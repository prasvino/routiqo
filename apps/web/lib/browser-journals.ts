import { readTripJournal, readTripJournalWrite, type TripJournalWrite } from '@routiqo/shared';
import { browserCsrf, BrowserAuthError } from './browser-auth';

export class BrowserJournalError extends Error {
  constructor(public readonly status: number) {
    super(
      status === 409
        ? 'This journal changed or is not available for editing. Review the latest version.'
        : status === 401 || status === 403
          ? 'Sign in again to open this journal.'
          : status === 404
            ? 'This trip journal could not be found.'
            : status === 429
              ? 'Too many journal saves. Wait a minute and try again.'
              : 'The journal is unavailable. Your unsent text has not been saved to the server.',
    );
  }
}
export function readBrowserTripJournal(accountId: string, journeyId: string, signal?: AbortSignal) {
  return request(accountId, journeyId, undefined, signal);
}
/** Caller must durably retain this mutation before invoking transport; no automatic retry or storage here. */
export function saveBrowserTripJournal(
  accountId: string,
  journeyId: string,
  input: unknown,
  signal?: AbortSignal,
) {
  return request(accountId, journeyId, readTripJournalWrite(input), signal);
}
async function request(
  accountId: string,
  journeyId: string,
  edit?: TripJournalWrite,
  signal?: AbortSignal,
) {
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
  if (!uuid.test(accountId) || !uuid.test(journeyId)) throw new Error('Invalid journal identity.');
  const cancellation = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(18000)])
    : AbortSignal.timeout(18000);
  try {
    cancellation.throwIfAborted();
    const headers: Record<string, string> = { 'X-Routiqo-Account': accountId };
    if (edit) {
      headers['Content-Type'] = 'application/json';
      headers['X-XSRF-TOKEN'] = await browserCsrf();
    }
    cancellation.throwIfAborted();
    const response = await fetch(`/api/v1/journeys/${journeyId}/journal`, {
      method: edit ? 'POST' : 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers,
      ...(edit ? { body: JSON.stringify(edit) } : {}),
      signal: cancellation,
    });
    if (!response.ok) throw new BrowserJournalError(response.status);
    if (!response.body) throw new BrowserJournalError(503);
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let raw = '',
      bytes = 0;
    try {
      while (true) {
        cancellation.throwIfAborted();
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.byteLength;
        if (bytes > 32768) throw new BrowserJournalError(503);
        raw += decoder.decode(chunk.value, { stream: true });
      }
      raw += decoder.decode();
      cancellation.throwIfAborted();
      const journal = readTripJournal(JSON.parse(raw));
      if (
        journal.journey.id !== journeyId ||
        (edit &&
          (journal.annotation.title !== edit.title ||
            journal.annotation.notes !== edit.notes ||
            journal.annotation.version !== edit.expectedVersion + 1))
      )
        throw new BrowserJournalError(503);
      return journal;
    } finally {
      await reader.cancel().catch(() => undefined);
      reader.releaseLock();
    }
  } catch (failure) {
    if (signal?.aborted) throw new DOMException('Journal request cancelled.', 'AbortError');
    if (failure instanceof BrowserJournalError) throw failure;
    if (failure instanceof BrowserAuthError) throw new BrowserJournalError(failure.status);
    throw new BrowserJournalError(503);
  }
}
