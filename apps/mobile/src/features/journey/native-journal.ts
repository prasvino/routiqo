import {
  readTripJournal,
  readTripJournalWrite,
  type TripJournal,
  type TripJournalWrite,
} from '@routiqo/shared';
import type { createNativeAccount } from '../../auth/native-account';
import { nativeAbortError } from '../../auth/abort-error';

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

/** Private, explicit transport. The caller owns durable drafts and acknowledgement. */
async function requestNativeJournal(
  identity: Account,
  accountId: string,
  journeyId: string,
  input: TripJournalWrite | undefined,
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
  if (signal?.aborted) throw nativeAbortError('Journal request cancelled.');
  const edit = input === undefined ? undefined : readTripJournalWrite(input);
  if (edit !== undefined && (edit.mutationId.length !== 36 || edit.mutationId === nil))
    throw new Error('Invalid native journal edit.');
  if (
    input !== undefined &&
    (!record(input) || !exact(input, ['title', 'notes', 'expectedVersion', 'mutationId']))
  )
    throw new Error('Invalid native journal edit.');
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
  const onAbort = () => stop(nativeAbortError('Journal request cancelled.'));
  signal?.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => stop(new Error('Native journal request timed out.')), DEADLINE_MS);
  try {
    if (signal?.aborted) onAbort();
    const pending = identity.verifiedRequest(
      `/api/v1/native/journeys/${journeyId}/journal`,
      edit === undefined ? 'GET' : 'POST',
      {
        accountId,
        ...(edit === undefined ? {} : { body: edit }),
        signal: controller.signal,
      },
    );
    void pending.catch(() => undefined);
    const raw = await Promise.race([pending, stopped]);
    if (!active) throw nativeAbortError('Journal request cancelled.');
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new Error('Native journal account changed.');
    const journal = readNativeTripJournal(raw, journeyId);
    if (
      edit !== undefined &&
      (journal.annotation.title !== edit.title ||
        journal.annotation.notes !== edit.notes ||
        journal.annotation.version !== edit.expectedVersion + 1)
    )
      throw new Error('Native journal acknowledgement is invalid.');
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new Error('Native journal account changed.');
    return journal;
  } finally {
    active = false;
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}

export function readNativeJournal(
  identity: Account,
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<TripJournal> {
  return requestNativeJournal(identity, accountId, journeyId, undefined, signal);
}

export function writeNativeJournal(
  identity: Account,
  accountId: string,
  journeyId: string,
  input: TripJournalWrite,
  signal?: AbortSignal,
): Promise<TripJournal> {
  return requestNativeJournal(identity, accountId, journeyId, input, signal);
}
