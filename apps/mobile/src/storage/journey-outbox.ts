import type { SQLiteDatabase } from 'expo-sqlite';
import {
  enqueueJourneyCommand,
  readJourneyOutbox,
  readJourneySnapshots,
  readServerJourney,
  recordJourneyResult,
  resumeJourneyAuthentication,
  settleJourneyCommand,
  type JourneyCommand,
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
  // Deletion must work for databases initialized by the outbox alone as well.
  await db.execAsync(`CREATE TABLE IF NOT EXISTS journal_partitions_v1 (
    account_id TEXT PRIMARY KEY NOT NULL,
    payload TEXT NOT NULL
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

/** A verified session releases only a command blocked by authentication. */
export async function resumeMobileJourneyAuthentication(
  db: OutboxDatabase,
  accountId: string,
  now: number,
): Promise<boolean> {
  readJourneyOutbox(null, accountId);
  let resumed = false;
  await db.withExclusiveTransactionAsync(async (tx) => {
    const retired = await tx.getFirstAsync<{ account_id: string }>(
      'SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?',
      accountId,
    );
    if (retired) return;
    const row = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_outbox_v1 WHERE account_id = ?',
      accountId,
    );
    const queue = readJourneyOutbox(row?.payload ?? null, accountId);
    if (queue.entries[0]?.blocked !== 'authentication') return;
    const next = resumeJourneyAuthentication(queue, accountId, now);
    await tx.runAsync(
      'UPDATE journey_outbox_v1 SET payload = ? WHERE account_id = ?',
      JSON.stringify(next),
      accountId,
    );
    resumed = true;
  });
  return resumed;
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
    await tx.runAsync('DELETE FROM journal_partitions_v1 WHERE account_id = ?', accountId);
  });
}

export async function readMobileJourneyPartition(db: SQLiteDatabase, accountId: string) {
  const queue = await db.getFirstAsync<{ payload: string }>(
    'SELECT payload FROM journey_outbox_v1 WHERE account_id = ?',
    accountId,
  );
  const snapshots = await db.getFirstAsync<{ payload: string }>(
    'SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?',
    accountId,
  );
  return {
    outbox: readJourneyOutbox(queue?.payload ?? null, accountId),
    snapshots: readJourneySnapshots(snapshots?.payload ?? null, accountId),
  };
}

/** The command is committed before any network send; plans are never promoted implicitly. */
export async function queueMobileJourney(
  db: OutboxDatabase,
  accountId: string,
  command: JourneyCommand,
  now: number,
): Promise<void> {
  readJourneyOutbox(null, accountId);
  await db.withExclusiveTransactionAsync(async (tx) => {
    const retired = await tx.getFirstAsync<{ account_id: string }>(
      'SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?',
      accountId,
    );
    if (retired) throw new Error('Journey account is retired.');
    const queueRow = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_outbox_v1 WHERE account_id = ?',
      accountId,
    );
    const snapshotRow = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?',
      accountId,
    );
    const queue = readJourneyOutbox(queueRow?.payload ?? null, accountId);
    const snapshots = readJourneySnapshots(snapshotRow?.payload ?? null, accountId);
    const known = snapshots.journeys.find((item) => item.id === command.journeyId);
    if (command.action === 'start') {
      if (known) {
        if (known.kind !== command.kind) throw new Error('Journey kind changed.');
        return;
      }
      if (
        snapshots.journeys.some((item) => item.status === 'active') ||
        queue.entries.some((item) => item.command.journeyId !== command.journeyId)
      )
        throw new Error('Finish or resolve the current journey first.');
    } else {
      if (known?.status === 'completed') return;
      if (
        !known &&
        !queue.entries.some(
          (item) => item.command.journeyId === command.journeyId && item.command.action === 'start',
        )
      )
        throw new Error('Restore this journey before completing it.');
    }
    const next = enqueueJourneyCommand(queue, command, now);
    await tx.runAsync(
      `INSERT INTO journey_outbox_v1 (account_id, payload) VALUES (?, ?)
      ON CONFLICT(account_id) DO UPDATE SET payload = excluded.payload`,
      accountId,
      JSON.stringify(next),
    );
  });
}

export async function mergeMobileJourneyHistory(
  db: OutboxDatabase,
  accountId: string,
  input: unknown[],
): Promise<void> {
  if (input.length > 51) throw new Error('Journey history is too large.');
  const journeys = input.map(readServerJourney);
  if (new Set(journeys.map((item) => item.id)).size !== journeys.length)
    throw new Error('Duplicate journey history.');
  await db.withExclusiveTransactionAsync(async (tx) => {
    const retired = await tx.getFirstAsync<{ account_id: string }>(
      'SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?',
      accountId,
    );
    if (retired) return;
    const row = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?',
      accountId,
    );
    const previous = readJourneySnapshots(row?.payload ?? null, accountId);
    let snapshots = previous;
    for (const journey of journeys) {
      const known = previous.journeys.find((item) => item.id === journey.id);
      if (known?.status === 'completed' && journey.status === 'active') continue;
      snapshots = recordJourneyResult(
        snapshots,
        { journeyId: journey.id, action: 'start', kind: journey.kind },
        journey,
      );
    }
    if (snapshots.journeys.filter((item) => item.status === 'active').length > 1)
      throw new Error('Journey history needs individual reconciliation.');
    await tx.runAsync(
      `INSERT INTO journey_snapshots_v1 (account_id, payload) VALUES (?, ?)
      ON CONFLICT(account_id) DO UPDATE SET payload = excluded.payload`,
      accountId,
      JSON.stringify(snapshots),
    );
  });
}

/** A blocked replay is removed only when a matching owner read proves server application. */
export async function reconcileMobileJourney(
  db: OutboxDatabase,
  accountId: string,
  expected: JourneyCommand,
  response: unknown,
): Promise<boolean> {
  const verified = readServerJourney(response);
  let accepted = false;
  await db.withExclusiveTransactionAsync(async (tx) => {
    const queueRow = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_outbox_v1 WHERE account_id = ?',
      accountId,
    );
    const queue = readJourneyOutbox(queueRow?.payload ?? null, accountId);
    const head = queue.entries[0];
    if (
      !head ||
      !['conflict', 'rejected'].includes(head.blocked ?? '') ||
      head.lease ||
      head.command.journeyId !== expected.journeyId ||
      head.command.action !== expected.action ||
      (head.command.action === 'start' &&
        expected.action === 'start' &&
        head.command.kind !== expected.kind)
    )
      return;
    const snapshotRow = await tx.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM journey_snapshots_v1 WHERE account_id = ?',
      accountId,
    );
    const snapshots = recordJourneyResult(
      readJourneySnapshots(snapshotRow?.payload ?? null, accountId),
      head.command,
      verified,
    );
    await tx.runAsync(
      `INSERT INTO journey_snapshots_v1 (account_id, payload) VALUES (?, ?)
      ON CONFLICT(account_id) DO UPDATE SET payload = excluded.payload`,
      accountId,
      JSON.stringify(snapshots),
    );
    await tx.runAsync(
      'UPDATE journey_outbox_v1 SET payload = ? WHERE account_id = ?',
      JSON.stringify({ ...queue, entries: queue.entries.slice(1) }),
      accountId,
    );
    accepted = true;
  });
  return accepted;
}
