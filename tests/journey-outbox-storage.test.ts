import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  initializeJourneyOutbox,
  updateJourneyOutbox,
  acknowledgeJourneyResult,
  clearJourneyPartition,
  type OutboxDatabase,
} from '../apps/mobile/src/storage/journey-outbox';
import {
  claimJourneyCommand,
  enqueueJourneyCommand,
  settleJourneyCommand,
} from '../packages/shared/src/journey-outbox';

const owner = '00000000-0000-4000-8000-000000000001';
const other = '00000000-0000-4000-8000-000000000002';
const journeyId = '00000000-0000-4000-8000-000000000003';
const lease = '00000000-0000-4000-8000-000000000004';
const nextLease = '00000000-0000-4000-8000-000000000005';
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
  const folder = mkdtempSync(join(tmpdir(), 'routiqo-outbox-test-'));
  folders.push(folder);
  const path = join(folder, 'queue.db');
  const opened = open(path);
  await initializeJourneyOutbox(opened.adapter);
  return { ...opened, path };
}
describe('native outbox SQL on file-backed SQLite', () => {
  const result = {
    id: journeyId,
    kind: 'trip',
    status: 'active',
    startedAt: '2026-09-08T12:00:00Z',
    completedAt: null,
  };
  async function pending(adapter: OutboxDatabase, account = owner) {
    await updateJourneyOutbox(adapter, account, (state) =>
      claimJourneyCommand(
        enqueueJourneyCommand(state, { journeyId, action: 'start', kind: 'trip' }, 0),
        0,
        lease,
      ),
    );
  }
  it('commits the server snapshot and acknowledgement together across reopen', async () => {
    const { db, adapter, path } = await setup();
    await pending(adapter);
    expect(await acknowledgeJourneyResult(adapter, owner, lease, result, 1)).toBe(true);
    db.close();
    const reopened = open(path);
    expect(
      (await updateJourneyOutbox(reopened.adapter, owner, (state) => state)).entries,
    ).toHaveLength(0);
    const saved = JSON.parse(
      String(
        reopened.db
          .prepare('SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?')
          .get(owner)?.payload,
      ),
    );
    expect(saved.journeys[0]).toMatchObject({ id: journeyId, status: 'active' });
    expect(await acknowledgeJourneyResult(reopened.adapter, owner, lease, result, 2)).toBe(false);
  });
  it('rolls back both writes when queue persistence fails after snapshot insert', async () => {
    const { db, adapter } = await setup();
    await pending(adapter);
    db.exec(
      "CREATE TRIGGER reject_ack BEFORE UPDATE ON journey_outbox_v1 BEGIN SELECT RAISE(ABORT, 'test disk failure'); END;",
    );
    await expect(acknowledgeJourneyResult(adapter, owner, lease, result, 1)).rejects.toThrow();
    expect(
      db.prepare('SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?').get(owner),
    ).toBeUndefined();
    db.exec('DROP TRIGGER reject_ack');
    expect(
      (await updateJourneyOutbox(adapter, owner, (state) => state)).entries[0]?.lease?.token,
    ).toBe(lease);
  });
  it('ignores stale workers and validates responses before removing commands', async () => {
    const { db, adapter } = await setup();
    await pending(adapter);
    await updateJourneyOutbox(adapter, owner, (state) =>
      claimJourneyCommand(state, 30000, nextLease),
    );
    expect(await acknowledgeJourneyResult(adapter, owner, lease, result, 30001)).toBe(false);
    expect(await acknowledgeJourneyResult(adapter, other, nextLease, result, 30001)).toBe(false);
    await expect(
      acknowledgeJourneyResult(adapter, owner, nextLease, { ...result, id: other }, 30001),
    ).rejects.toThrow();
    expect(db.prepare('SELECT count(*) AS total FROM journey_snapshots_v1').get()?.total).toBe(0);
    expect((await updateJourneyOutbox(adapter, owner, (state) => state)).entries).toHaveLength(1);
  });
  it('removes only the selected account partition and rejects late acknowledgement', async () => {
    const { db, adapter } = await setup();
    await pending(adapter);
    await pending(adapter, other);
    await acknowledgeJourneyResult(adapter, owner, lease, result, 1);
    await acknowledgeJourneyResult(adapter, other, lease, result, 1);
    await clearJourneyPartition(adapter, owner);
    expect(
      db.prepare('SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?').get(owner),
    ).toBeUndefined();
    expect(
      db.prepare('SELECT payload FROM journey_outbox_v1 WHERE account_id = ?').get(owner),
    ).toBeUndefined();
    expect(
      db.prepare('SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?').get(other),
    ).toBeDefined();
    expect(await acknowledgeJourneyResult(adapter, owner, lease, result, 2)).toBe(false);
  });
  it('retains pending work across reopening and recovers a lost response with stable journey identity', async () => {
    const { db, adapter, path } = await setup();
    await updateJourneyOutbox(adapter, owner, (state) =>
      enqueueJourneyCommand(state, { journeyId, action: 'start', kind: 'trip' }, 0),
    );
    await updateJourneyOutbox(adapter, owner, (state) => claimJourneyCommand(state, 0, lease));
    db.close();
    const reopened = open(path);
    await initializeJourneyOutbox(reopened.adapter);
    const recovered = await updateJourneyOutbox(reopened.adapter, owner, (state) =>
      claimJourneyCommand(state, 30_000, nextLease),
    );
    expect(recovered.entries[0].command.journeyId).toBe(journeyId);
    expect(recovered.entries[0].attempts).toBe(2);
    const stale = await updateJourneyOutbox(reopened.adapter, owner, (state) =>
      settleJourneyCommand(state, lease, 'success', 30_001),
    );
    expect(stale.entries).toHaveLength(1);
    const done = await updateJourneyOutbox(reopened.adapter, owner, (state) =>
      settleJourneyCommand(state, nextLease, 'success', 30_002),
    );
    expect(done.entries).toHaveLength(0);
  });
  it('isolates accounts and rolls back failed writes or attempts to change partitions', async () => {
    const { db, adapter } = await setup();
    const saved = await updateJourneyOutbox(adapter, owner, (state) =>
      enqueueJourneyCommand(state, { journeyId, action: 'start', kind: 'trip' }, 0),
    );
    expect((await updateJourneyOutbox(adapter, other, (state) => state)).entries).toHaveLength(0);
    await expect(
      updateJourneyOutbox(adapter, owner, (state) => ({ ...state, accountId: other })),
    ).rejects.toThrow();
    db.exec(
      "CREATE TRIGGER reject_outbox_update BEFORE UPDATE ON journey_outbox_v1 BEGIN SELECT RAISE(ABORT, 'test disk failure'); END;",
    );
    await expect(
      updateJourneyOutbox(adapter, owner, (state) => claimJourneyCommand(state, 0, lease)),
    ).rejects.toThrow();
    db.exec('DROP TRIGGER reject_outbox_update');
    expect(await updateJourneyOutbox(adapter, owner, (state) => state)).toEqual(saved);
  });
  it('preserves unreadable pending work instead of replacing it', async () => {
    const { db, adapter } = await setup();
    db.prepare('INSERT INTO journey_outbox_v1 VALUES (?, ?)').run(owner, '{broken');
    await expect(updateJourneyOutbox(adapter, owner, (state) => state)).rejects.toThrow();
    expect(
      db.prepare('SELECT payload FROM journey_outbox_v1 WHERE account_id = ?').get(owner)?.payload,
    ).toBe('{broken');
  });
});
