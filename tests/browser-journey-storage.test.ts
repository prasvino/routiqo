import { IDBFactory, IDBObjectStore } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  acknowledgeBrowserJourney,
  clearBrowserJourneyPartition,
  readBrowserJourneyPartition,
  updateBrowserJourneyOutbox,
  queueBrowserJourneyAction,
  reconcileBrowserJourney,
  discardBrowserJourneyAction,
  mergeBrowserJourneyHistory,
  retireBrowserJourneyPartition,
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
  it('prevents late history and queue writes from resurrecting a deleted account', async () => {
    await mergeBrowserJourneyHistory(account, [response]);
    await mergeBrowserJourneyHistory(other, [response]);
    await retireBrowserJourneyPartition(account);
    await retireBrowserJourneyPartition(account);
    await expect(mergeBrowserJourneyHistory(account, [response])).rejects.toThrow('deleted');
    await expect(
      queueBrowserJourneyAction(account, { action: 'start', kind: 'trip', journeyId: id }, 0),
    ).rejects.toThrow('deleted');
    await expect(readBrowserJourneyPartition(account)).rejects.toThrow('deleted');
    expect((await readBrowserJourneyPartition(other)).snapshots.journeys).toHaveLength(1);
  });
  it('restores server history without clearing pending actions and rolls back invalid batches', async () => {
    await queueBrowserJourneyAction(account, { action: 'start', kind: 'trip', journeyId: id }, 0);
    const restored = await mergeBrowserJourneyHistory(account, [response]);
    expect(restored.outbox.entries).toHaveLength(1);
    expect(restored.snapshots.journeys).toHaveLength(1);
    await expect(
      mergeBrowserJourneyHistory(account, [
        { ...response, id: other },
        { ...response, kind: 'commute' },
      ]),
    ).rejects.toThrow();
    expect(await readBrowserJourneyPartition(account)).toEqual(restored);
  });
  it('does not regress a confirmed completion when an older history read reports it active', async () => {
    await mergeBrowserJourneyHistory(account, [response]);
    await queueBrowserJourneyAction(account, { action: 'complete', journeyId: id }, 0);
    const pending = (await readBrowserJourneyPartition(account)).outbox;
    await mergeBrowserJourneyHistory(account, [
      { ...response, status: 'completed', completedAt: '2026-09-08T13:00:00Z' },
    ]);

    const restored = await mergeBrowserJourneyHistory(account, [response]);

    expect(restored.outbox).toEqual(pending);
    expect(restored.snapshots.journeys[0]).toEqual({
      ...response,
      status: 'completed',
      startedAt: '2026-09-08T12:00:00.000000Z',
      completedAt: '2026-09-08T13:00:00.000000Z',
    });
  });
  it('rejects duplicate history identities and conflicting completed observations atomically', async () => {
    await mergeBrowserJourneyHistory(account, [
      { ...response, status: 'completed', completedAt: '2026-09-08T13:00:00Z' },
    ]);
    const before = await readBrowserJourneyPartition(account);

    await expect(mergeBrowserJourneyHistory(account, [response, response])).rejects.toThrow(
      'Duplicate journey history',
    );
    await expect(
      mergeBrowserJourneyHistory(account, [
        { ...response, status: 'completed', completedAt: '2026-09-08T14:00:00Z' },
      ]),
    ).rejects.toThrow('conflicts with the saved lifecycle');
    await expect(
      mergeBrowserJourneyHistory(account, [{ ...response, kind: 'commute' }]),
    ).rejects.toThrow('conflicts with the saved lifecycle');
    await expect(
      mergeBrowserJourneyHistory(account, [
        { ...response, startedAt: '2026-09-08T12:00:00.000001Z' },
      ]),
    ).rejects.toThrow('conflicts with the saved lifecycle');
    await expect(
      mergeBrowserJourneyHistory(account, [
        {
          ...response,
          id: other,
          status: 'completed',
          completedAt: '2026-09-08T13:00:00Z',
        },
        { ...response, startedAt: '2026-09-08T12:00:00.000001Z' },
      ]),
    ).rejects.toThrow('conflicts with the saved lifecycle');
    expect(await readBrowserJourneyPartition(account)).toEqual(before);
  });
  it('copies validated history before waiting for browser storage', async () => {
    const mutable = { ...response };
    const merging = mergeBrowserJourneyHistory(account, [mutable]);
    mutable.kind = 'commute';
    mutable.startedAt = '2026-09-09T12:00:00Z';

    const restored = await merging;

    expect(restored.snapshots.journeys[0]).toEqual({
      ...response,
      startedAt: '2026-09-08T12:00:00.000000Z',
    });
  });
  it('does not resurrect a pruned completed record from a later stale item in the same page', async () => {
    const history = Array.from({ length: 100 }, (_, index) => {
      const startedAt = new Date(Date.UTC(2026, 0, 1, 0, index)).toISOString();
      const completedAt = new Date(Date.UTC(2026, 0, 1, 1, index)).toISOString();
      return {
        id: `00000000-0000-4000-8000-${String(index + 100).padStart(12, '0')}`,
        kind: 'trip' as const,
        status: 'completed' as const,
        startedAt,
        completedAt,
      };
    });
    for (let index = 0; index < history.length; index += 20)
      await mergeBrowserJourneyHistory(account, history.slice(index, index + 20));
    const before = await readBrowserJourneyPartition(account);
    const oldest = history[0]!;
    const incoming = {
      id: '00000000-0000-4000-8000-000000000999',
      kind: 'trip' as const,
      status: 'completed' as const,
      startedAt: '2026-02-01T00:00:00Z',
      completedAt: '2026-02-01T01:00:00Z',
    };

    const restored = await mergeBrowserJourneyHistory(account, [
      incoming,
      { ...oldest, status: 'active', completedAt: null },
    ]);

    expect(restored.outbox).toEqual(before.outbox);
    expect(restored.snapshots.journeys).toHaveLength(100);
    expect(restored.snapshots.journeys.some((journey) => journey.id === oldest.id)).toBe(false);
    expect(restored.snapshots.journeys.some((journey) => journey.status === 'active')).toBe(false);
  });
  it('atomically reconciles only confirmed blocked work and preserves following commands', async () => {
    const command = { action: 'start' as const, kind: 'trip' as const, journeyId: id };
    await queueBrowserJourneyAction(account, command, 0);
    await queueBrowserJourneyAction(account, { action: 'complete', journeyId: id }, 1);
    await updateBrowserJourneyOutbox(account, (queue) => ({
      ...queue,
      entries: queue.entries.map((entry, index) =>
        index === 0 ? { ...entry, blocked: 'conflict' } : entry,
      ),
    }));
    await expect(
      reconcileBrowserJourney(account, command, { ...response, kind: 'commute' }),
    ).rejects.toThrow();
    expect((await readBrowserJourneyPartition(account)).outbox.entries).toHaveLength(2);
    expect(await reconcileBrowserJourney(account, command, response)).toBe(true);
    const saved = await readBrowserJourneyPartition(account);
    expect(saved.snapshots.journeys[0]?.id).toBe(id);
    expect(saved.outbox.entries[0]?.command.action).toBe('complete');
    expect(await reconcileBrowserJourney(account, command, response)).toBe(false);
  });
  it('explicitly discards a refused start with its dependent finish in one transaction', async () => {
    const command = { action: 'start' as const, kind: 'trip' as const, journeyId: id };
    await queueBrowserJourneyAction(account, command, 0);
    await queueBrowserJourneyAction(account, { action: 'complete', journeyId: id }, 1);
    await updateBrowserJourneyOutbox(account, (queue) => ({
      ...queue,
      entries: queue.entries.map((entry, index) =>
        index === 0 ? { ...entry, blocked: 'conflict' } : entry,
      ),
    }));
    const before = await readBrowserJourneyPartition(account);
    await expect(
      discardBrowserJourneyAction(account, command, { kind: 'trip', status: 'active' }),
    ).rejects.toThrow(/Reconcile/);
    await expect(
      discardBrowserJourneyAction(account, { action: 'complete', journeyId: id }, null),
    ).rejects.toThrow(/changed/);
    await expect(discardBrowserJourneyAction(other, command, null)).rejects.toThrow();
    expect(await readBrowserJourneyPartition(account)).toEqual(before);

    const after = await discardBrowserJourneyAction(account, command, null);
    expect(after.outbox.entries).toEqual([]);
    expect(after.snapshots).toEqual(before.snapshots);
    expect(await readBrowserJourneyPartition(account)).toEqual(after);
  });
  it('does not discard into a retired account partition', async () => {
    const command = { action: 'start' as const, kind: 'trip' as const, journeyId: id };
    await queueBrowserJourneyAction(account, command, 0);
    await updateBrowserJourneyOutbox(account, (queue) => ({
      ...queue,
      entries: queue.entries.map((entry) => ({ ...entry, blocked: 'rejected' as const })),
    }));
    await retireBrowserJourneyPartition(account);
    await expect(discardBrowserJourneyAction(account, command, null)).rejects.toThrow();
  });
  it('allows only one competing explicit start across tabs', async () => {
    const results = await Promise.allSettled(
      [id, other].map((journeyId) =>
        queueBrowserJourneyAction(account, { action: 'start', kind: 'trip', journeyId }, 0),
      ),
    );
    expect(results.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    expect((await readBrowserJourneyPartition(account)).outbox.entries).toHaveLength(1);
  });
  it('queues offline completion behind its start and rejects unknown completion', async () => {
    await expect(
      queueBrowserJourneyAction(account, { action: 'complete', journeyId: id }, 0),
    ).rejects.toThrow();
    await queueBrowserJourneyAction(account, { action: 'start', kind: 'trip', journeyId: id }, 0);
    await queueBrowserJourneyAction(account, { action: 'complete', journeyId: id }, 1);
    await queueBrowserJourneyAction(account, { action: 'complete', journeyId: id }, 2);
    expect(
      (await readBrowserJourneyPartition(account)).outbox.entries.map(
        (item) => item.command.action,
      ),
    ).toEqual(['start', 'complete']);
  });
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
  it('rolls back acknowledgement and preserves partitions after an asynchronous abort', async () => {
    const priorCompleted = {
      id: '00000000-0000-4000-8000-000000000099',
      kind: 'trip' as const,
      status: 'completed' as const,
      startedAt: '2026-09-01T10:00:00Z',
      completedAt: '2026-09-01T11:00:00Z',
    };
    await mergeBrowserJourneyHistory(account, [priorCompleted]);
    await pending(account);
    await pending(other);

    const preAbortA = await readBrowserJourneyPartition(account);
    const preAbortB = await readBrowserJourneyPartition(other);
    expect(preAbortA.outbox.entries).toHaveLength(1);
    expect(preAbortA.snapshots.journeys).toHaveLength(1);
    expect(preAbortB.outbox.entries).toHaveLength(1);

    const originalPut = IDBObjectStore.prototype.put;
    let putSuccessCount = 0;
    let abortTriggered = 0;
    const putSpy = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (
      this: IDBObjectStore,
      value: unknown,
      key?: IDBValidKey,
    ) {
      const request = originalPut.call(this, value, key);
      if (this.name === 'accounts' && key === account && abortTriggered === 0) {
        request.addEventListener('success', () => {
          putSuccessCount++;
          if (abortTriggered === 0) {
            abortTriggered++;
            request.transaction?.abort();
          }
        });
      }
      return request;
    });

    try {
      await expect(acknowledgeBrowserJourney(account, lease, response, 1)).rejects.toThrow(
        'Journey changes could not be saved.',
      );
      expect(putSuccessCount).toBe(1);
      expect(abortTriggered).toBe(1);
    } finally {
      putSpy.mockRestore();
    }

    const postAbortA = await readBrowserJourneyPartition(account);
    const postAbortB = await readBrowserJourneyPartition(other);
    expect(postAbortA).toEqual(preAbortA);
    expect(postAbortB).toEqual(preAbortB);

    expect(await acknowledgeBrowserJourney(account, lease, response, 1)).toBe(true);
    const postRetryA = await readBrowserJourneyPartition(account);
    expect(postRetryA.outbox.entries).toHaveLength(0);
    expect(postRetryA.snapshots.journeys).toHaveLength(2);
    expect(postRetryA.snapshots.journeys[0]).toEqual({
      ...response,
      startedAt: '2026-09-08T12:00:00.000000Z',
    });
    expect(postRetryA.snapshots.journeys[1]).toEqual(preAbortA.snapshots.journeys[0]);

    const postRetryB = await readBrowserJourneyPartition(other);
    expect(postRetryB).toEqual(preAbortB);

    expect(await acknowledgeBrowserJourney(account, lease, response, 2)).toBe(false);
    expect(await readBrowserJourneyPartition(account)).toEqual(postRetryA);
    expect(await readBrowserJourneyPartition(other)).toEqual(preAbortB);
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
