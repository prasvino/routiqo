import type { SQLiteDatabase } from 'expo-sqlite';
import {
  readJourneyOutbox,
  readJourneySnapshots,
  recordJourneyResult,
  settleJourneyCommand,
  type JourneyOutbox,
} from '@routiqo/shared';

// Structural subset also permits verification of these exact queries against file-backed SQLite.
export interface OutboxDatabase {
  execAsync: SQLiteDatabase['execAsync'];
  withExclusiveTransactionAsync: (
    task: (tx: Pick<SQLiteDatabase, 'getFirstAsync' | 'runAsync'>) => Promise<void>,
  ) => Promise<void>;
}
export async function initializeJourneyOutbox(db: Pick<OutboxDatabase, 'execAsync'>) {
  await db.execAsync(`CREATE TABLE IF NOT EXISTS journey_outbox_v1 (
    account_id TEXT PRIMARY KEY NOT NULL,
    payload TEXT NOT NULL
  );`);
  await db.execAsync(`CREATE TABLE IF NOT EXISTS journey_snapshots_v1 (
    account_id TEXT PRIMARY KEY NOT NULL,
    payload TEXT NOT NULL
  );`);
  await db.execAsync(`CREATE TABLE IF NOT EXISTS journey_retired_accounts_v1 (
    account_id TEXT PRIMARY KEY NOT NULL
  );`);
}

/** No network inside change. Return only after SQLite commits the new queue. */
export async function updateJourneyOutbox(
  db: OutboxDatabase,
  accountId: string,
  change: (current: JourneyOutbox) => JourneyOutbox,
): Promise<JourneyOutbox> {
  readJourneyOutbox(null, accountId); // Validate before opening a transaction.
  let committed: JourneyOutbox | undefined;
  await db.withExclusiveTransactionAsync(async (tx) => {
    const retired = await tx.getFirstAsync<{ account_id: string }>(
      'SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?',
      accountId,
    );
    if (retired) throw new Error('Journey partition is retired.');
    const row = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_outbox_v1 WHERE account_id = ?',
      accountId,
    );
    const current = readJourneyOutbox(row?.payload ?? null, accountId);
    const next = readJourneyOutbox(JSON.stringify(change(current)), accountId);
    await tx.runAsync(
      `INSERT INTO journey_outbox_v1 (account_id, payload) VALUES (?, ?)
      ON CONFLICT(account_id) DO UPDATE SET payload = excluded.payload`,
      accountId,
      JSON.stringify(next),
    );
    committed = next;
  });
  if (!committed) throw new Error('Journey outbox transaction did not commit.');
  return committed;
}

/** A successful network response is accepted only with a matching durable lease. */
export async function acknowledgeJourneyResult(
  db: OutboxDatabase,
  accountId: string,
  lease: string,
  response: unknown,
  now: number,
): Promise<boolean> {
  readJourneyOutbox(null, accountId); // Validate before opening a transaction.
  let accepted = false;
  await db.withExclusiveTransactionAsync(async (tx) => {
    const retired = await tx.getFirstAsync<{ account_id: string }>(
      'SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?',
      accountId,
    );
    if (retired) return;
    const queueRow = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_outbox_v1 WHERE account_id = ?',
      accountId,
    );
    const queue = readJourneyOutbox(queueRow?.payload ?? null, accountId);
    const head = queue.entries[0];
    if (!head || head.lease?.token !== lease) return;
    const snapshotRow = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?',
      accountId,
    );
    const snapshots = recordJourneyResult(
      readJourneySnapshots(snapshotRow?.payload ?? null, accountId),
      head.command,
      response,
    );
    const next = settleJourneyCommand(queue, lease, 'success', now);
    await tx.runAsync(
      `INSERT INTO journey_snapshots_v1 (account_id, payload) VALUES (?, ?)
      ON CONFLICT(account_id) DO UPDATE SET payload = excluded.payload`,
      accountId,
      JSON.stringify(snapshots),
    );
    await tx.runAsync(
      'UPDATE journey_outbox_v1 SET payload = ? WHERE account_id = ?',
      JSON.stringify(next),
      accountId,
    );
    accepted = true;
  });
  return accepted;
}

/** Permanently retire only a deleted account. Never use for logout or a local cache reset. */
export async function clearJourneyPartition(db: OutboxDatabase, accountId: string): Promise<void> {
  readJourneyOutbox(null, accountId); // Validate before any mutation.
  await db.withExclusiveTransactionAsync(async (tx) => {
    await tx.runAsync(
      `INSERT INTO journey_retired_accounts_v1 (account_id) VALUES (?)
      ON CONFLICT(account_id) DO NOTHING`,
      accountId,
    );
    await tx.runAsync('DELETE FROM journey_outbox_v1 WHERE account_id = ?', accountId);
    await tx.runAsync('DELETE FROM journey_snapshots_v1 WHERE account_id = ?', accountId);
  });
}
