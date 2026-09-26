import {
  readSpotActivity,
  readSpotCatalog,
  type SpotActivity,
  type SpotCatalog,
} from '@routiqo/shared';
import { NativeSessionRequired, type createNativeAccount } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import { nativeAbortError } from '../../auth/abort-error';

type Account = ReturnType<typeof createNativeAccount>;
export type SpotsFailure = 'session' | 'no-journey' | 'rate-limited' | 'unavailable' | 'invalid';

export class NativeSpotsError extends Error {
  constructor(
    readonly code: SpotsFailure,
    readonly status?: number,
  ) {
    super(
      {
        session: 'Sign in to continue.',
        'no-journey': 'Spot updates start once your journey reaches the server.',
        'rate-limited': 'Updates paused briefly.',
        unavailable: 'Spot updates are unavailable right now.',
        invalid: 'Spot updates are unavailable right now.',
      }[code],
    );
    this.name = 'NativeSpotsError';
  }
}

const deadlineMs = 12_000;
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;

function classify(error: unknown): Error {
  if (error instanceof NativeSpotsError) return error;
  if (error instanceof Error && error.name === 'AbortError') return error;
  if (error instanceof NativeSessionRequired) return new NativeSpotsError('session');
  if (error instanceof NativeHttpStatus) {
    const code: SpotsFailure =
      error.status === 401
        ? 'session'
        : error.status === 409
          ? 'no-journey'
          : error.status === 429
            ? 'rate-limited'
            : [400, 413, 415].includes(error.status)
              ? 'invalid'
              : 'unavailable';
    return new NativeSpotsError(code, error.status);
  }
  return new NativeSpotsError('unavailable');
}

/** Runs one Spots call with a deadline, cancellation and account/revision checks. */
async function guarded<T>(
  identity: Account,
  accountId: string,
  call: () => Promise<T>,
  signal?: AbortSignal,
): Promise<T> {
  if (!uuid.test(accountId) || identity.activeAccount() !== accountId)
    throw new NativeSpotsError('session');
  if (signal?.aborted) throw nativeAbortError('Spot request cancelled.');
  const revision = identity.revision();
  const current = () => {
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new NativeSpotsError('session');
  };
  let stop!: (reason: Error) => void;
  const stopped = new Promise<never>((_, reject) => {
    stop = reject;
  });
  void stopped.catch(() => undefined);
  const onAbort = () => stop(nativeAbortError('Spot request cancelled.'));
  signal?.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => stop(new NativeSpotsError('unavailable')), deadlineMs);
  try {
    const pending = call();
    void pending.catch(() => undefined);
    const result = await Promise.race([pending, stopped]);
    current();
    return result;
  } catch (error) {
    current();
    throw classify(error);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}

export type SpotCatalogFetch =
  | { status: 'not-modified' }
  | { status: 'catalog'; etag: string; payload: unknown; catalog: SpotCatalog };

/** Revalidates the cached catalog by ETag; a 200 is accepted only if it validates against its ETag. */
export function fetchNativeSpotCatalog(
  identity: Account,
  accountId: string,
  ifNoneMatch: string | null,
  signal?: AbortSignal,
): Promise<SpotCatalogFetch> {
  return guarded(
    identity,
    accountId,
    async () => {
      const result = await identity.verifiedSpotCatalog({
        accountId,
        ifNoneMatch,
        ...(signal ? { signal } : {}),
      });
      if (result.status === 'not-modified') return { status: 'not-modified' as const };
      let catalog: SpotCatalog;
      try {
        catalog = readSpotCatalog(result.catalog, result.etag);
      } catch {
        throw new NativeSpotsError('invalid');
      }
      return { status: 'catalog' as const, etag: result.etag, payload: result.catalog, catalog };
    },
    signal,
  );
}

/** Activity for the given Spot IDs. Only IDs are sent: never the route, endpoints or position. */
export function fetchNativeSpotActivity(
  identity: Account,
  accountId: string,
  spotIds: readonly string[],
  signal?: AbortSignal,
): Promise<SpotActivity> {
  const ids = [...spotIds].sort();
  if (
    ids.length < 1 ||
    ids.length > 20 ||
    new Set(ids).size !== ids.length ||
    ids.some((id) => !uuid.test(id))
  )
    return Promise.reject(new NativeSpotsError('invalid'));
  return guarded(
    identity,
    accountId,
    async () => {
      const raw = await identity.verifiedRequest('/api/v1/native/spots/activity', 'POST', {
        accountId,
        body: { spotIds: ids },
        ...(signal ? { signal } : {}),
      });
      try {
        return readSpotActivity(raw);
      } catch {
        throw new NativeSpotsError('invalid');
      }
    },
    signal,
  );
}
