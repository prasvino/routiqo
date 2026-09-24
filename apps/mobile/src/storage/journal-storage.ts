import {
  readTripJournal,
  readTripJournalWrite,
  type TripJournal,
  type TripJournalWrite,
} from '@routiqo/shared';
import type { OutboxDatabase } from './journey-outbox';

const maximumEntries = 20;
const maximumBytes = 1024 * 1024;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const nilUuid = '00000000-0000-0000-0000-000000000000';
const bytes = (value: string) => new TextEncoder().encode(value).length;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const exactKeys = (value: unknown, keys: string[]): value is Record<string, unknown> =>
  record(value) &&
  Object.keys(value).length === keys.length &&
  keys.every((key) => Object.hasOwn(value, key));

export type NativeJournalDraft = TripJournalWrite & { journeyId: string };
interface JournalPartition {
  version: 1;
  accountId: string;
  drafts: NativeJournalDraft[];
  journals: TripJournal[];
}
export type NativeJournalStorageErrorCode =
  'full' | 'corrupt' | 'retired' | 'conflict' | 'unavailable';
export class NativeJournalStorageError extends Error {
  constructor(
    public readonly code: NativeJournalStorageErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'NativeJournalStorageError';
  }
}
function fail(code: NativeJournalStorageErrorCode, message: string): never {
  throw new NativeJournalStorageError(code, message);
}
function identity(value: string, label: string): string {
  if (typeof value !== 'string' || value.length !== 36 || value === nilUuid || !uuid.test(value))
    throw new Error(`Invalid ${label}.`);
  return value;
}
function draft(value: unknown): NativeJournalDraft {
  if (!record(value) || typeof value.journeyId !== 'string')
    throw new Error('Invalid saved journal draft.');
  const journeyId = identity(value.journeyId, 'saved journal draft');
  const write = readTripJournalWrite(value);
  identity(write.mutationId, 'saved journal mutation');
  return { journeyId, ...write };
}
function journal(value: unknown): TripJournal {
  const parsed = readTripJournal(value);
  identity(parsed.journey.id, 'saved trip journal');
  return parsed;
}
function empty(accountId: string): JournalPartition {
  return { version: 1, accountId, drafts: [], journals: [] };
}
function read(raw: string | null, accountId: string): JournalPartition {
  if (raw === null) return empty(accountId);
  if (bytes(raw) > maximumBytes) fail('corrupt', 'Saved journals exceed their limit.');
  let value: unknown;
  try {
    value = JSON.parse(raw);
    if (
      !exactKeys(value, ['version', 'accountId', 'drafts', 'journals']) ||
      value.version !== 1 ||
      value.accountId !== accountId ||
      !Array.isArray(value.drafts) ||
      value.drafts.length > maximumEntries ||
      !Array.isArray(value.journals) ||
      value.journals.length > maximumEntries
    )
      throw new Error('Invalid journal partition.');
    if (
      value.drafts.some(
        (item) =>
          !exactKeys(item, ['journeyId', 'title', 'notes', 'expectedVersion', 'mutationId']),
      ) ||
      value.journals.some(
        (item) =>
          !exactKeys(item, ['journey', 'annotation']) ||
          !exactKeys(record(item) ? item.journey : null, [
            'id',
            'kind',
            'status',
            'startedAt',
            'completedAt',
          ]) ||
          !exactKeys(record(item) ? item.annotation : null, [
            'title',
            'notes',
            'version',
            'updatedAt',
          ]),
      )
    )
      throw new Error('Invalid journal partition.');
    const drafts = value.drafts.map(draft);
    const journals = value.journals.map(journal);
    const draftIds = drafts.map((item) => item.journeyId);
    const journalIds = journals.map((item) => item.journey.id);
    if (
      new Set(draftIds).size !== draftIds.length ||
      new Set(drafts.map((item) => item.mutationId)).size !== drafts.length ||
      new Set(journalIds).size !== journalIds.length ||
      draftIds.some((id) => !journalIds.includes(id)) ||
      drafts.some(
        (item) =>
          item.expectedVersion >
          journals.find((journal) => journal.journey.id === item.journeyId)!.annotation.version,
      )
    )
      throw new Error('Invalid journal partition.');
    return { version: 1, accountId, drafts, journals };
  } catch {
    fail('corrupt', 'Saved journals cannot be read.');
  }
}
function serialized(value: JournalPartition): string {
  const payload = JSON.stringify(value);
  if (bytes(payload) > maximumBytes) fail('full', 'Saved journal work has reached its limit.');
  const canonical = read(payload, value.accountId);
  const output = JSON.stringify(canonical);
  if (bytes(output) > maximumBytes) fail('full', 'Saved journal work has reached its limit.');
  return output;
}
type Transaction = Parameters<OutboxDatabase['withExclusiveTransactionAsync']>[0] extends (
  tx: infer T,
) => Promise<void>
  ? T
  : never;
