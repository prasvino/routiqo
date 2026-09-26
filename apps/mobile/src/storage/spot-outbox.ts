import { readSpotOutbox, type SpotOutbox } from '@routiqo/shared';
import type { OutboxDatabase } from './journey-outbox';

/**
 * Device queue for Spot signals and posts (`spot_outbox_v1`, POSTS_AND_SIGNALS_SPEC, ADR 0073),
 * separate from the journey outbox so neither waits behind the other, and the device-wide Ghost
 * Mode switch (`ghost_mode_v1`). Turning Ghost Mode on clears every queued contribution in the
 * same transaction. Nothing here is backed up or logged.
 */
export async function initializeSpotOutboxStorage(db: Pick<OutboxDatabase, 'execAsync'>) {
  await db.execAsync(`CREATE TABLE IF NOT EXISTS spot_outbox_v1 (
    account_id TEXT PRIMARY KEY NOT NULL,
    payload TEXT NOT NULL
  );`);
  await db.execAsync(`CREATE TABLE IF NOT EXISTS ghost_mode_v1 (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1))
  );`);
}

type Tx = Parameters<Parameters<OutboxDatabase['withExclusiveTransactionAsync']>[0]>[0];

async function ghostOn(tx: Tx): Promise<boolean> {
  const row = await tx.getFirstAsync<{ enabled: number }>(
    'SELECT enabled FROM ghost_mode_v1 WHERE id = 1',
  );
  return row?.enabled === 1;
}

async function read(tx: Tx, accountId: string): Promise<SpotOutbox> {
  const row = await tx.getFirstAsync<{ payload: string }>(
    'SELECT payload FROM spot_outbox_v1 WHERE account_id = ?',
    accountId,
  );
  if (!row) return readSpotOutbox(null, accountId);
  try {
    return readSpotOutbox(JSON.parse(row.payload) as unknown, accountId);
  } catch {
    return readSpotOutbox(null, accountId);
  }
}

export async function readSpotOutboxFor(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
  accountId: string,
): Promise<SpotOutbox> {
  readSpotOutbox(null, accountId); // Validate before opening a transaction.
  let outbox: SpotOutbox | undefined;
  await db.withExclusiveTransactionAsync(async (tx) => {
    outbox = await read(tx, accountId);
  });
  return outbox!;
}

export class SpotOutboxUnavailable extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SpotOutboxUnavailable';
  }
}

/**
 * Changes one account's queue atomically. No network inside `change`. Refused while Ghost Mode is
 * on or for a deleted account, so nothing can be queued behind either.
 */
export async function updateSpotOutbox(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
  accountId: string,
  change: (current: SpotOutbox) => SpotOutbox,
): Promise<SpotOutbox> {
  readSpotOutbox(null, accountId);
  let committed: SpotOutbox | undefined;
  await db.withExclusiveTransactionAsync(async (tx) => {
    if (await ghostOn(tx)) throw new SpotOutboxUnavailable('Ghost Mode is on.');
    const retired = await tx.getFirstAsync<{ account_id: string }>(
      'SELECT account_id FROM journey_retired_accounts_v1 WHERE account_id = ?',
      accountId,
    );
    if (retired) throw new SpotOutboxUnavailable('This account was deleted.');
    const next = change(await read(tx, accountId));
    if (next.accountId !== accountId) throw new Error('Spot outbox account changed.');
    if (next.entries.length === 0)
      await tx.runAsync('DELETE FROM spot_outbox_v1 WHERE account_id = ?', accountId);
    else
      await tx.runAsync(
        `INSERT INTO spot_outbox_v1 (account_id, payload) VALUES (?, ?)
         ON CONFLICT (account_id) DO UPDATE SET payload = excluded.payload`,
        accountId,
        JSON.stringify(next),
      );
    committed = next;
  });
  return committed!;
}

/** Deletes queued contributions: for one account (sign-out), all others (account change), or all. */
export async function clearSpotOutbox(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
  scope: { only: string } | { except: string } | 'all',
): Promise<void> {
  await db.withExclusiveTransactionAsync(async (tx) => {
    if (scope === 'all') await tx.runAsync('DELETE FROM spot_outbox_v1');
    else if ('only' in scope)
      await tx.runAsync('DELETE FROM spot_outbox_v1 WHERE account_id = ?', scope.only);
    else await tx.runAsync('DELETE FROM spot_outbox_v1 WHERE account_id <> ?', scope.except);
  });
}

export async function readGhostMode(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
): Promise<boolean> {
  let enabled = false;
  await db.withExclusiveTransactionAsync(async (tx) => {
    enabled = await ghostOn(tx);
  });
  return enabled;
}

/** Turning Ghost Mode on also deletes every queued contribution, in the same transaction. */
export async function setGhostMode(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
  enabled: boolean,
): Promise<void> {
  await db.withExclusiveTransactionAsync(async (tx) => {
    await tx.runAsync(
      `INSERT INTO ghost_mode_v1 (id, enabled) VALUES (1, ?)
       ON CONFLICT (id) DO UPDATE SET enabled = excluded.enabled`,
      enabled ? 1 : 0,
    );
    if (enabled) await tx.runAsync('DELETE FROM spot_outbox_v1');
  });
}
