import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { enqueueJourneyCommand } from '../packages/shared/src/journey-outbox';
import {
  dispatchBrowserJourneyOnce,
  restoreBrowserJourneyAuthentication,
} from '../apps/web/lib/journey-dispatch';
import {
  readBrowserJourneyPartition,
  updateBrowserJourneyOutbox,
} from '../apps/web/lib/journey-storage';
const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
beforeEach(async () => {
  vi.stubGlobal('indexedDB', new IDBFactory());
  await updateBrowserJourneyOutbox(accountId, (queue) =>
    enqueueJourneyCommand(queue, { action: 'start', kind: 'trip', journeyId }, 0),
  );
});
afterEach(() => vi.unstubAllGlobals());
it('restores only authentication blocks for the verified account without sending', async () => {
  await updateBrowserJourneyOutbox(accountId, (queue) => ({
    ...queue,
    entries: queue.entries.map((entry) => ({ ...entry, blocked: 'authentication' })),
  }));
  const fetcher = vi.fn(async () => Response.json({ accountId: journeyId }));
  vi.stubGlobal('fetch', fetcher);
  expect(await restoreBrowserJourneyAuthentication(accountId)).toBe(false);
  expect((await readBrowserJourneyPartition(accountId)).outbox.entries[0]?.blocked).toBe(
    'authentication',
  );
  fetcher.mockImplementation(async () => Response.json({ accountId }));
  expect(await restoreBrowserJourneyAuthentication(accountId)).toBe(true);
  expect((await readBrowserJourneyPartition(accountId)).outbox.entries[0]?.blocked).toBeNull();
  await updateBrowserJourneyOutbox(accountId, (queue) => ({
    ...queue,
    entries: queue.entries.map((entry) => ({ ...entry, blocked: 'conflict' })),
  }));
  await restoreBrowserJourneyAuthentication(accountId);
  expect((await readBrowserJourneyPartition(accountId)).outbox.entries[0]?.blocked).toBe(
    'conflict',
  );
  expect(fetcher).toHaveBeenCalledTimes(3);
});
it('preserves queued work when session verification is unavailable', async () => {
  const before = await readBrowserJourneyPartition(accountId);
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('offline');
    }),
  );
  await expect(restoreBrowserJourneyAuthentication(accountId)).rejects.toThrow();
  expect(await readBrowserJourneyPartition(accountId)).toEqual(before);
});
it('serializes competing dispatchers and atomically saves the acknowledged result', async () => {
  const fetcher = vi.fn(async (url: string) => {
    if (url.endsWith('/session')) return Response.json({ accountId });
    if (url.endsWith('/csrf')) return Response.json({ token: 'synthetic-csrf-value-for-test' });
    return Response.json({
      id: journeyId,
      kind: 'trip',
      status: 'active',
      startedAt: '2026-09-08T12:00:00Z',
      completedAt: null,
    });
  });
  vi.stubGlobal('fetch', fetcher);
  const results = await Promise.all([
    dispatchBrowserJourneyOnce(accountId),
    dispatchBrowserJourneyOnce(accountId),
  ]);
  expect(results.sort()).toEqual(['acknowledged', 'idle']);
  expect(fetcher.mock.calls.filter(([url]) => url === '/api/v1/journeys')).toHaveLength(1);
  const saved = await readBrowserJourneyPartition(accountId);
  expect(saved.outbox.entries).toHaveLength(0);
  expect(saved.snapshots.journeys[0]?.id).toBe(journeyId);
});
it('does not claim pending work when the verified session belongs to another account', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => Response.json({ accountId: journeyId })),
  );
  expect(await dispatchBrowserJourneyOnce(accountId)).toBe('idle');
  expect((await readBrowserJourneyPartition(accountId)).outbox.entries[0]?.attempts).toBe(0);
});
it('persists authentication blocks without discarding the command', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.endsWith('/session')) return Response.json({ accountId });
      if (url.endsWith('/csrf')) return Response.json({ token: 'synthetic-csrf-value-for-test' });
      return new Response(null, { status: 401 });
    }),
  );
  expect(await dispatchBrowserJourneyOnce(accountId)).toBe('blocked');
  const head = (await readBrowserJourneyPartition(accountId)).outbox.entries[0];
  expect(head?.blocked).toBe('authentication');
  expect(head?.lease).toBeNull();
});
