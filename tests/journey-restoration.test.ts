import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { restoreRecentBrowserJourneyHistory } from '../apps/web/lib/journey-restoration';
import {
  mergeBrowserJourneyHistory,
  queueBrowserJourneyAction,
  readBrowserJourneyPartition,
  retireBrowserJourneyPartition,
} from '../apps/web/lib/journey-storage';
const account = '00000000-0000-4000-8000-000000000001';
const old = {
  id: '00000000-0000-4000-8000-000000000002',
  kind: 'trip',
  status: 'active',
  startedAt: '2026-09-08T12:00:00Z',
  completedAt: null,
};
const recent = Array.from({ length: 20 }, (_, index) => ({
  id: `00000000-0000-4000-8000-${String(index + 10).padStart(12, '0')}`,
  kind: 'trip',
  status: index === 0 ? 'active' : 'completed',
  startedAt: '2026-09-09T12:00:00Z',
  completedAt: index === 0 ? null : '2026-09-09T13:00:00Z',
}));
beforeEach(() => vi.stubGlobal('indexedDB', new IDBFactory()));
afterEach(() => vi.unstubAllGlobals());
it('restores a new active journey after confirming an older cached journey completed', async () => {
  await mergeBrowserJourneyHistory(account, [old]);
  await queueBrowserJourneyAction(account, { action: 'complete', journeyId: old.id }, 0);
  const pending = (await readBrowserJourneyPartition(account)).outbox;
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ journeys: recent }))
    .mockResolvedValueOnce(
      Response.json({ ...old, status: 'completed', completedAt: '2026-09-08T13:00:00Z' }),
    );
  vi.stubGlobal('fetch', fetcher);
  const restored = await restoreRecentBrowserJourneyHistory(account);
  expect(restored.recentCount).toBe(20);
  expect(restored.partition.snapshots.journeys).toHaveLength(21);
  expect(
    restored.partition.snapshots.journeys.filter((journey) => journey.status === 'active'),
  ).toMatchObject([{ id: recent[0]!.id }]);
  expect(restored.partition.outbox).toEqual(pending);
  expect(fetcher).toHaveBeenLastCalledWith(
    `/api/v1/journeys/${old.id}`,
    expect.objectContaining({ headers: { 'X-Routiqo-Account': account } }),
  );
});
it('does not infer completion from an absent or unavailable detail response', async () => {
  await mergeBrowserJourneyHistory(account, [old]);
  const before = await readBrowserJourneyPartition(account);
  for (const status of [404, 503]) {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ journeys: recent }))
        .mockResolvedValueOnce(new Response(null, { status })),
    );
    await expect(restoreRecentBrowserJourneyHistory(account)).rejects.toThrow();
    expect(await readBrowserJourneyPartition(account)).toEqual(before);
  }
});
it('avoids a duplicate detail lookup when the active record is already on the recent page', async () => {
  await mergeBrowserJourneyHistory(account, [old]);
  const fetcher = vi.fn(async () => Response.json({ journeys: [old] }));
  vi.stubGlobal('fetch', fetcher);
  expect((await restoreRecentBrowserJourneyHistory(account)).recentCount).toBe(1);
  expect(fetcher).toHaveBeenCalledOnce();
});
it('rejects restoration if account deletion happens during the detail lookup', async () => {
  await mergeBrowserJourneyHistory(account, [old]);
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(Response.json({ journeys: recent }))
      .mockImplementationOnce(async () => {
        await retireBrowserJourneyPartition(account);
        return Response.json({ ...old, status: 'completed', completedAt: '2026-09-08T13:00:00Z' });
      }),
  );
  await expect(restoreRecentBrowserJourneyHistory(account)).rejects.toThrow('deleted');
  await expect(readBrowserJourneyPartition(account)).rejects.toThrow('deleted');
});
