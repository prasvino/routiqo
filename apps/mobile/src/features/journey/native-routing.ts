import {
  readPlaceQuery,
  readPlaceResults,
  readRouteRequest,
  readRouteResult,
  type PlaceResults,
  type RouteRequest,
  type RouteResult,
} from '@routiqo/shared';
import { NativeSessionRequired, type createNativeAccount } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import { nativeAbortError } from '../../auth/abort-error';

type Account = ReturnType<typeof createNativeAccount>;
type RoutingCode =
  'invalid' | 'coverage' | 'session' | 'forbidden' | 'rate-limited' | 'unavailable' | 'timeout';
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const nil = '00000000-0000-0000-0000-000000000000';
const deadlineMs = 18_000;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const exact = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));

export class NativeRoutingError extends Error {
  constructor(
    readonly code: RoutingCode,
    readonly status?: number,
  ) {
    super(
      {
        invalid: 'Choose valid route planning inputs.',
        coverage: 'Route coverage is unavailable for these places.',
        session: 'Sign in to continue.',
        forbidden: 'Route planning is unavailable for this account.',
        'rate-limited': 'Too many route requests. Try again shortly.',
        unavailable: 'Route planning is unavailable. Try again.',
        timeout: 'Route planning request timed out.',
      }[code],
    );
    this.name = 'NativeRoutingError';
  }
}
function accountIdentity(value: string): string {
  if (typeof value !== 'string' || value.length !== 36 || value === nil || !uuid.test(value))
    throw new NativeRoutingError('invalid');
  return value;
}
function routeInput(value: unknown): RouteRequest {
  if (!record(value) || !exact(value, ['mode', 'origin', 'destination']))
    throw new NativeRoutingError('invalid');
  try {
    return readRouteRequest(value);
  } catch {
    throw new NativeRoutingError('invalid');
  }
}
function placeInput(value: unknown): string {
  try {
    return readPlaceQuery(value);
  } catch {
    throw new NativeRoutingError('invalid');
  }
}
function classify(error: unknown): Error {
  if (error instanceof NativeRoutingError) return error;
  if (error instanceof NativeSessionRequired) return new NativeRoutingError('session');
  if (error instanceof NativeHttpStatus) {
    const code: RoutingCode =
      error.status === 401
        ? 'session'
        : error.status === 403
          ? 'forbidden'
          : error.status === 429
            ? 'rate-limited'
            : error.status === 422
              ? 'coverage'
              : [400, 413, 415].includes(error.status)
                ? 'invalid'
                : 'unavailable';
    return new NativeRoutingError(code, error.status);
  }
  if (error instanceof Error && error.name === 'AbortError')
    return nativeAbortError('Route planning request cancelled.');
  return new NativeRoutingError('unavailable');
}
async function request<T>(
  identity: Account,
  accountId: string,
  path: string,
  body: unknown,
  parse: (value: unknown) => T,
  signal?: AbortSignal,
): Promise<T> {
  accountIdentity(accountId);
  if (signal?.aborted) throw nativeAbortError('Route planning request cancelled.');
  if (identity.activeAccount() !== accountId) throw new NativeRoutingError('session');
  const revision = identity.revision();
  const start = Date.now();
  const current = () => {
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new NativeRoutingError('session');
  };
  const controller = new AbortController();
  let rejectStop!: (reason: Error) => void;
  const stopped = new Promise<never>((_, reject) => {
    rejectStop = reject;
  });
  void stopped.catch(() => undefined);
  let active = true;
  const stop = (reason: Error) => {
    if (!active) return;
    active = false;
    rejectStop(reason);
    controller.abort();
  };
  const onAbort = () => stop(nativeAbortError('Route planning request cancelled.'));
  signal?.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => stop(new NativeRoutingError('timeout')), deadlineMs);
  try {
    if (signal?.aborted) onAbort();
    current();
    const pending = identity.verifiedRequest(path, 'POST', {
      accountId,
      body,
      signal: controller.signal,
    });
    void pending.catch(() => undefined);
    const raw = await Promise.race([pending, stopped]);
    if (!active) throw new NativeRoutingError('timeout');
    current();
    const result = parse(raw);
    current();
    if (Date.now() - start >= deadlineMs) throw new NativeRoutingError('timeout');
    return result;
  } catch (error) {
    if (!active && signal?.aborted) throw nativeAbortError('Route planning request cancelled.');
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new NativeRoutingError('session');
    throw classify(error);
  } finally {
    active = false;
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}
export function calculateNativeRoute(
  identity: Account,
  accountId: string,
  input: RouteRequest,
  signal?: AbortSignal,
): Promise<RouteResult> {
  const snapshot = routeInput(input);
  return request(
    identity,
    accountId,
    '/api/v1/native/routes',
    snapshot,
    (value) => {
      try {
        return readRouteResult(value);
      } catch {
        throw new NativeRoutingError('unavailable');
      }
    },
    signal,
  );
}
export function searchNativePlaces(
  identity: Account,
  accountId: string,
  query: string,
  signal?: AbortSignal,
): Promise<PlaceResults> {
  const snapshot = placeInput(query);
  return request(
    identity,
    accountId,
    '/api/v1/native/routes/places',
    { query: snapshot },
    (value) => {
      try {
        return readPlaceResults(value);
      } catch {
        throw new NativeRoutingError('unavailable');
      }
    },
    signal,
  );
}
