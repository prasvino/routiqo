import type { paths } from '@routiqo/api-client';
import { readServerJourney, type JourneyCommand, type JourneyDelivery } from '@routiqo/shared';
import { browserCsrf, BrowserAuthError } from './browser-auth';

/** Used only by a future durable dispatcher, never by local planning draft operations. */
export async function sendBrowserJourney(
  accountId: string,
  command: JourneyCommand,
): Promise<JourneyDelivery> {
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
  if (
    !uuid.test(accountId) ||
    !uuid.test(command.journeyId) ||
    (command.action !== 'complete' &&
      (command.action !== 'start' || !['trip', 'commute'].includes(command.kind)))
  )
    return { outcome: 'rejected' };
  try {
    const csrf = await browserCsrf();
    const start:
      paths['/api/v1/journeys']['post']['requestBody']['content']['application/json'] | undefined =
      command.action === 'start' ? { id: command.journeyId, kind: command.kind } : undefined;
    const response = await fetch(
      command.action === 'start'
        ? '/api/v1/journeys'
        : `/api/v1/journeys/${command.journeyId}/complete`,
      {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': csrf,
          'X-Routiqo-Account': accountId,
        },
        body: JSON.stringify(start ?? {}),
        signal: AbortSignal.timeout(12000),
      },
    );
    if (response.status === 401 || response.status === 403) return { outcome: 'authentication' };
    if (response.status === 409) return { outcome: 'conflict' };
    if (response.status === 429 || response.status === 408 || response.status >= 500)
      return { outcome: 'transient' };
    if (response.status !== 200) return { outcome: 'rejected' };
    const raw = await response.text();
    if (raw.length > 4096) return { outcome: 'transient' };
    const journey = readServerJourney(JSON.parse(raw));
    if (
      journey.id !== command.journeyId ||
      (command.action === 'start' && journey.kind !== command.kind) ||
      (command.action === 'complete' && journey.status !== 'completed')
    )
      return { outcome: 'transient' };
    return { outcome: 'success', journey };
  } catch (failure) {
    if (failure instanceof BrowserAuthError && (failure.status === 401 || failure.status === 403))
      return { outcome: 'authentication' };
    return { outcome: 'transient' };
  }
}
