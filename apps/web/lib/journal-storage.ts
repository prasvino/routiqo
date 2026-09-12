import {
  readTripJournal,
  readTripJournalWrite,
  type TripJournal,
  type TripJournalWrite,
} from '@routiqo/shared';

const databaseName = 'routiqo-journal-v1';
const storeName = 'accounts';
const maximumEntries = 20;
const maximumBytes = 1024 * 1024;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export type BrowserJournalDraft = TripJournalWrite & { journeyId: string };

interface BrowserJournalPartition {
  version: 1;
  accountId: string;
  drafts: BrowserJournalDraft[];
  journals: TripJournal[];
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

function readAccount(account: string): string {
  if (!uuid.test(account)) throw new Error('Invalid journal account.');
  return account;
}

function readDraft(value: unknown): BrowserJournalDraft {
  if (!record(value) || typeof value.journeyId !== 'string' || !uuid.test(value.journeyId))
    throw new Error('Invalid saved journal draft.');
  return { journeyId: value.journeyId, ...readTripJournalWrite(value) };
}

function serializedBytes(value: unknown): number {
  return new TextEncoder().encode(JSON.stringify(value)).length;
}

function empty(accountId: string): BrowserJournalPartition {
  return { version: 1, accountId, drafts: [], journals: [] };
}

function read(value: unknown, accountId: string): BrowserJournalPartition {
  if (value === undefined) return empty(accountId);
  if (serializedBytes(value) > maximumBytes) throw new Error('Saved journals exceed their limit.');
  if (
    !record(value) ||
    value.version !== 1 ||
    value.accountId !== accountId ||
    !Array.isArray(value.drafts) ||
    value.drafts.length > maximumEntries ||
    !Array.isArray(value.journals) ||
    value.journals.length > maximumEntries
  )
    throw new Error('Saved journals cannot be read.');

  const drafts = value.drafts.map(readDraft);
  const journals = value.journals.map(readTripJournal);
  const draftIds = drafts.map((draft) => draft.journeyId);
  const journalIds = journals.map((journal) => journal.journey.id);
  if (
    new Set(draftIds).size !== draftIds.length ||
    new Set(journalIds).size !== journalIds.length ||
    draftIds.some((journeyId) => !journalIds.includes(journeyId))
  )
    throw new Error('Saved journals cannot be read.');
  return { version: 1, accountId, drafts, journals };
}

function stored(partition: BrowserJournalPartition): BrowserJournalPartition {
  const canonical = read(partition, partition.accountId);
  if (serializedBytes(canonical) > maximumBytes)
    throw new Error('Saved journals exceed their limit.');
  return canonical;
}

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1);
    let finished = false;
    const fail = () => {
      if (!finished) {
        finished = true;
        clearTimeout(timeout);
        reject(new Error('Journal storage is unavailable.'));
      }
    };
    const timeout = setTimeout(fail, 5000);
    request.onupgradeneeded = () => {
      if (finished) {
        request.transaction?.abort();
        return;
      }
      if (!request.result.objectStoreNames.contains(storeName))
        request.result.createObjectStore(storeName);
    };
    request.onerror = fail;
    request.onblocked = fail;
    request.onsuccess = () => {
      if (finished) {
        request.result.close();
        return;
      }
      finished = true;
      clearTimeout(timeout);
      request.result.onversionchange = () => request.result.close();
      resolve(request.result);
    };
  });
}

