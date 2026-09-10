import {
  readJourneyOutbox,
  readJourneySnapshots,
  readServerJourney,
  recordJourneyResult,
  settleJourneyCommand,
  enqueueJourneyCommand,
  type JourneyCommand,
  type JourneyOutbox,
  type JourneySnapshots,
} from '@routiqo/shared';
const databaseName = 'routiqo-journeys-v1';
const storeName = 'accounts';
export interface BrowserJourneyPartition {
  outbox: JourneyOutbox;
  snapshots: JourneySnapshots;
}
function read(value: unknown, account: string): BrowserJourneyPartition {
  if (value === undefined)
    return {
      outbox: readJourneyOutbox(null, account),
      snapshots: readJourneySnapshots(null, account),
    };
  if (
    typeof value !== 'object' ||
    value === null ||
    !('version' in value) ||
    value.version !== 1 ||
    !('outbox' in value) ||
    typeof value.outbox !== 'string' ||
    !('snapshots' in value) ||
    typeof value.snapshots !== 'string'
  )
    throw new Error('Saved journey work cannot be read.');
  return {
    outbox: readJourneyOutbox(value.outbox, account),
    snapshots: readJourneySnapshots(value.snapshots, account),
  };
}
function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1);
    let finished = false;
    const fail = () => {
      if (!finished) {
        finished = true;
        clearTimeout(timeout);
        reject(new Error('Journey storage is unavailable.'));
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
  change: (stored: unknown) => { value?: unknown; remove?: boolean; result: T },
  allowRetired = false,
): Promise<T> {
  readJourneyOutbox(null, account);
  const db = await open();
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction(storeName, 'readwrite', { durability: 'strict' });
      const store = tx.objectStore(storeName);
      let result: T;
      let failure: unknown;
      tx.oncomplete = () => resolve(result);
      tx.onabort = () => reject(failure ?? new Error('Journey changes could not be saved.'));
      tx.onerror = () => {
        /* Abort reports failure; never resolve a failed transaction. */
      };
      const request = store.get(account);
      request.onsuccess = () => {
        try {
          if (
            !allowRetired &&
            typeof request.result === 'object' &&
            request.result?.retired === true
          )
            throw new Error('This account was deleted from this device.');
          const next = change(request.result);
          result = next.result;
          if (next.remove) store.delete(account);
          else if (next.value !== undefined) store.put(next.value, account);
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
function stored(partition: BrowserJourneyPartition, account: string) {
  const outbox = JSON.stringify(partition.outbox),
    snapshots = JSON.stringify(partition.snapshots);
  readJourneyOutbox(outbox, account);
  readJourneySnapshots(snapshots, account);
  return { version: 1, outbox, snapshots };
}
/** User lifecycle writes check both queue and snapshots in the same cross-tab transaction. */
export function queueBrowserJourneyAction(
  account: string,
  command: JourneyCommand,
  now: number,
): Promise<BrowserJourneyPartition> {
  return transaction(account, (value) => {
    const partition = read(value, account);
    const known = partition.snapshots.journeys.find((item) => item.id === command.journeyId);
    if (command.action === 'start') {
      if (known) {
        if (known.kind !== command.kind)
          throw new Error('This journey already has a different kind.');
        return { result: partition };
      }
      const otherActive = partition.snapshots.journeys.some(
        (item) => item.status === 'active' && item.id !== command.journeyId,
      );
      const otherPending = partition.outbox.entries.some(
        (item) => item.command.journeyId !== command.journeyId,
      );
      if (otherActive || otherPending)
        throw new Error('Finish or resolve your current journey before starting another.');
    } else {
      if (known?.status === 'completed') return { result: partition };
      const pendingStart = partition.outbox.entries.some(
        (item) => item.command.journeyId === command.journeyId && item.command.action === 'start',
      );
      if (!known && !pendingStart)
        throw new Error('This journey must be restored before finishing.');
    }
    const outbox = enqueueJourneyCommand(partition.outbox, command, now);
    const result = { ...partition, outbox };
    return { value: stored(result, account), result };
  });
}
export function readBrowserJourneyPartition(account: string): Promise<BrowserJourneyPartition> {
  return transaction(account, (value) => ({ result: read(value, account) }));
}
export function updateBrowserJourneyOutbox(
  account: string,
  change: (outbox: JourneyOutbox) => JourneyOutbox,
): Promise<JourneyOutbox> {
  return transaction(account, (value) => {
    const partition = read(value, account);
    const outbox = readJourneyOutbox(JSON.stringify(change(partition.outbox)), account);
    return { value: stored({ ...partition, outbox }, account), result: outbox };
  });
}
export function acknowledgeBrowserJourney(
  account: string,
  lease: string,
  response: unknown,
  now: number,
): Promise<boolean> {
  return transaction(account, (value) => {
    const partition = read(value, account);
    const head = partition.outbox.entries[0];
    if (!head || head.lease?.token !== lease) return { result: false };
    const snapshots = recordJourneyResult(partition.snapshots, head.command, response);
    const outbox = settleJourneyCommand(partition.outbox, lease, 'success', now);
    return { value: stored({ outbox, snapshots }, account), result: true };
  });
}
export function clearBrowserJourneyPartition(account: string): Promise<void> {
  return transaction(account, () => ({ remove: true, result: undefined }));
}

/** Account deletion keeps only an opaque marker so late workers cannot recreate private data. */
export function retireBrowserJourneyPartition(account: string): Promise<void> {
  return transaction(
    account,
    () => ({ value: { version: 1, retired: true }, result: undefined }),
    true,
  );
}

export function mergeBrowserJourneyHistory(
  account: string,
  responses: unknown[],
): Promise<BrowserJourneyPartition> {
  // One bounded page plus an older cached active journey resolved by owner-bound detail.
  if (responses.length > 21) throw new Error('Too many journeys to restore at once.');
  return transaction(account, (value) => {
    const partition = read(value, account);
    let snapshots = partition.snapshots;
    for (const input of responses) {
      const journey = readServerJourney(input);
      snapshots = recordJourneyResult(
        snapshots,
        { action: 'start', kind: journey.kind, journeyId: journey.id },
        journey,
      );
    }
    if (snapshots.journeys.filter((journey) => journey.status === 'active').length > 1)
      throw new Error('Multiple active records need individual reconciliation.');
    const result = { ...partition, snapshots };
    return { value: stored(result, account), result };
  });
}

/** Reconcile only a still-blocked matching action whose result the server has already applied. */
export function reconcileBrowserJourney(
  account: string,
  expected: JourneyCommand,
  response: unknown,
): Promise<boolean> {
  return transaction(account, (value) => {
    const partition = read(value, account);
    const head = partition.outbox.entries[0];
    if (
      !head ||
      !['conflict', 'rejected'].includes(head.blocked ?? '') ||
      head.lease ||
      head.command.journeyId !== expected.journeyId ||
      head.command.action !== expected.action ||
      (head.command.action === 'start' &&
        expected.action === 'start' &&
        head.command.kind !== expected.kind)
    )
      return { result: false };
    const snapshots = recordJourneyResult(partition.snapshots, head.command, response);
    const outbox = { ...partition.outbox, entries: partition.outbox.entries.slice(1) };
    return { value: stored({ outbox, snapshots }, account), result: true };
  });
}
