import { readSpotCatalog, type SpotCatalog } from '@routiqo/shared';
import type { OutboxDatabase } from './journey-outbox';

/**
 * Device cache of the latest Spot catalog (SPOTS_SPEC, ADR 0070): one row, not account-scoped
 * because the catalog is public reference data. The payload is kept as received with its ETag and
 * re-validated on every read, so a corrupt or foreign row reads as "no catalog".
 */
export interface CachedSpotCatalog {
  catalog: SpotCatalog;
  etag: string;
  fetchedAt: string;
}

export async function initializeSpotCatalogStorage(db: Pick<OutboxDatabase, 'execAsync'>) {
  await db.execAsync(`CREATE TABLE IF NOT EXISTS spot_catalog_v1 (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    etag TEXT NOT NULL,
    payload TEXT NOT NULL,
    fetched_at TEXT NOT NULL);`);
}

export async function readCachedSpotCatalog(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
): Promise<CachedSpotCatalog | null> {
  let row: { etag: string; payload: string; fetched_at: string } | null = null;
  await db.withExclusiveTransactionAsync(async (tx) => {
    row = await tx.getFirstAsync<{ etag: string; payload: string; fetched_at: string }>(
      'SELECT etag, payload, fetched_at FROM spot_catalog_v1 WHERE id = 1',
    );
  });
  const stored = row as { etag: string; payload: string; fetched_at: string } | null;
  if (!stored) return null;
  try {
    return {
      catalog: readSpotCatalog(JSON.parse(stored.payload) as unknown, stored.etag),
      etag: stored.etag,
      fetchedAt: stored.fetched_at,
    };
  } catch {
    return null;
  }
}

/** Replaces the cached catalog. The caller has already validated `payload` against `etag`. */
export async function replaceCachedSpotCatalog(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
  etag: string,
  payload: unknown,
  fetchedAt: string,
): Promise<void> {
  const text = JSON.stringify(payload);
  await db.withExclusiveTransactionAsync(async (tx) => {
    await tx.runAsync(
      `INSERT INTO spot_catalog_v1 (id, etag, payload, fetched_at) VALUES (1, ?, ?, ?)
       ON CONFLICT (id) DO UPDATE SET etag = excluded.etag, payload = excluded.payload,
       fetched_at = excluded.fetched_at`,
      etag,
      text,
      fetchedAt,
    );
  });
}

export async function clearCachedSpotCatalog(
  db: Pick<OutboxDatabase, 'withExclusiveTransactionAsync'>,
): Promise<void> {
  await db.withExclusiveTransactionAsync(async (tx) => {
    await tx.runAsync('DELETE FROM spot_catalog_v1');
  });
}
