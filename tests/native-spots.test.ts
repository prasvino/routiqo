import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { OutboxDatabase } from '../apps/mobile/src/storage/journey-outbox';
import {
  clearCachedSpotCatalog,
  initializeSpotCatalogStorage,
  readCachedSpotCatalog,
  replaceCachedSpotCatalog,
} from '../apps/mobile/src/storage/spot-catalog';
import { createSpotCatalogStore } from '../apps/mobile/src/features/spots/spot-catalog-store';
import {
  ACTIVITY_INTERVAL_MS,
  DETAIL_INTERVAL_MS,
  MAX_BACKOFF_MS,
  createSpotActivityController,
  type SpotActivityState,
} from '../apps/mobile/src/features/spots/spot-activity-controller';
import {
  NativeSpotsError,
  fetchNativeSpotActivity,
  type SpotCatalogFetch,
} from '../apps/mobile/src/features/spots/native-spots';
import { NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import { readSpotCatalog, type SpotActivity } from '../packages/shared/src/spots';

const version = '00000000-0000-4000-8000-0000000000aa';
const newer = '00000000-0000-4000-8000-0000000000bb';
const etag = (v: string) => `"${v}"`;
const account = '00000000-0000-4000-8000-000000000001';
const spotId = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const wire = (v: string, name = 'Toll') => ({
  version: v,
  corridors: [{ id: 'gst-trunk', name: 'GST Road' }],
  spots: [
    {
      id: spotId(1),
      name,
      nameTa: 'சுங்கம்',
      kind: 'toll',
      longitude: 80,
      latitude: 12.1,
      district: 'chengalpattu',
      corridors: ['gst-trunk'],
      categories: ['traffic'],
    },
  ],
});

const folders: string[] = [];
const databases: DatabaseSync[] = [];
afterEach(() => {
  databases.splice(0).forEach((db) => db.isOpen && db.close());
  // Paths come only from this test's own mkdtemp.
  folders.splice(0).forEach((path) => rmSync(path, { recursive: true }));
  vi.useRealTimers();
});
async function database() {
  const folder = mkdtempSync(join(tmpdir(), 'routiqo-spots-test-'));
  folders.push(folder);
  const db = new DatabaseSync(join(folder, 'spots.db'));
  databases.push(db);
  type Tx = Parameters<Parameters<OutboxDatabase['withExclusiveTransactionAsync']>[0]>[0];
  const adapter: OutboxDatabase = {
    execAsync: async (sql) => {
      db.exec(sql);
    },
    withExclusiveTransactionAsync: async (task) => {
      db.exec('BEGIN IMMEDIATE');
      try {
        await task({
          getFirstAsync: (async (sql: string, ...params: string[]) =>
            db.prepare(sql).get(...params) ?? null) as Tx['getFirstAsync'],
          runAsync: (async (sql: string, ...params: string[]) => {
            const result = db.prepare(sql).run(...params);
            return {
              changes: Number(result.changes),
              lastInsertRowId: Number(result.lastInsertRowid),
            };
          }) as Tx['runAsync'],
        });
        db.exec('COMMIT');
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
  };
  await initializeSpotCatalogStorage(adapter);
  return { db, adapter };
}

describe('spot_catalog_v1 storage', () => {
  it('replaces, re-validates on read and clears the single catalog row', async () => {
    const { db, adapter } = await database();
    expect(await readCachedSpotCatalog(adapter)).toBeNull();
    await replaceCachedSpotCatalog(
      adapter,
      etag(version),
      wire(version),
      '2026-11-01T00:00:00.000Z',
    );
    await replaceCachedSpotCatalog(
      adapter,
      etag(newer),
      wire(newer, 'New Toll'),
      '2026-11-02T00:00:00.000Z',
    );
    const cached = await readCachedSpotCatalog(adapter);
    expect(cached?.catalog.version).toBe(newer);
    expect(cached?.catalog.spots[0]?.name).toBe('New Toll');
    expect(cached?.fetchedAt).toBe('2026-11-02T00:00:00.000Z');
    expect(db.prepare('SELECT COUNT(*) AS n FROM spot_catalog_v1').get()).toEqual({ n: 1 });
    await clearCachedSpotCatalog(adapter);
    expect(await readCachedSpotCatalog(adapter)).toBeNull();
  });

  it('treats a corrupt or mismatched row as no catalog', async () => {
    const { db, adapter } = await database();
    db.prepare(
      'INSERT INTO spot_catalog_v1 (id, etag, payload, fetched_at) VALUES (1, ?, ?, ?)',
    ).run(etag(version), '{not json', 'x');
    expect(await readCachedSpotCatalog(adapter)).toBeNull();
    await replaceCachedSpotCatalog(adapter, etag(newer), wire(version), 'x');
    expect(await readCachedSpotCatalog(adapter)).toBeNull();
  });
});

describe('Spot catalog store', () => {
  function harness(cached: string | null) {
    const saved: Array<{ etag: string; payload: unknown }> = [];
    let resolveFetch!: (value: SpotCatalogFetch) => void;
    let rejectFetch!: (error: unknown) => void;
    const fetch = vi.fn(
      (_ifNoneMatch: string | null, _signal: AbortSignal) =>
        new Promise<SpotCatalogFetch>((resolve, reject) => {
          resolveFetch = resolve;
          rejectFetch = reject;
        }),
    );
    const store = createSpotCatalogStore({
      load: async () =>
        cached
          ? {
              catalog: readSpotCatalog(wire(cached), etag(cached)),
              etag: etag(cached),
              fetchedAt: 'x',
            }
          : null,
      save: async (tag, payload) => {
        saved.push({ etag: tag, payload });
      },
      clear: async () => undefined,
      fetch,
      now: () => Date.parse('2026-11-05T06:30:00Z'),
    });
    return {
      store,
      fetch,
      saved,
      resolve: (v: SpotCatalogFetch) => resolveFetch(v),
      reject: (e: unknown) => rejectFetch(e),
    };
  }
  const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

  it('downloads the first catalog and sends no If-None-Match without a cached copy', async () => {
    const { store, fetch, saved, resolve } = harness(null);
    const done = store.refresh();
    await flush();
    expect(fetch).toHaveBeenCalledWith(null, expect.any(AbortSignal));
    resolve({
      status: 'catalog',
      etag: etag(version),
      payload: wire(version),
      catalog: readSpotCatalog(wire(version), etag(version)),
    });
    await done;
    expect(store.getState()).toMatchObject({ loaded: true, refreshing: false, failure: null });
    expect(store.version()).toBe(version);
    expect(saved).toEqual([{ etag: etag(version), payload: wire(version) }]);
  });

  it('revalidates by ETag, keeps the cached copy on 304 and on failure, one request in flight', async () => {
    const { store, fetch, saved, resolve, reject } = harness(version);
    const first = store.refresh();
    const second = store.refresh();
    expect(second).toBe(first);
    await flush();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(etag(version), expect.any(AbortSignal));
    resolve({ status: 'not-modified' });
    await first;
    expect(store.version()).toBe(version);
    const failing = store.refresh();
    await flush();
    reject(new NativeSpotsError('invalid'));
    await failing;
    expect(store.getState()).toMatchObject({ failure: 'invalid' });
    expect(store.version()).toBe(version);
    expect(saved).toEqual([]);
  });

  it('finishes loading with no catalog when the SQLite read fails', async () => {
    const store = createSpotCatalogStore({
      load: () => Promise.reject(new Error('disk')),
      save: async () => undefined,
      clear: async () => undefined,
      fetch: () => new Promise<SpotCatalogFetch>(() => undefined),
      now: () => 0,
    });
    await store.load();
    expect(store.getState()).toMatchObject({ loaded: true, catalog: null });
  });

  it('forgets the catalog on clear and ignores a refresh that finishes afterwards', async () => {
    const { store, resolve, saved } = harness(version);
    const pending = store.refresh();
    await flush();
    await store.clear();
    resolve({
      status: 'catalog',
      etag: etag(newer),
      payload: wire(newer),
      catalog: readSpotCatalog(wire(newer), etag(newer)),
    });
    await pending;
    expect(store.getState().catalog).toBeNull();
    expect(saved).toEqual([]);
  });
});

describe('Spot activity controller', () => {
  const activity = (catalogVersion = version): SpotActivity => ({
    serverTime: '2026-11-05T06:30:00Z',
    catalogVersion,
    spots: [{ id: spotId(1), state: 'quiet', alertIds: [] }],
  });
  function harness(options: { eligible?: boolean } = {}) {
    let now = 0;
    let eligible = options.eligible ?? true;
    const timers: Array<{ at: number; run: () => void; cancelled: boolean }> = [];
    const calls: Array<{
      ids: readonly string[];
      signal: AbortSignal;
      resolve: (a: SpotActivity) => void;
      reject: (e: unknown) => void;
    }> = [];
    const states: SpotActivityState[] = [];
    const versions: string[] = [];
    const controller = createSpotActivityController(
      {
        eligible: () => eligible,
        fetch: (ids, signal) =>
          new Promise<SpotActivity>((resolve, reject) =>
            calls.push({ ids, signal, resolve, reject }),
          ),
        now: () => now,
        schedule: (run, delay) => {
          const timer = { at: now + delay, run, cancelled: false };
          timers.push(timer);
          return () => {
            timer.cancelled = true;
          };
        },
        onCatalogVersion: (v) => versions.push(v),
      },
      (state) => states.push(state),
    );
    const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
    return {
      controller,
      calls,
      states,
      versions,
      setEligible(value: boolean) {
        eligible = value;
        controller.environmentChanged();
      },
      /** Advances the fake clock, firing due timers in order. */
      async advance(ms: number) {
        const target = now + ms;
        for (;;) {
          const next = timers
            .filter((timer) => !timer.cancelled && timer.at <= target)
            .sort((a, b) => a.at - b.at)[0];
          if (!next) break;
          next.cancelled = true;
          now = next.at;
          next.run();
          await flush();
        }
        now = target;
      },
      flush,
      pendingTimers: () =>
        timers.filter((timer) => !timer.cancelled).map((timer) => timer.at - now),
    };
  }

  it('requests immediately, then every 60 s, or 20 s while a detail is open', async () => {
    const h = harness();
    h.controller.setSpotIds([spotId(2), spotId(1)]);
    await h.advance(0);
    expect(h.calls).toHaveLength(1);
    expect(h.calls[0]?.ids).toEqual([spotId(1), spotId(2)]);
    h.calls[0]?.resolve(activity());
    await h.flush();
    expect(h.controller.getState()).toMatchObject({ status: 'ready', receivedAt: 0 });
    await h.advance(ACTIVITY_INTERVAL_MS - 1);
    expect(h.calls).toHaveLength(1);
    await h.advance(1);
    expect(h.calls).toHaveLength(2);
    h.calls[1]?.resolve(activity());
    await h.flush();
    h.controller.setDetailOpen(true);
    await h.advance(DETAIL_INTERVAL_MS);
    expect(h.calls).toHaveLength(3);
  });

  it('refreshes at once when the Spots ahead no longer overlap the last request (first fix)', async () => {
    const h = harness();
    h.controller.setSpotIds([spotId(1), spotId(2)]);
    await h.advance(0);
    h.calls[0]?.resolve(activity());
    await h.flush();
    h.controller.setSpotIds([spotId(2), spotId(3)]); // overlapping: waits for the interval
    await h.advance(1_000);
    expect(h.calls).toHaveLength(1);
    h.controller.setSpotIds([spotId(8), spotId(9)]); // disjoint: due now
    await h.advance(0);
    expect(h.calls).toHaveLength(2);
    expect(h.calls[1]?.ids).toEqual([spotId(8), spotId(9)]);
  });

  it('keeps one request in flight and never overlaps', async () => {
    const h = harness();
    h.controller.setSpotIds([spotId(1)]);
    await h.advance(0);
    h.controller.setDetailOpen(true);
    h.controller.environmentChanged();
    await h.advance(5 * ACTIVITY_INTERVAL_MS);
    expect(h.calls).toHaveLength(1);
  });

  it('cancels on becoming ineligible, drops the late result, and refreshes promptly when back', async () => {
    const h = harness();
    h.controller.setSpotIds([spotId(1)]);
    await h.advance(0);
    h.setEligible(false);
    expect(h.calls[0]?.signal.aborted).toBe(true);
    h.calls[0]?.resolve(activity());
    await h.flush();
    expect(h.controller.getState().activity).toBeNull();
    await h.advance(10 * ACTIVITY_INTERVAL_MS);
    expect(h.calls).toHaveLength(1);
    h.setEligible(true);
    await h.advance(0);
    expect(h.calls).toHaveLength(2);
  });

  it('sends nothing without Spots ahead or while ineligible (e.g. journey not confirmed)', async () => {
    const h = harness({ eligible: false });
    h.controller.setSpotIds([spotId(1)]);
    await h.advance(ACTIVITY_INTERVAL_MS);
    const empty = harness();
    empty.controller.setSpotIds([]);
    await empty.advance(ACTIVITY_INTERVAL_MS);
    expect(h.calls).toHaveLength(0);
    expect(empty.calls).toHaveLength(0);
  });

  it('backs off exponentially on 429 and 503 up to 5 minutes, then resets on success', async () => {
    const h = harness();
    h.controller.setSpotIds([spotId(1)]);
    await h.advance(0);
    const expected = [120_000, 240_000, MAX_BACKOFF_MS, MAX_BACKOFF_MS];
    for (const [index, wait] of expected.entries()) {
      h.calls[index]?.reject(new NativeSpotsError(index % 2 ? 'unavailable' : 'rate-limited', 429));
      await h.flush();
      expect(h.pendingTimers()).toEqual([wait]);
      await h.advance(wait);
    }
    expect(h.controller.getState().status).toBe('unavailable');
    h.calls[4]?.resolve(activity());
    await h.flush();
    expect(h.pendingTimers()).toEqual([ACTIVITY_INTERVAL_MS]);
  });

  it('keeps the last good activity on failure and reports 409 without backoff', async () => {
    const h = harness();
    h.controller.setSpotIds([spotId(1)]);
    await h.advance(0);
    h.calls[0]?.resolve(activity());
    await h.flush();
    await h.advance(ACTIVITY_INTERVAL_MS);
    h.calls[1]?.reject(new NativeSpotsError('no-journey', 409));
    await h.flush();
    expect(h.controller.getState()).toMatchObject({ status: 'no-journey', receivedAt: 0 });
    expect(h.controller.getState().activity).not.toBeNull();
    expect(h.pendingTimers()).toEqual([ACTIVITY_INTERVAL_MS]);
  });

  it('reports a newer catalog version from activity and stops on dispose', async () => {
    const h = harness();
    h.controller.setSpotIds([spotId(1)]);
    await h.advance(0);
    h.calls[0]?.resolve(activity(newer));
    await h.flush();
    expect(h.versions).toEqual([newer]);
    h.controller.dispose();
    await h.advance(10 * ACTIVITY_INTERVAL_MS);
    expect(h.calls).toHaveLength(1);
  });
});

describe('Spot activity request', () => {
  function identity(response: unknown) {
    const verifiedRequest = vi.fn(async () => {
      if (response instanceof Error) throw response;
      return response;
    });
    return {
      verifiedRequest,
      identity: {
        activeAccount: () => account,
        revision: () => 1,
        verifiedRequest,
      } as unknown as Parameters<typeof fetchNativeSpotActivity>[0],
    };
  }

  it('sends only sorted Spot IDs in the body', async () => {
    const { identity: id, verifiedRequest } = identity({
      serverTime: '2026-11-05T06:30:00Z',
      catalogVersion: version,
      spots: [],
      alerts: [],
    });
    await fetchNativeSpotActivity(id, account, [spotId(2), spotId(1)]);
    expect(verifiedRequest).toHaveBeenCalledWith('/api/v1/native/spots/activity', 'POST', {
      accountId: account,
      body: { spotIds: [spotId(1), spotId(2)] },
      signal: undefined,
    });
  });

  it('maps statuses and rejects invalid ID lists before sending', async () => {
    for (const [status, code] of [
      [401, 'session'],
      [409, 'no-journey'],
      [429, 'rate-limited'],
      [503, 'unavailable'],
      [400, 'invalid'],
    ] as const) {
      const { identity: id } = identity(new NativeHttpStatus(status));
      await expect(fetchNativeSpotActivity(id, account, [spotId(1)])).rejects.toMatchObject({
        code,
      });
    }
    const { identity: id, verifiedRequest } = identity({});
    await expect(fetchNativeSpotActivity(id, account, [])).rejects.toMatchObject({
      code: 'invalid',
    });
    await expect(
      fetchNativeSpotActivity(id, account, [spotId(1), spotId(1)]),
    ).rejects.toMatchObject({ code: 'invalid' });
    expect(verifiedRequest).not.toHaveBeenCalled();
  });
});

describe('privacy boundaries (source scan)', () => {
  const read = (path: string) => readFileSync(path, 'utf8');
  it('keeps matching free of storage and transport, and requests free of route or position', () => {
    const shared = read('packages/shared/src/spots.ts');
    expect(shared).not.toMatch(/from '[^']*(storage|transport|sqlite|netinfo|analytics)/i);
    const request = read('apps/mobile/src/features/spots/native-spots.ts');
    expect(request).not.toMatch(/geometry|coordinate|origin|destination|journeyRoute|location/i);
    expect(request).not.toMatch(/console\./);
  });
});
