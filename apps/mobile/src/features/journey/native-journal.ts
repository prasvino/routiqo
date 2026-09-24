import { readTripJournal, type TripJournal } from '@routiqo/shared';
import type { createNativeAccount } from '../../auth/native-account';

type Account = ReturnType<typeof createNativeAccount>;
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const nil = '00000000-0000-0000-0000-000000000000';
const DEADLINE_MS = 12_000;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const exact = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every((key) => key in value);

export function readNativeTripJournal(value: unknown, journeyId: string): TripJournal {
  if (
    !record(value) ||
    !exact(value, ['journey', 'annotation']) ||
    !record(value.journey) ||
    !exact(value.journey, ['id', 'kind', 'status', 'startedAt', 'completedAt']) ||
    !record(value.annotation) ||
    !exact(value.annotation, ['title', 'notes', 'version', 'updatedAt'])
  )
    throw new Error('Invalid native trip journal.');
  const journal = readTripJournal(value);
  if (journal.journey.id !== journeyId) throw new Error('Native trip journal identity changed.');
  return journal;
}

/** Private, explicit read only. The caller owns any display or session-scoped state. */
export async function readNativeJournal(
  identity: Account,
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<TripJournal> {
  if (
    accountId.length !== 36 ||
    journeyId.length !== 36 ||
    !uuid.test(accountId) ||
    !uuid.test(journeyId) ||
    accountId === nil ||
    journeyId === nil
  )
    throw new Error('Invalid native journal identity.');
  if (signal?.aborted) throw new DOMException('Journal request cancelled.', 'AbortError');
  if (identity.activeAccount() !== accountId) throw new Error('Native journal account changed.');
  const revision = identity.revision();

  const controller = new AbortController();
  let rejectStop!: (reason: Error) => void;
  const stopped = new Promise<never>((_, reject) => {
    rejectStop = reject;
  });
  void stopped.catch(() => undefined);
  let active = true;
  const stop = (reason: Error) => {
    if (!active) return;
    active = false;
    controller.abort();
    rejectStop(reason);
  };
  const onAbort = () => stop(new DOMException('Journal request cancelled.', 'AbortError'));
  signal?.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => stop(new Error('Native journal request timed out.')), DEADLINE_MS);
  try {
    if (signal?.aborted) onAbort();
    const pending = identity.verifiedRequest(
      `/api/v1/native/journeys/${journeyId}/journal`,
      'GET',
      {
        accountId,
        signal: controller.signal,
      },
    );
    void pending.catch(() => undefined);
    const raw = await Promise.race([pending, stopped]);
    if (!active) throw new DOMException('Journal request cancelled.', 'AbortError');
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new Error('Native journal account changed.');
    const journal = readNativeTripJournal(raw, journeyId);
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new Error('Native journal account changed.');
    return journal;
  } finally {
    active = false;
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}
