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
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
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

it('recovers from a transient network failure with persisted backoff and FIFO completion', async () => {
  const otherAccount = '00000000-0000-4000-8000-000000000099';
  const otherJourney = '00000000-0000-4000-8000-000000000098';
  await updateBrowserJourneyOutbox(accountId, (queue) =>
    enqueueJourneyCommand(queue, { action: 'complete', journeyId }, 0),
  );
  await updateBrowserJourneyOutbox(otherAccount, (queue) =>
    enqueueJourneyCommand(queue, { action: 'start', kind: 'trip', journeyId: otherJourney }, 0),
  );
  const initialOther = await readBrowserJourneyPartition(otherAccount);

  let currentTime = 1_725_800_000_000;
  const dateSpy = vi.spyOn(Date, 'now').mockImplementation(() => currentTime);

  type RecordedPost = {
    url: string;
    headers: Record<string, string>;
    body: string;
  };
  const journeyPosts: RecordedPost[] = [];
  let startAttempts = 0;

  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/session')) {
      return Response.json({ accountId });
    }
    if (url.endsWith('/csrf')) {
      return Response.json({ token: 'synthetic-csrf-value-for-test' });
    }
    if (url === '/api/v1/journeys') {
      const headers = (init?.headers as Record<string, string>) ?? {};
      const body = String(init?.body ?? '');
      journeyPosts.push({ url, headers, body });
      startAttempts++;
      if (startAttempts === 1) {
        throw new TypeError('Failed to fetch');
      }
      return Response.json({
        id: journeyId,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
      });
    }
    if (url === `/api/v1/journeys/${journeyId}/complete`) {
      const headers = (init?.headers as Record<string, string>) ?? {};
      const body = String(init?.body ?? '');
      journeyPosts.push({ url, headers, body });
      return Response.json({
        id: journeyId,
        kind: 'trip',
        status: 'completed',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: '2026-09-08T13:00:00Z',
      });
    }
    throw new Error(`Unexpected request to ${url}`);
  });
  vi.stubGlobal('fetch', fetcher);

  try {
    // 1. Initial attempt fails with transient error -> deferred
    expect(await dispatchBrowserJourneyOnce(accountId)).toBe('deferred');
    const deferredPartition = await readBrowserJourneyPartition(accountId);
    expect(deferredPartition.outbox.entries).toHaveLength(2);
    expect(deferredPartition.outbox.entries[0]?.command).toEqual({
      action: 'start',
      kind: 'trip',
      journeyId,
    });
    expect(deferredPartition.outbox.entries[1]?.command).toEqual({
      action: 'complete',
      journeyId,
    });
    const head = deferredPartition.outbox.entries[0]!;
    expect(head.attempts).toBe(1);
    expect(head.lease).toBeNull();
    expect(head.blocked).toBeNull();
    expect(head.nextAttemptAt).toBeGreaterThan(currentTime);
    expect(deferredPartition.snapshots.journeys).toHaveLength(0);
    expect(await readBrowserJourneyPartition(otherAccount)).toEqual(initialOther);
    expect(journeyPosts).toHaveLength(1);

    // 2. Before backoff expires -> idle, no new journey POST
    const retryTime = head.nextAttemptAt;
    currentTime = retryTime - 1;
    expect(await dispatchBrowserJourneyOnce(accountId)).toBe('idle');
    expect(await readBrowserJourneyPartition(accountId)).toEqual(deferredPartition);
    expect(journeyPosts).toHaveLength(1);

    // 3. At backoff expiry -> retry start succeeds -> acknowledged
    currentTime = retryTime;
    expect(await dispatchBrowserJourneyOnce(accountId)).toBe('acknowledged');
    const acknowledgedStart = await readBrowserJourneyPartition(accountId);
    expect(acknowledgedStart.outbox.entries).toHaveLength(1);
    expect(acknowledgedStart.outbox.entries[0]?.command).toEqual({
      action: 'complete',
      journeyId,
    });
    expect(acknowledgedStart.snapshots.journeys).toHaveLength(1);
    expect(acknowledgedStart.snapshots.journeys[0]).toEqual({
      id: journeyId,
      kind: 'trip',
      status: 'active',
      startedAt: '2026-09-08T12:00:00.000000Z',
      completedAt: null,
    });
    expect(journeyPosts).toHaveLength(2);
    expect(journeyPosts[0].url).toBe('/api/v1/journeys');
    expect(journeyPosts[1].url).toBe('/api/v1/journeys');
    expect(journeyPosts[0].headers['X-Routiqo-Account']).toBe(accountId);
    expect(journeyPosts[1].headers['X-Routiqo-Account']).toBe(accountId);
    expect(JSON.parse(journeyPosts[0].body)).toEqual({ id: journeyId, kind: 'trip' });
    expect(JSON.parse(journeyPosts[1].body)).toEqual({ id: journeyId, kind: 'trip' });

    // 4. Next dispatch executes queued completion -> acknowledged
    expect(await dispatchBrowserJourneyOnce(accountId)).toBe('acknowledged');
    const acknowledgedComplete = await readBrowserJourneyPartition(accountId);
    expect(acknowledgedComplete.outbox.entries).toHaveLength(0);
    expect(acknowledgedComplete.snapshots.journeys).toHaveLength(1);
    expect(acknowledgedComplete.snapshots.journeys[0]).toEqual({
      id: journeyId,
      kind: 'trip',
      status: 'completed',
      startedAt: '2026-09-08T12:00:00.000000Z',
      completedAt: '2026-09-08T13:00:00.000000Z',
    });
    expect(journeyPosts).toHaveLength(3);
    expect(journeyPosts[2].url).toBe(`/api/v1/journeys/${journeyId}/complete`);
    expect(journeyPosts[2].headers['X-Routiqo-Account']).toBe(accountId);
    expect(JSON.parse(journeyPosts[2].body)).toEqual({});

    // 5. Final dispatch when queue is empty -> idle
    expect(await dispatchBrowserJourneyOnce(accountId)).toBe('idle');
    expect(await readBrowserJourneyPartition(accountId)).toEqual(acknowledgedComplete);
    expect(await readBrowserJourneyPartition(otherAccount)).toEqual(initialOther);
    expect(journeyPosts).toHaveLength(3);
  } finally {
    dateSpy.mockRestore();
  }
});
