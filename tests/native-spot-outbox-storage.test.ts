import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  clearJourneyPartition,
  initializeJourneyOutbox,
  type OutboxDatabase,
} from '../apps/mobile/src/storage/journey-outbox';
import {
  SpotOutboxUnavailable,
  clearSpotOutbox,
  initializeSpotOutboxStorage,
  readGhostMode,
  readSpotOutboxFor,
  setGhostMode,
  updateSpotOutbox,
} from '../apps/mobile/src/storage/spot-outbox';
import { enqueueSpotContribution } from '../packages/shared/src/spot-contributions';

const owner = '00000000-0000-4000-8000-000000000001';
const other = '00000000-0000-4000-8000-000000000002';
const journey = '00000000-0000-4000-8000-000000000003';
const spot = '00000000-0000-4000-8000-000000000004';
const key = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const NOW = Date.parse('2026-11-05T06:30:00Z');
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
  const folder = mkdtempSync(join(tmpdir(), 'routiqo-spot-outbox-test-'));
  folders.push(folder);
  const opened = open(join(folder, 'queue.db'));
  await initializeJourneyOutbox(opened.adapter);
  await initializeSpotOutboxStorage(opened.adapter);
  return opened;
}

const queuePost = (adapter: OutboxDatabase, account: string, n: number) =>
  updateSpotOutbox(adapter, account, (current) =>
    enqueueSpotContribution(
      current,
      {
        kind: 'post',
        clientKey: key(n),
        spotId: spot,
        journeyId: journey,
        capturedAt: new Date(NOW).toISOString(),
        type: 'traffic',
        text: `Post ${n}`,
      },
      NOW,
    ),
  );

describe('spot_outbox_v1 and Ghost Mode storage', () => {
  it('partitions queues by account and clears by scope', async () => {
    const { adapter } = await setup();
    await queuePost(adapter, owner, 10);
    await queuePost(adapter, owner, 11);
    await queuePost(adapter, other, 12);
    expect(
      (await readSpotOutboxFor(adapter, owner)).entries.map((entry) => entry.clientKey),
    ).toEqual([key(10), key(11)]);
    await clearSpotOutbox(adapter, { except: owner });
    expect((await readSpotOutboxFor(adapter, other)).entries).toEqual([]);
    expect((await readSpotOutboxFor(adapter, owner)).entries).toHaveLength(2);
    await clearSpotOutbox(adapter, { only: owner });
    expect((await readSpotOutboxFor(adapter, owner)).entries).toEqual([]);
    await queuePost(adapter, other, 13);
    await clearSpotOutbox(adapter, 'all');
    expect((await readSpotOutboxFor(adapter, other)).entries).toEqual([]);
  });

  it('turning Ghost Mode on clears every queue in the same transaction and refuses new items', async () => {
    const { adapter } = await setup();
    expect(await readGhostMode(adapter)).toBe(false);
    await queuePost(adapter, owner, 20);
    await queuePost(adapter, other, 21);
    await setGhostMode(adapter, true);
    expect(await readGhostMode(adapter)).toBe(true);
    expect((await readSpotOutboxFor(adapter, owner)).entries).toEqual([]);
    expect((await readSpotOutboxFor(adapter, other)).entries).toEqual([]);
    await expect(queuePost(adapter, owner, 22)).rejects.toBeInstanceOf(SpotOutboxUnavailable);
    await setGhostMode(adapter, false);
    await queuePost(adapter, owner, 23);
    expect((await readSpotOutboxFor(adapter, owner)).entries).toHaveLength(1);
  });

  it('account deletion removes the queue and refuses a deleted account', async () => {
    const { adapter } = await setup();
    await queuePost(adapter, owner, 30);
    await clearJourneyPartition(adapter, owner);
    expect((await readSpotOutboxFor(adapter, owner)).entries).toEqual([]);
    await expect(queuePost(adapter, owner, 31)).rejects.toBeInstanceOf(SpotOutboxUnavailable);
  });

  it('reads a corrupt or foreign row as empty and a failed change leaves the queue unchanged', async () => {
    const { db, adapter } = await setup();
    db.prepare('INSERT INTO spot_outbox_v1 (account_id, payload) VALUES (?, ?)').run(
      owner,
      '{not json',
    );
    expect((await readSpotOutboxFor(adapter, owner)).entries).toEqual([]);
    db.prepare('UPDATE spot_outbox_v1 SET payload = ? WHERE account_id = ?').run(
      JSON.stringify({ accountId: other, entries: [] }),
      owner,
    );
    expect((await readSpotOutboxFor(adapter, owner)).entries).toEqual([]);
    await queuePost(adapter, owner, 40);
    await expect(
      updateSpotOutbox(adapter, owner, () => {
        throw new Error('boom');
      }),
    ).rejects.toThrow('boom');
    expect((await readSpotOutboxFor(adapter, owner)).entries).toHaveLength(1);
  });
});
