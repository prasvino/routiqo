import { IDBFactory, IDBObjectStore } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  acknowledgeBrowserJourney,
  clearBrowserJourneyPartition,
  readBrowserJourneyPartition,
  updateBrowserJourneyOutbox,
} from '../apps/web/lib/journey-storage';
import { claimJourneyCommand, enqueueJourneyCommand } from '../packages/shared/src/journey-outbox';
const account = '00000000-0000-4000-8000-000000000001';
const other = '00000000-0000-4000-8000-000000000002';
const id = '00000000-0000-4000-8000-000000000003';
const lease = '00000000-0000-4000-8000-000000000004';
const response = {
  id,
  kind: 'trip',
  status: 'active',
  startedAt: '2026-09-08T12:00:00Z',
  completedAt: null,
};
beforeEach(() => vi.stubGlobal('indexedDB', new IDBFactory()));
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
async function pending(owner = account) {
  await updateBrowserJourneyOutbox(owner, (queue) =>
    claimJourneyCommand(
      enqueueJourneyCommand(queue, { journeyId: id, action: 'start', kind: 'trip' }, 0),
      0,
      lease,
    ),
  );
}
describe('browser IndexedDB journey partitions', () => {
  it('serializes concurrent writers without losing queued commands', async () => {
    await Promise.all(
      [id, other].map((journeyId) =>
        updateBrowserJourneyOutbox(account, (queue) =>
          enqueueJourneyCommand(queue, { journeyId, action: 'start', kind: 'trip' }, 0),
        ),
      ),
    );
    expect((await readBrowserJourneyPartition(account)).outbox.entries).toHaveLength(2);
    expect((await readBrowserJourneyPartition(other)).outbox.entries).toHaveLength(0);
  });
  it('persists the snapshot and acknowledgement through new connections', async () => {
    await pending();
    expect(await acknowledgeBrowserJourney(account, lease, response, 1)).toBe(true);
    const restored = await readBrowserJourneyPartition(account);
    expect(restored.outbox.entries).toHaveLength(0);
    expect(restored.snapshots.journeys[0]).toEqual({
      ...response,
      startedAt: '2026-09-08T12:00:00.000000Z',
    });
    expect(await acknowledgeBrowserJourney(account, lease, response, 2)).toBe(false);
  });
  it('preserves both parts after failed writes or invalid responses', async () => {
    await pending();
    const original = await readBrowserJourneyPartition(account);
    await expect(
      acknowledgeBrowserJourney(account, lease, { ...response, id: other }, 1),
    ).rejects.toThrow();
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(() => {
      throw new DOMException('Test quota', 'QuotaExceededError');
    });
    await expect(acknowledgeBrowserJourney(account, lease, response, 1)).rejects.toThrow();
    put.mockRestore();
    expect(await readBrowserJourneyPartition(account)).toEqual(original);
  });
  it('clears only the explicitly deleted partition and ignores late workers', async () => {
    await pending();
    await pending(other);
    await clearBrowserJourneyPartition(account);
    expect(await acknowledgeBrowserJourney(account, lease, response, 1)).toBe(false);
    expect((await readBrowserJourneyPartition(account)).snapshots.journeys).toHaveLength(0);
    expect((await readBrowserJourneyPartition(other)).outbox.entries).toHaveLength(1);
  });
  it('fails closed when browser storage is unavailable', async () => {
    vi.stubGlobal('indexedDB', undefined);
    await expect(readBrowserJourneyPartition(account)).rejects.toThrow();
  });
  it('rejects changing the account partition during an update', async () => {
    await pending();
    const original = await readBrowserJourneyPartition(account);
    await expect(
      updateBrowserJourneyOutbox(account, (queue) => ({ ...queue, accountId: other })),
    ).rejects.toThrow();
    expect(await readBrowserJourneyPartition(account)).toEqual(original);
  });
  it('preserves an unsupported stored version until explicit deletion', async () => {
    await readBrowserJourneyPartition(account);
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('routiqo-journeys-v1', 1);
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction('accounts', 'readwrite');
        tx.objectStore('accounts').put({ version: 999 }, account);
        tx.oncomplete = () => {
          db.close();
          resolve();
        };
        tx.onabort = () => {
          db.close();
          reject(tx.error);
        };
      };
    });
    await expect(readBrowserJourneyPartition(account)).rejects.toThrow();
    await expect(updateBrowserJourneyOutbox(account, (queue) => queue)).rejects.toThrow();
    await clearBrowserJourneyPartition(account);
    expect((await readBrowserJourneyPartition(account)).outbox.entries).toHaveLength(0);
  });
});