async function transaction<T>(
  account: string,
  change: (partition: BrowserJournalPartition) => {
    value?: BrowserJournalPartition | { version: 1; retired: true };
    result: T;
  },
  allowRetired = false,
): Promise<T> {
  const accountId = readAccount(account);
  const db = await open();
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction(storeName, 'readwrite', { durability: 'strict' });
      const store = tx.objectStore(storeName);
      let result!: T;
      let failure: unknown;
      tx.oncomplete = () => resolve(result);
      tx.onabort = () => reject(failure ?? new Error('Journal changes could not be saved.'));
      tx.onerror = () => {
        /* Abort reports failure; never resolve a failed transaction. */
      };
      const request = store.get(accountId);
      request.onsuccess = () => {
        try {
          if (!allowRetired && record(request.result) && request.result.retired === true)
            throw new Error('This account was deleted from this device.');
          const next = change(allowRetired ? empty(accountId) : read(request.result, accountId));
          result = next.result;
          if (next.value !== undefined) store.put(next.value, accountId);
        } catch (error) {
          failure = error;
          tx.abort();
        }
      };
    });
  } finally {
    db.close();
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

function sameDraft(left: BrowserJournalDraft, right: BrowserJournalDraft): boolean {
  return (
    left.journeyId === right.journeyId &&
    left.title === right.title &&
    left.notes === right.notes &&
    left.expectedVersion === right.expectedVersion &&
    left.mutationId === right.mutationId
  );
}

function mergeJournal(
  partition: BrowserJournalPartition,
  input: unknown,
): { partition: BrowserJournalPartition; journal: TripJournal } {
  const journal = readTripJournal(input);
  const current = partition.journals.find((item) => item.journey.id === journal.journey.id);
  if (current) {
    if (!sameJourney(current, journal))
      throw new Error('Journal lifecycle conflicts with the saved trip.');
    if (journal.annotation.version < current.annotation.version)
      throw new Error('Journal version cannot move backwards.');
    if (journal.annotation.version === current.annotation.version) {
      if (!sameJournal(current, journal))
        throw new Error('Journal content conflicts with the saved version.');
      return { partition, journal: current };
    }
  }

  const journals = partition.journals.filter((item) => item.journey.id !== journal.journey.id);
  if (journals.length >= maximumEntries) {
    const protectedIds = new Set(partition.drafts.map((draft) => draft.journeyId));
    const eviction = journals.reduce(
      (oldest, item, index) =>
        protectedIds.has(item.journey.id) ||
        (oldest >= 0 &&
          journals[oldest]!.journey.startedAt.localeCompare(item.journey.startedAt) <= 0)
          ? oldest
          : index,
      -1,
    );
    if (eviction < 0) throw new Error('Saved journal work has reached its limit.');
    journals.splice(eviction, 1);
  }
  journals.push(journal);
  journals.sort(
    (left, right) =>
      right.journey.startedAt.localeCompare(left.journey.startedAt) ||
      right.journey.id.localeCompare(left.journey.id),
  );
  return { partition: { ...partition, journals }, journal };
}

export function readBrowserJournal(
  account: string,
  journeyId: string,
): Promise<{ draft: BrowserJournalDraft | null; journal: TripJournal | null }> {
  if (!uuid.test(journeyId)) return Promise.reject(new Error('Invalid journal identity.'));
  return transaction(account, (partition) => ({
    result: {
      draft: partition.drafts.find((draft) => draft.journeyId === journeyId) ?? null,
      journal: partition.journals.find((journal) => journal.journey.id === journeyId) ?? null,
    },
  }));
}

/** Retained journals remain discoverable even after the recent journey list changes. */
export function listBrowserJournals(
  account: string,
): Promise<{ draft: BrowserJournalDraft | null; journal: TripJournal }[]> {
  return transaction(account, (partition) => ({
    result: partition.journals.map((journal) => ({
      journal,
      draft: partition.drafts.find((draft) => draft.journeyId === journal.journey.id) ?? null,
    })),
  }));
}

export function cacheBrowserTripJournal(account: string, input: unknown): Promise<TripJournal> {
  return transaction(account, (partition) => {
    const merged = mergeJournal(partition, input);
    return { value: stored(merged.partition), result: merged.journal };
  });
}

export function saveBrowserJournalDraft(
  account: string,
  draft: BrowserJournalDraft,
  expectedPreviousMutationId: string | null,
): Promise<BrowserJournalDraft> {
  const canonical = readDraft(draft);
  if (expectedPreviousMutationId !== null && !uuid.test(expectedPreviousMutationId))
    return Promise.reject(new Error('Invalid previous journal mutation.'));
  return transaction(account, (partition) => {
    const confirmed = partition.journals.find(
      (journal) => journal.journey.id === canonical.journeyId,
    );
    if (!confirmed) throw new Error('Restore the completed trip before editing its journal.');
    if (canonical.expectedVersion > confirmed.annotation.version)
      throw new Error('This journal draft is based on an unconfirmed version.');
    const current = partition.drafts.find((item) => item.journeyId === canonical.journeyId);
    if ((current?.mutationId ?? null) !== expectedPreviousMutationId)
      throw new Error('This journal draft changed in another tab.');
    if (current?.mutationId === canonical.mutationId) {
      if (!sameDraft(current, canonical))
        throw new Error('A journal mutation cannot be reused for different text.');
      return { result: current };
    }
    if (!current && partition.drafts.length >= maximumEntries)
      throw new Error('Saved journal work has reached its limit.');
    const drafts = [
      ...partition.drafts.filter((item) => item.journeyId !== canonical.journeyId),
      canonical,
    ];
    return { value: stored({ ...partition, drafts }), result: canonical };
  });
}

export function acknowledgeBrowserJournalDraft(
  account: string,
  journeyId: string,
  expectedMutationId: string,
  response: unknown,
): Promise<boolean> {
  if (!uuid.test(journeyId) || !uuid.test(expectedMutationId))
    return Promise.reject(new Error('Invalid journal acknowledgement.'));
  return transaction(account, (partition) => {
    const draft = partition.drafts.find((item) => item.journeyId === journeyId);
    if (!draft || draft.mutationId !== expectedMutationId) return { result: false };
    const journal = readTripJournal(response);
    if (
      journal.journey.id !== journeyId ||
      journal.annotation.title !== draft.title ||
      journal.annotation.notes !== draft.notes ||
      journal.annotation.version !== draft.expectedVersion + 1
    )
      throw new Error('Server journal does not match the saved draft.');
    const merged = mergeJournal(partition, journal);
    const next = {
      ...merged.partition,
      drafts: merged.partition.drafts.filter((item) => item.journeyId !== journeyId),
    };
    return { value: stored(next), result: true };
  });
}

/** Remove only the draft and confirmed version the user explicitly reviewed. */
export function discardBrowserJournalDraft(
  account: string,
  journeyId: string,
  expectedMutationId: string,
  reviewed: TripJournal,
): Promise<TripJournal> {
  if (!uuid.test(journeyId) || !uuid.test(expectedMutationId))
    return Promise.reject(new Error('Invalid journal discard identity.'));
  const expected = readTripJournal(reviewed);
  if (expected.journey.id !== journeyId)
    return Promise.reject(new Error('Reviewed journal does not match this trip.'));
  return transaction(account, (partition) => {
    const draft = partition.drafts.find((item) => item.journeyId === journeyId);
    const confirmed = partition.journals.find((item) => item.journey.id === journeyId);
    if (!draft || draft.mutationId !== expectedMutationId)
      throw new Error('This journal draft changed in another tab. Reopen it before discarding.');
    if (!confirmed || !sameJournal(confirmed, expected))
      throw new Error('The saved account version changed. Review it again before discarding.');
    return {
      value: stored({
        ...partition,
        drafts: partition.drafts.filter((item) => item.journeyId !== journeyId),
      }),
      result: confirmed,
    };
  });
}

/** Account deletion keeps an opaque marker so late work cannot recreate private content. */
export function retireBrowserJournalPartition(account: string): Promise<void> {
  return transaction(
    account,
    () => ({ value: { version: 1, retired: true }, result: undefined }),
    true,
  );
}
