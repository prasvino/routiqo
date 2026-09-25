import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  acknowledgeJourneyResult,
  clearJourneyPartition,
  clearMobileJourneyRoutes,
  initializeJourneyOutbox,
  queueMobileJourney,
  readMobileJourneyRoute,
  updateJourneyOutbox,
  type OutboxDatabase,
} from '../apps/mobile/src/storage/journey-outbox';
import { claimJourneyCommand } from '../packages/shared/src/journey-outbox';
import { buildJourneyRoute, type JourneyRoute } from '../packages/shared/src/journey-route';
import type { RouteCoordinate } from '../packages/shared/src/routing';

const owner = '00000000-0000-4000-8000-000000000001';
const other = '00000000-0000-4000-8000-000000000002';
const journeyId = '00000000-0000-4000-8000-000000000003';
const nextJourney = '00000000-0000-4000-8000-000000000006';
const lease = '00000000-0000-4000-8000-000000000004';
const folders: string[] = [];
const databases: DatabaseSync[] = [];
afterEach(() => {
  databases.splice(0).forEach((db) => {
    if (db.isOpen) db.close();
  });
  // Paths come only from this test's own mkdtemp, never project/user data.
  folders.splice(0).forEach((path) => rmSync(path, { recursive: true }));
});
function open(path: string) {
  const db = new DatabaseSync(path);
  databases.push(db);
  const adapter: OutboxDatabase = {
    execAsync: async (sql) => {
      db.exec(sql);
    },
    withExclusiveTransactionAsync: async (task) => {
      db.exec('BEGIN IMMEDIATE');
      try {
        // A narrow bridge executes the production adapter's SQL and transaction callback.
        // Expo overloads also allow array/named binding; this adapter only receives positional strings.
        await task({
          getFirstAsync: (async (sql: string, ...params: string[]) =>
            db.prepare(sql).get(...params) ?? null) as Parameters<
            Parameters<OutboxDatabase['withExclusiveTransactionAsync']>[0]
          >[0]['getFirstAsync'],
          runAsync: (async (sql: string, ...params: string[]) => {
            const result = db.prepare(sql).run(...params);
            return {
              changes: Number(result.changes),
              lastInsertRowId: Number(result.lastInsertRowid),
            };
          }) as Parameters<
            Parameters<OutboxDatabase['withExclusiveTransactionAsync']>[0]
          >[0]['runAsync'],
        });
        db.exec('COMMIT');
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
  };
  return { db, adapter };
}
async function setup() {
  const folder = mkdtempSync(join(tmpdir(), 'routiqo-route-test-'));
  folders.push(folder);
  const path = join(folder, 'queue.db');
  const opened = open(path);
  await initializeJourneyOutbox(opened.adapter);
  return { ...opened, path };
}

const routeFor = (id: string): JourneyRoute =>
  buildJourneyRoute({
    journeyId: id,
    mode: 'driving',
    originLabel: 'Kilambakkam',
    destinationLabel: 'Trichy',
    origin: [80.08, 12.87],
    destination: [78.7, 10.8],
    alternativeIndex: 0,
    calculatedAt: '2026-09-25T10:00:00Z',
    route: {
      distanceMetres: 300_000,
      durationSeconds: 18_000,
      geometry: [
        [80.08, 12.87],
        [79.5, 12.0],
        [78.7, 10.8],
      ] as RouteCoordinate[],
    },
  });
const start = (id = journeyId) => ({
  journeyId: id,
  action: 'start' as const,
  kind: 'trip' as const,
});
const complete = (id = journeyId) => ({ journeyId: id, action: 'complete' as const });

describe('device-only journey route record', () => {
  it('is written with the start command in one transaction and read back', async () => {
    const { adapter, db } = await setup();
    await queueMobileJourney(adapter, owner, start(), 0, routeFor(journeyId));
    expect(await readMobileJourneyRoute(adapter, owner)).toEqual(routeFor(journeyId));
    const outbox = db.prepare('SELECT payload FROM journey_outbox_v1').get() as { payload: string };
    // The outbox never carries route data.
    expect(outbox.payload).not.toContain('Kilambakkam');
    expect(outbox.payload).not.toContain('geometry');
  });

  it('is not written when the start fails, and a mismatched route is refused', async () => {
    const { adapter } = await setup();
    await expect(
      queueMobileJourney(adapter, owner, start(), 0, routeFor(nextJourney)),
    ).rejects.toThrow();
    await expect(
      queueMobileJourney(adapter, owner, complete(), 0, routeFor(journeyId)),
    ).rejects.toThrow();
    await queueMobileJourney(adapter, owner, start(), 0);
    // A second journey cannot start, so its route is rolled back with it.
    await expect(
      queueMobileJourney(adapter, owner, start(nextJourney), 0, routeFor(nextJourney)),
    ).rejects.toThrow();
    expect(await readMobileJourneyRoute(adapter, owner)).toBeNull();
  });

  it('is deleted in the same transaction as queuing completion', async () => {
    const { adapter, db } = await setup();
    await queueMobileJourney(adapter, owner, start(), 0, routeFor(journeyId));
    await queueMobileJourney(adapter, owner, complete(), 1);
    expect(db.prepare('SELECT COUNT(*) AS n FROM journey_route_v1').get()).toEqual({ n: 0 });
  });

  it('survives delivery of the start and is dropped once the journey is no longer current', async () => {
    const { adapter, db } = await setup();
    await queueMobileJourney(adapter, owner, start(), 0, routeFor(journeyId));
    await updateJourneyOutbox(adapter, owner, (state) => claimJourneyCommand(state, 0, lease));
    await acknowledgeJourneyResult(
      adapter,
      owner,
      lease,
      {
        id: journeyId,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-25T10:00:00Z',
        completedAt: null,
      },
      1,
    );
    expect(await readMobileJourneyRoute(adapter, owner)).not.toBeNull();
    // The server completed it elsewhere (another device): the stale record is removed on read.
    db.prepare('UPDATE journey_snapshots_v1 SET payload = ? WHERE account_id = ?').run(
      JSON.stringify({
        version: 1,
        accountId: owner,
        journeys: [
          {
            id: journeyId,
            kind: 'trip',
            status: 'completed',
            startedAt: '2026-09-25T10:00:00Z',
            completedAt: '2026-09-25T14:00:00Z',
          },
        ],
      }),
      owner,
    );
    expect(await readMobileJourneyRoute(adapter, owner)).toBeNull();
    expect(db.prepare('SELECT COUNT(*) AS n FROM journey_route_v1').get()).toEqual({ n: 0 });
  });

  it('drops a corrupted record instead of using it', async () => {
    const { adapter, db } = await setup();
    await queueMobileJourney(adapter, owner, start(), 0, routeFor(journeyId));
    db.prepare('UPDATE journey_route_v1 SET payload = ?').run('{"version":1}');
    expect(await readMobileJourneyRoute(adapter, owner)).toBeNull();
    expect(db.prepare('SELECT COUNT(*) AS n FROM journey_route_v1').get()).toEqual({ n: 0 });
  });

  it('is partitioned by account and cleared on sign-out, account change, clear and deletion', async () => {
    const { adapter, db } = await setup();
    await queueMobileJourney(adapter, owner, start(), 0, routeFor(journeyId));
    await queueMobileJourney(adapter, other, start(nextJourney), 0, routeFor(nextJourney));
    expect(await readMobileJourneyRoute(adapter, other)).toEqual(routeFor(nextJourney));
    await clearMobileJourneyRoutes(adapter, { except: owner });
    expect(await readMobileJourneyRoute(adapter, other)).toBeNull();
    expect(await readMobileJourneyRoute(adapter, owner)).not.toBeNull();
    await clearMobileJourneyRoutes(adapter, { only: owner });
    expect(await readMobileJourneyRoute(adapter, owner)).toBeNull();
    await queueMobileJourney(adapter, other, start(nextJourney), 0, routeFor(nextJourney));
    await clearMobileJourneyRoutes(adapter, 'all');
    expect(db.prepare('SELECT COUNT(*) AS n FROM journey_route_v1').get()).toEqual({ n: 0 });
    await queueMobileJourney(adapter, owner, start(), 0, routeFor(journeyId));
    await clearJourneyPartition(adapter, owner);
    expect(db.prepare('SELECT COUNT(*) AS n FROM journey_route_v1').get()).toEqual({ n: 0 });
  });
});
