import {
  readJourneyOutbox,
  readJourneySnapshots,
  recordJourneyResult,
  settleJourneyCommand,
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