async function transact<T>(
  db: OutboxDatabase,
  account: string,
  change: (partition: JournalPartition) => { next?: JournalPartition; result: T },
): Promise<T> {
  const accountId = identity(account, 'journal account');
  let result!: T;
  let completed = false;
  try {
    await db.withExclusiveTransactionAsync(async (tx: Transaction) => {
      const retired = await tx.getFirstAsync<{ account_id: string }>(
        'SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?',
        accountId,
      );
      if (retired) fail('retired', 'This account was deleted from this device.');
      const row = await tx.getFirstAsync<{ payload: string }>(
        'SELECT payload FROM journal_partitions_v1 WHERE account_id = ?',
        accountId,
      );
      const partition = read(row?.payload ?? null, accountId);
      let update: { next?: JournalPartition; result: T };
      try {
        update = change(partition);
      } catch (error) {
        if (error instanceof NativeJournalStorageError) throw error;
        fail('conflict', 'Journal data could not be reconciled.');
      }
      if (update.next) {
        await tx.runAsync(
          `INSERT INTO journal_partitions_v1 (account_id, payload) VALUES (?, ?)
          ON CONFLICT(account_id) DO UPDATE SET payload = excluded.payload`,
          accountId,
          serialized(update.next),
        );
      }
      result = update.result;
      completed = true;
    });
  } catch (error) {
    if (error instanceof NativeJournalStorageError) throw error;
    fail('unavailable', 'Journal storage is unavailable.');
  }
  if (!completed) fail('unavailable', 'Journal storage is unavailable.');
  return result;
}

export async function initializeNativeJournalStorage(db: Pick<OutboxDatabase, 'execAsync'>) {
  try {
    await db.execAsync(`CREATE TABLE IF NOT EXISTS journal_partitions_v1 (
      account_id TEXT PRIMARY KEY NOT NULL,
      payload TEXT NOT NULL
    );`);
  } catch {
    fail('unavailable', 'Journal storage is unavailable.');
  }
}
function sameJourney(left: TripJournal, right: TripJournal): boolean {
  return (
    left.journey.id === right.journey.id &&
    left.journey.kind === right.journey.kind &&
    left.journey.status === right.journey.status &&
    left.journey.startedAt === right.journey.startedAt &&
    left.journey.completedAt === right.journey.completedAt
  );
}
function sameJournal(left: TripJournal, right: TripJournal): boolean {
  return (
    sameJourney(left, right) &&
    left.annotation.title === right.annotation.title &&
    left.annotation.notes === right.annotation.notes &&
    left.annotation.version === right.annotation.version &&
    left.annotation.updatedAt === right.annotation.updatedAt
  );
}
function sameDraft(left: NativeJournalDraft, right: NativeJournalDraft): boolean {
  return (
    left.journeyId === right.journeyId &&
    left.title === right.title &&
    left.notes === right.notes &&
    left.expectedVersion === right.expectedVersion &&
    left.mutationId === right.mutationId
  );
}
function merge(
  partition: JournalPartition,
  input: unknown,
): { next: JournalPartition; journal: TripJournal } {
  const journal = readTripJournal(input);
  identity(journal.journey.id, 'trip journal');
  const current = partition.journals.find((item) => item.journey.id === journal.journey.id);
  if (current) {
    if (!sameJourney(current, journal))
      throw new Error('Journal lifecycle conflicts with the saved trip.');
    if (journal.annotation.version < current.annotation.version)
      throw new Error('Journal version cannot move backwards.');
    if (journal.annotation.version === current.annotation.version) {
      if (!sameJournal(current, journal))
        throw new Error('Journal content conflicts with the saved version.');
      return { next: partition, journal: current };
    }
  }
  const journals = partition.journals.filter((item) => item.journey.id !== journal.journey.id);
  if (journals.length >= maximumEntries) {
    const protectedIds = new Set(partition.drafts.map((item) => item.journeyId));
    const oldest = journals.reduce(
      (selected, item, index) =>
        protectedIds.has(item.journey.id) ||
        (selected >= 0 && journals[selected]!.journey.startedAt <= item.journey.startedAt)
          ? selected
          : index,
      -1,
    );
    if (oldest < 0) fail('full', 'Saved journal work has reached its limit.');
    journals.splice(oldest, 1);
  }
  journals.push(journal);
  journals.sort(
    (a, b) =>
      b.journey.startedAt.localeCompare(a.journey.startedAt) ||
      b.journey.id.localeCompare(a.journey.id),
  );
  return { next: { ...partition, journals }, journal };
}

