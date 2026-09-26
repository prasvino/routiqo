import type { SpotCatalog } from '@routiqo/shared';
import type { CachedSpotCatalog } from '../../storage/spot-catalog';
import { NativeSpotsError, type SpotCatalogFetch, type SpotsFailure } from './native-spots';

export interface SpotCatalogState {
  /** False until the SQLite cache has been read once. */
  loaded: boolean;
  catalog: SpotCatalog | null;
  refreshing: boolean;
  failure: SpotsFailure | null;
}

export interface SpotCatalogPorts {
  load(): Promise<CachedSpotCatalog | null>;
  save(etag: string, payload: unknown, fetchedAt: string): Promise<void>;
  clear(): Promise<void>;
  fetch(ifNoneMatch: string | null, signal: AbortSignal): Promise<SpotCatalogFetch>;
  now(): number;
}

/**
 * The device's Spot catalog: read from SQLite, revalidated by ETag, replaced whole on a new
 * version. One refresh in flight; any failure keeps the cached copy.
 */
export function createSpotCatalogStore(ports: SpotCatalogPorts) {
  let state: SpotCatalogState = { loaded: false, catalog: null, refreshing: false, failure: null };
  let etag: string | null = null;
  let generation = 0;
  let loading: Promise<void> | null = null;
  let pending: { controller: AbortController; promise: Promise<void> } | null = null;
  const listeners = new Set<() => void>();
  const publish = (next: Partial<SpotCatalogState>) => {
    state = { ...state, ...next };
    listeners.forEach((listener) => listener());
  };

  function load(): Promise<void> {
    loading ??= (async () => {
      const attempt = generation;
      const cached = await ports.load().catch(() => null);
      if (attempt !== generation) return;
      etag = cached?.etag ?? null;
      publish({ loaded: true, catalog: cached?.catalog ?? null });
    })();
    return loading;
  }

  function release(controller: AbortController) {
    if (pending && pending.controller === controller) pending = null;
  }

  function refresh(): Promise<void> {
    if (pending) return pending.promise;
    const controller = new AbortController();
    const promise = (async () => {
      await load();
      const attempt = generation;
      publish({ refreshing: true, failure: null });
      try {
        const result = await ports.fetch(etag, controller.signal);
        if (attempt !== generation || controller.signal.aborted) return;
        if (result.status === 'catalog') {
          await ports.save(result.etag, result.payload, new Date(ports.now()).toISOString());
          if (attempt !== generation) return;
          etag = result.etag;
          publish({ catalog: result.catalog, refreshing: false, failure: null });
        } else publish({ refreshing: false, failure: null });
      } catch (error) {
        if (attempt !== generation) return;
        publish({
          refreshing: false,
          failure:
            error instanceof NativeSpotsError
              ? error.code
              : error instanceof Error && error.name === 'AbortError'
                ? null
                : 'unavailable',
        });
      } finally {
        release(controller);
      }
    })();
    pending = { controller, promise };
    return promise;
  }

  return {
    getState: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    load,
    refresh,
    /** The version the device holds, so activity responses from a newer catalog trigger a refresh. */
    version: () => state.catalog?.version ?? null,
    /** "Clear local data": forget the cached catalog and cancel any refresh. */
    async clear() {
      generation += 1;
      pending?.controller.abort();
      pending = null;
      loading = Promise.resolve();
      etag = null;
      publish({ loaded: true, catalog: null, refreshing: false, failure: null });
      await ports.clear();
    },
    dispose() {
      generation += 1;
      pending?.controller.abort();
      pending = null;
      listeners.clear();
    },
  };
}
export type SpotCatalogStore = ReturnType<typeof createSpotCatalogStore>;
