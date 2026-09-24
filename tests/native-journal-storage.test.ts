import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  acknowledgeNativeJournalDraft,
  cacheNativeTripJournal,
  discardNativeJournalDraft,
  initializeNativeJournalStorage,
  listNativeStoredJournals,
  readNativeStoredJournal,
  saveNativeJournalDraft,
  type NativeJournalDraft,
} from '../apps/mobile/src/storage/journal-storage';
import {
  clearJourneyPartition,
  initializeJourneyOutbox,
  type OutboxDatabase,
} from '../apps/mobile/src/storage/journey-outbox';

const owner = '00000000-0000-4000-8000-000000000001';
const other = '00000000-0000-4000-8000-000000000002';
const journeyId = '00000000-0000-4000-8000-000000000003';
const mutation = '00000000-0000-4000-8000-000000000004';
const nextMutation = '00000000-0000-4000-8000-000000000005';
const folders: string[] = [];
const databases: DatabaseSync[] = [];
afterEach(() => {
  databases.splice(0).forEach((db) => {
    if (db.isOpen) db.close();
  });
  folders.splice(0).forEach((folder) => rmSync(folder, { recursive: true }));
});
function open(path: string) {
  const sqlite = new DatabaseSync(path);
  databases.push(sqlite);
  const db: OutboxDatabase = {
    execAsync: async (sql) => {
      sqlite.exec(sql);
    },
    withExclusiveTransactionAsync: async (task) => {
      sqlite.exec('BEGIN IMMEDIATE');
      try {
        await task({
          getFirstAsync: (async (sql: string, ...params: string[]) =>
            sqlite.prepare(sql).get(...params) ?? null) as Parameters<
            Parameters<OutboxDatabase['withExclusiveTransactionAsync']>[0]
          >[0]['getFirstAsync'],
          runAsync: (async (sql: string, ...params: string[]) => {
            const result = sqlite.prepare(sql).run(...params);
            return {
              changes: Number(result.changes),
              lastInsertRowId: Number(result.lastInsertRowid),
            };
          }) as Parameters<
            Parameters<OutboxDatabase['withExclusiveTransactionAsync']>[0]
          >[0]['runAsync'],
        });
        sqlite.exec('COMMIT');
      } catch (error) {
        sqlite.exec('ROLLBACK');
        throw error;
      }
    },
  };
  return { sqlite, db };
}
async function setup() {
  const folder = mkdtempSync(join(tmpdir(), 'routiqo-journal-test-'));
  folders.push(folder);
  const path = join(folder, 'journal.db');
  const connection = open(path);
  await initializeJourneyOutbox(connection.db);
  await initializeNativeJournalStorage(connection.db);
  return { ...connection, path };
}
function journal(id = journeyId, version = 0, title = '', notes = '') {
  return {
    journey: {
      id,
      kind: 'trip' as const,
      status: 'completed' as const,
      startedAt: '2026-09-08T12:00:00Z',
      completedAt: '2026-09-08T13:00:00Z',
    },
    annotation: { title, notes, version, updatedAt: version === 0 ? null : '2026-09-08T14:00:00Z' },
  };
}
function draft(
  id = journeyId,
  mutationId = mutation,
  title = 'Personal title',
): NativeJournalDraft {
  return { journeyId: id, mutationId, expectedVersion: 0, title, notes: 'Private notes\n' };
}
async function prepared(db: OutboxDatabase) {
  await cacheNativeTripJournal(db, owner, journal());
  return saveNativeJournalDraft(db, owner, draft(), null);
}
describe('native journal SQL on file-backed SQLite', () => {
  it('survives reopen, isolates accounts and preserves an exact retry identity', async () => {
    const { sqlite, db, path } = await setup();
    const saved = await prepared(db);
    expect(await saveNativeJournalDraft(db, owner, saved, mutation)).toEqual(saved);
    sqlite.close();
    const reopened = open(path);
    expect((await readNativeStoredJournal(reopened.db, owner, journeyId)).draft).toEqual(saved);
    expect(await listNativeStoredJournals(reopened.db, other)).toEqual([]);
    expect((await listNativeStoredJournals(reopened.db, owner))[0]?.draft).toEqual(saved);
  });
  it('uses mutation CAS and rejects identity reuse with changed text', async () => {
    const { db } = await setup();
    await prepared(db);
    await expect(
      saveNativeJournalDraft(db, owner, draft(journeyId, nextMutation), null),
    ).rejects.toThrow();
    await expect(
      saveNativeJournalDraft(db, owner, draft(journeyId, mutation, 'Changed'), mutation),
    ).rejects.toThrow();
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft?.title).toBe(
      'Personal title',
    );
  });
  it('keeps an unsent draft on its original base after caching a newer account version', async () => {
    const { db } = await setup();
    const saved = await prepared(db);
    const newer = await cacheNativeTripJournal(
      db,
      owner,
      journal(journeyId, 2, 'Account title', 'Account notes'),
    );
    const stored = await readNativeStoredJournal(db, owner, journeyId);
    expect(stored.journal).toEqual(newer);
    expect(stored.draft).toEqual(saved);
    expect(stored.draft?.expectedVersion).toBe(0);
    await expect(
      acknowledgeNativeJournalDraft(
        db,
        owner,
        journeyId,
        mutation,
        journal(journeyId, 1, saved.title, saved.notes),
      ),
    ).rejects.toThrow();
    expect(await readNativeStoredJournal(db, owner, journeyId)).toEqual(stored);
  });
  it('rejects version regression, divergent same-version content and changed lifecycle', async () => {
    const { db } = await setup();
    const saved = await prepared(db);
    const confirmed = await cacheNativeTripJournal(
      db,
      owner,
      journal(journeyId, 2, 'Account title', 'Account notes'),
    );
    const rejected = [
      journal(journeyId, 1, 'Older title', 'Older notes'),
      journal(journeyId, 2, 'Different title', 'Account notes'),
      { ...confirmed, annotation: { ...confirmed.annotation, updatedAt: '2026-09-08T14:01:00Z' } },
      {
        ...journal(journeyId, 3, 'Later title', 'Later notes'),
        journey: { ...confirmed.journey, startedAt: '2026-09-08T11:59:00Z' },
      },
      {
        ...journal(journeyId, 3, 'Later title', 'Later notes'),
        journey: { ...confirmed.journey, completedAt: '2026-09-08T13:01:00Z' },
      },
    ];
    for (const input of rejected) {
      await expect(cacheNativeTripJournal(db, owner, input)).rejects.toThrow();
      expect(await readNativeStoredJournal(db, owner, journeyId)).toEqual({
        journal: confirmed,
        draft: saved,
      });
    }
  });
  it('keeps replaced drafts on late acknowledgement and commits an exact acknowledgement', async () => {
    const { db } = await setup();
    await prepared(db);
    await saveNativeJournalDraft(db, owner, draft(journeyId, nextMutation, 'Newer'), mutation);
    expect(
      await acknowledgeNativeJournalDraft(
        db,
        owner,
        journeyId,
        mutation,
        journal(journeyId, 1, 'Personal title', 'Private notes\n'),
      ),
    ).toBe(false);
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft?.mutationId).toBe(
      nextMutation,
    );
    await expect(
      acknowledgeNativeJournalDraft(
        db,
        owner,
        journeyId,
        nextMutation,
        journal(journeyId, 1, 'Wrong', 'Private notes\n'),
      ),
    ).rejects.toThrow();
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft).not.toBeNull();
    expect(
      await acknowledgeNativeJournalDraft(
        db,
        owner,
        journeyId,
        nextMutation,
        journal(journeyId, 1, 'Newer', 'Private notes\n'),
      ),
    ).toBe(true);
    const stored = await readNativeStoredJournal(db, owner, journeyId);
    expect(stored.draft).toBeNull();
    expect(stored.journal?.annotation.version).toBe(1);
  });
  it('rolls back acknowledgement when SQLite rejects persistence', async () => {
    const { sqlite, db } = await setup();
    await prepared(db);
    sqlite.exec(
      "CREATE TRIGGER reject_ack BEFORE UPDATE ON journal_partitions_v1 BEGIN SELECT RAISE(ABORT, 'raw sqlite detail must stay private'); END;",
    );
    await expect(
      acknowledgeNativeJournalDraft(
        db,
        owner,
        journeyId,
        mutation,
        journal(journeyId, 1, 'Personal title', 'Private notes\n'),
      ),
    ).rejects.toMatchObject({
      code: 'unavailable',
      message: 'Journal storage is unavailable.',
    });
    sqlite.exec('DROP TRIGGER reject_ack');
    const stored = await readNativeStoredJournal(db, owner, journeyId);
    expect(stored.draft?.mutationId).toBe(mutation);
    expect(stored.journal?.annotation.version).toBe(0);
  });
  it('redacts database failure details during device draft saving', async () => {
    const { sqlite, db } = await setup();
    await cacheNativeTripJournal(db, owner, journal());
    sqlite.exec(
      "CREATE TRIGGER reject_save BEFORE UPDATE ON journal_partitions_v1 BEGIN SELECT RAISE(ABORT, 'raw sqlite detail must stay private'); END;",
    );
    await expect(saveNativeJournalDraft(db, owner, draft(), null)).rejects.toMatchObject({
      code: 'unavailable',
      message: 'Journal storage is unavailable.',
    });
    sqlite.exec('DROP TRIGGER reject_save');
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft).toBeNull();
  });
  it('retains corrupt rows and rejects all reads and writes', async () => {
    const { sqlite, db } = await setup();
    sqlite.prepare('INSERT INTO journal_partitions_v1 VALUES (?, ?)').run(owner, '{broken');
    await expect(readNativeStoredJournal(db, owner, journeyId)).rejects.toMatchObject({
      code: 'corrupt',
    });
    await expect(cacheNativeTripJournal(db, owner, journal())).rejects.toMatchObject({
      code: 'corrupt',
    });
    expect(
      sqlite.prepare('SELECT payload FROM journal_partitions_v1 WHERE account_id = ?').get(owner)
        ?.payload,
    ).toBe('{broken');
  });
  it('rejects unsupported persisted fields without silently rewriting the row', async () => {
    const { sqlite, db } = await setup();
    const payload = JSON.stringify({
      version: 1,
      accountId: owner,
      drafts: [],
      journals: [],
      unexpected: 'must not be erased',
    });
    sqlite.prepare('INSERT INTO journal_partitions_v1 VALUES (?, ?)').run(owner, payload);
    await expect(listNativeStoredJournals(db, owner)).rejects.toMatchObject({ code: 'corrupt' });
    expect(
      sqlite.prepare('SELECT payload FROM journal_partitions_v1 WHERE account_id = ?').get(owner)
        ?.payload,
    ).toBe(payload);
  });
  it('rejects trailing-newline and nil identities on input and in saved partitions', async () => {
    const { sqlite, db } = await setup();
    await expect(readNativeStoredJournal(db, `${owner}\n`, journeyId)).rejects.toThrow();
    expect(() => readNativeStoredJournal(db, owner, `${journeyId}\n`)).toThrow();
    await expect(
      readNativeStoredJournal(db, '00000000-0000-0000-0000-000000000000', journeyId),
    ).rejects.toThrow();
    await expect(
      cacheNativeTripJournal(db, owner, journal('00000000-0000-0000-0000-000000000000')),
    ).rejects.toThrow();
    await expect(cacheNativeTripJournal(db, owner, journal(`${journeyId}\n`))).rejects.toThrow();
    await cacheNativeTripJournal(db, owner, journal());
    expect(() =>
      saveNativeJournalDraft(db, owner, draft(journeyId, `${mutation}\n`), null),
    ).toThrow();
    const saved = sqlite
      .prepare('SELECT payload FROM journal_partitions_v1 WHERE account_id = ?')
      .get(owner)?.payload as string;
    sqlite
      .prepare('UPDATE journal_partitions_v1 SET payload = ? WHERE account_id = ?')
      .run(saved.replace(journeyId, `${journeyId}\\n`), owner);
    await expect(listNativeStoredJournals(db, owner)).rejects.toMatchObject({ code: 'corrupt' });
  });
  it('keeps protected snapshots and evicts only an oldest unprotected snapshot', async () => {
    const { db } = await setup();
    await prepared(db);
    for (let i = 10; i < 29; i++) {
      const id = `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`;
      await cacheNativeTripJournal(db, owner, journal(id));
    }
    const newcomer = '00000000-0000-4000-8000-000000000030';
    await cacheNativeTripJournal(db, owner, journal(newcomer));
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft).not.toBeNull();
    expect(await listNativeStoredJournals(db, owner)).toHaveLength(20);
    expect((await readNativeStoredJournal(db, owner, newcomer)).journal).not.toBeNull();
  });
  it('rejects a twenty-first draft and never evicts twenty protected snapshots', async () => {
    const { db } = await setup();
    for (let i = 10; i < 30; i++) {
      const id = `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`;
      const mutationId = `00000000-0000-4000-9000-${String(i).padStart(12, '0')}`;
      await cacheNativeTripJournal(db, owner, journal(id));
      await saveNativeJournalDraft(db, owner, draft(id, mutationId), null);
    }
    const extra = '00000000-0000-4000-8000-000000000030';
    await expect(cacheNativeTripJournal(db, owner, journal(extra))).rejects.toMatchObject({
      code: 'full',
    });
    expect(await listNativeStoredJournals(db, owner)).toHaveLength(20);
    expect(
      (await readNativeStoredJournal(db, owner, '00000000-0000-4000-8000-000000000010')).draft,
    ).not.toBeNull();
  });
  it('requires an exact reviewed confirmed version before discarding', async () => {
    const { db } = await setup();
    await prepared(db);
    const reviewed = await cacheNativeTripJournal(
      db,
      owner,
      journal(journeyId, 1, 'Account text', 'Account notes'),
    );
    await expect(
      discardNativeJournalDraft(db, owner, journeyId, nextMutation, reviewed),
    ).rejects.toMatchObject({ code: 'conflict' });
    await expect(
      discardNativeJournalDraft(db, owner, journeyId, mutation, journal()),
    ).rejects.toMatchObject({ code: 'conflict' });
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft).not.toBeNull();
    expect(await discardNativeJournalDraft(db, owner, journeyId, mutation, reviewed)).toEqual(
      reviewed,
    );
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft).toBeNull();
  });
  it('atomically removes journal data with account retirement and fences late work', async () => {
    const { sqlite, db, path } = await setup();
    await prepared(db);
    await cacheNativeTripJournal(db, other, journal());
    sqlite.exec(
      "CREATE TRIGGER reject_journal_delete BEFORE DELETE ON journal_partitions_v1 BEGIN SELECT RAISE(ABORT, 'test failure'); END;",
    );
    await expect(clearJourneyPartition(db, owner)).rejects.toThrow();
    expect(
      sqlite
        .prepare('SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?')
        .get(owner),
    ).toBeUndefined();
    expect((await readNativeStoredJournal(db, owner, journeyId)).draft).not.toBeNull();
    sqlite.exec('DROP TRIGGER reject_journal_delete');
    await clearJourneyPartition(db, owner);
    sqlite.close();
    const reopened = open(path);
    expect(
      reopened.sqlite
        .prepare('SELECT payload FROM journal_partitions_v1 WHERE account_id = ?')
        .get(owner),
    ).toBeUndefined();
    await expect(readNativeStoredJournal(reopened.db, owner, journeyId)).rejects.toMatchObject({
      code: 'retired',
    });
    await expect(cacheNativeTripJournal(reopened.db, owner, journal())).rejects.toMatchObject({
      code: 'retired',
    });
    expect(await listNativeStoredJournals(reopened.db, other)).toHaveLength(1);
  });
});