export function readNativeStoredJournal(db: OutboxDatabase, account: string, journeyId: string) {
  identity(journeyId, 'journal identity');
  return transact(db, account, (partition) => ({
    result: {
      draft: partition.drafts.find((item) => item.journeyId === journeyId) ?? null,
      journal: partition.journals.find((item) => item.journey.id === journeyId) ?? null,
    },
  }));
}
export function listNativeStoredJournals(
  db: OutboxDatabase,
  account: string,
): Promise<
  {
    draft: NativeJournalDraft | null;
    journal: TripJournal;
  }[]
> {
  return transact(db, account, (partition) => ({
    result: partition.journals.map((journal) => ({
      journal,
      draft: partition.drafts.find((item) => item.journeyId === journal.journey.id) ?? null,
    })),
  }));
}
export function cacheNativeTripJournal(
  db: OutboxDatabase,
  account: string,
  input: unknown,
): Promise<TripJournal> {
  return transact(db, account, (partition) => {
    const merged = merge(partition, input);
    return { next: merged.next, result: merged.journal };
  });
}
export function saveNativeJournalDraft(
  db: OutboxDatabase,
  account: string,
  input: NativeJournalDraft,
  expectedPreviousMutationId: string | null,
): Promise<NativeJournalDraft> {
  const canonical = draft(input);
  if (expectedPreviousMutationId !== null)
    identity(expectedPreviousMutationId, 'previous journal mutation');
  return transact(db, account, (partition) => {
    const confirmed = partition.journals.find((item) => item.journey.id === canonical.journeyId);
    if (!confirmed) throw new Error('Restore the completed trip before editing its journal.');
    if (canonical.expectedVersion > confirmed.annotation.version)
      throw new Error('This journal draft is based on an unconfirmed version.');
    const current = partition.drafts.find((item) => item.journeyId === canonical.journeyId);
    if ((current?.mutationId ?? null) !== expectedPreviousMutationId)
      fail('conflict', 'This journal draft changed on this device.');
    if (current?.mutationId === canonical.mutationId) {
      if (!sameDraft(current, canonical))
        throw new Error('A journal mutation cannot be reused for different text.');
      return { result: current };
    }
    if (!current && partition.drafts.length >= maximumEntries)
      fail('full', 'Saved journal work has reached its limit.');
    if (partition.drafts.some((item) => item.mutationId === canonical.mutationId))
      throw new Error('A journal mutation cannot be reused for another trip.');
    return {
      next: {
        ...partition,
        drafts: [
          ...partition.drafts.filter((item) => item.journeyId !== canonical.journeyId),
          canonical,
        ],
      },
      result: canonical,
    };
  });
}
export function acknowledgeNativeJournalDraft(
  db: OutboxDatabase,
  account: string,
  journeyId: string,
  expectedMutationId: string,
  response: unknown,
): Promise<boolean> {
  identity(journeyId, 'journal acknowledgement');
  identity(expectedMutationId, 'journal acknowledgement');
  return transact(db, account, (partition) => {
    const current = partition.drafts.find((item) => item.journeyId === journeyId);
    if (!current || current.mutationId !== expectedMutationId) return { result: false };
    const journal = readTripJournal(response);
    if (
      journal.journey.id !== journeyId ||
      journal.annotation.title !== current.title ||
      journal.annotation.notes !== current.notes ||
      journal.annotation.version !== current.expectedVersion + 1
    )
      throw new Error('Server journal does not match the saved draft.');
    const merged = merge(partition, journal);
    return {
      next: {
        ...merged.next,
        drafts: merged.next.drafts.filter((item) => item.journeyId !== journeyId),
      },
      result: true,
    };
  });
}
export function discardNativeJournalDraft(
  db: OutboxDatabase,
  account: string,
  journeyId: string,
  expectedMutationId: string,
  reviewed: TripJournal,
): Promise<TripJournal> {
  identity(journeyId, 'journal discard identity');
  identity(expectedMutationId, 'journal discard identity');
  const expected = readTripJournal(reviewed);
  if (expected.journey.id !== journeyId)
    throw new Error('Reviewed journal does not match this trip.');
  return transact(db, account, (partition) => {
    const current = partition.drafts.find((item) => item.journeyId === journeyId);
    const confirmed = partition.journals.find((item) => item.journey.id === journeyId);
    if (!current || current.mutationId !== expectedMutationId)
      fail('conflict', 'This journal draft changed on this device. Reopen it before discarding.');
    if (!confirmed || !sameJournal(confirmed, expected))
      fail('conflict', 'The saved account version changed. Review it again before discarding.');
    return {
      next: {
        ...partition,
        drafts: partition.drafts.filter((item) => item.journeyId !== journeyId),
      },
      result: confirmed,
    };
  });
}
