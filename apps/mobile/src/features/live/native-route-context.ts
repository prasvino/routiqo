import { readRouteRequest, type RouteRequest } from '@routiqo/shared';
import { nativeAbortError } from '../../auth/abort-error';
import { NativeSessionRequired, type createNativeAccount } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';

type Account = ReturnType<typeof createNativeAccount>;
type ContextCode =
  | 'invalid'
  | 'session'
  | 'forbidden'
  | 'not_found'
  | 'conflict'
  | 'rate_limited'
  | 'unavailable'
  | 'timeout';
const uuidPattern = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const nil = '00000000-0000-0000-0000-000000000000';
const decimalPattern = /^(0|[1-9][0-9]{0,18})$/;
const maximumLong = '9223372036854775807';
const instantPattern = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const exact = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key));

export type NativeRouteSelection = RouteRequest & { alternativeIndex: number };
export type NativeRouteBindingInput = NativeRouteSelection & { expectedContextId: string | null };
export interface NativeRouteContext {
  contextId: string;
  revision: string;
  anchorIds: string[];
  issuedAt: string;
  expiresAt: string;
}
export interface NativeRouteContextRead {
  context: NativeRouteContext | null;
}
export interface NativeRouteBindingResult {
  status: 'bound' | 'no_route' | 'no_eligible_anchors';
  context: NativeRouteContext | null;
}
export class NativeRouteContextError extends Error {
  constructor(
    readonly code: ContextCode,
    readonly status?: number,
  ) {
    super(
      {
        invalid: 'Choose valid route preparation inputs.',
        session: 'Sign in to continue.',
        forbidden: 'Private route preparation is unavailable for this account.',
        not_found: 'This journey is unavailable.',
        conflict: 'Private route settings changed. Check again.',
        rate_limited: 'Too many private route requests. Try again shortly.',
        unavailable: 'Private route preparation is unavailable. Try again.',
        timeout: 'Private route request timed out.',
      }[code],
    );
    this.name = 'NativeRouteContextError';
  }
}
function invalid(): never {
  throw new NativeRouteContextError('invalid');
}
function uuid(value: unknown): string {
  if (
    typeof value !== 'string' ||
    value.length !== 36 ||
    value === nil ||
    uuidPattern.exec(value)?.[0] !== value
  )
    invalid();
  return value;
}
function decimal(value: unknown): string {
  if (
    typeof value !== 'string' ||
    decimalPattern.exec(value)?.[0] !== value ||
    (value.length === maximumLong.length && value > maximumLong)
  )
    invalid();
  return value;
}
function instant(value: unknown): { value: string; nanos: bigint } {
  if (typeof value !== 'string') invalid();
  const match = instantPattern.exec(value);
  if (!match || match[0] !== value || value.startsWith('0000')) invalid();
  const millis = Date.parse(`${match[1]}Z`);
  if (!Number.isFinite(millis) || new Date(millis).toISOString().slice(0, 19) !== match[1])
    invalid();
  const fraction = BigInt((match[2] ?? '').padEnd(9, '0') || '0');
  return { value, nanos: BigInt(millis) * 1_000_000n + fraction };
}
function context(value: unknown): NativeRouteContext {
  if (
    !record(value) ||
    !exact(value, ['contextId', 'revision', 'anchorIds', 'issuedAt', 'expiresAt']) ||
    !Array.isArray(value.anchorIds) ||
    value.anchorIds.length < 1 ||
    value.anchorIds.length > 128
  )
    invalid();
  const anchorIds = value.anchorIds.map(uuid);
  if (anchorIds.some((item, index) => index > 0 && anchorIds[index - 1]! >= item)) invalid();
  const issued = instant(value.issuedAt);
  const expires = instant(value.expiresAt);
  const lifetime = expires.nanos - issued.nanos;
  if (lifetime <= 0n || lifetime > 900_000_000_000n) invalid();
  return {
    contextId: uuid(value.contextId),
    revision: decimal(value.revision),
    anchorIds,
    issuedAt: issued.value,
    expiresAt: expires.value,
  };
}
export function readNativeRouteContextResult(value: unknown): NativeRouteContextRead {
  if (!record(value) || !exact(value, ['context'])) invalid();
  return { context: value.context === null ? null : context(value.context) };
}
export function readNativeRouteBindingResult(value: unknown): NativeRouteBindingResult {
  if (
    !record(value) ||
    !exact(value, ['status', 'context']) ||
    !['bound', 'no_route', 'no_eligible_anchors'].includes(value.status as string)
  )
    invalid();
  const parsed = value.context === null ? null : context(value.context);
  if ((value.status === 'bound') !== (parsed !== null)) invalid();
  return { status: value.status as NativeRouteBindingResult['status'], context: parsed };
}
function bindingInput(value: unknown): NativeRouteBindingInput {
  if (
    !record(value) ||
    !exact(value, ['mode', 'origin', 'destination', 'alternativeIndex', 'expectedContextId']) ||
    typeof value.alternativeIndex !== 'number' ||
    !Number.isInteger(value.alternativeIndex) ||
    value.alternativeIndex < 0 ||
    value.alternativeIndex > 2
  )
    invalid();
  const expectedContextId = value.expectedContextId === null ? null : uuid(value.expectedContextId);
  let route: RouteRequest;
  try {
    route = readRouteRequest(value);
  } catch {
    invalid();
  }
  return { ...route, alternativeIndex: value.alternativeIndex, expectedContextId };
}
function classify(error: unknown): Error {
  if (error instanceof NativeRouteContextError) return error;
  if (error instanceof NativeSessionRequired) return new NativeRouteContextError('session');
  if (error instanceof NativeHttpStatus) {
    const code: ContextCode =
      error.status === 401
        ? 'session'
        : error.status === 403
          ? 'forbidden'
          : error.status === 404
            ? 'not_found'
            : error.status === 409
              ? 'conflict'
              : error.status === 429
                ? 'rate_limited'
                : [400, 413, 415, 422].includes(error.status)
                  ? 'invalid'
                  : 'unavailable';
    return new NativeRouteContextError(code, error.status);
  }
  if (error instanceof Error && error.name === 'AbortError')
    return nativeAbortError('Private route request cancelled.');
  return new NativeRouteContextError('unavailable');
}
async function request<T>(
  identity: Account,
  accountId: string,
  journeyId: string,
  method: 'GET' | 'POST',
  body: NativeRouteBindingInput | undefined,
  parse: (value: unknown) => T,
  signal?: AbortSignal,
): Promise<T> {
  uuid(accountId);
  uuid(journeyId);
  if (signal?.aborted) throw nativeAbortError('Private route request cancelled.');
  if (identity.activeAccount() !== accountId) throw new NativeRouteContextError('session');
  const revision = identity.revision();
  const deadline = method === 'GET' ? 12_000 : 30_000;
  const started = Date.now();
  const current = () => {
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new NativeRouteContextError('session');
  };
  const controller = new AbortController();
  let rejectStop!: (reason: Error) => void;
  const stopped = new Promise<never>((_, reject) => {
    rejectStop = reject;
  });
  void stopped.catch(() => undefined);
  let active = true;
  let stopReason: Error | null = null;
  const stop = (reason: Error) => {
    if (!active) return;
    active = false;
    stopReason = reason;
    rejectStop(reason);
    controller.abort();
  };
  const onAbort = () => stop(nativeAbortError('Private route request cancelled.'));
  signal?.addEventListener('abort', onAbort, { once: true });
  const timer = setTimeout(() => stop(new NativeRouteContextError('timeout')), deadline);
  try {
    if (signal?.aborted) onAbort();
    current();
    const pending = identity.verifiedRequest(
      `/api/v1/native/journeys/${journeyId}/route-context`,
      method,
      { accountId, ...(body === undefined ? {} : { body }), signal: controller.signal },
    );
    void pending.catch(() => undefined);
    const raw = await Promise.race([pending, stopped]);
    if (!active) throw stopReason ?? new NativeRouteContextError('timeout');
    current();
    let result: T;
    try {
      result = parse(raw);
    } catch {
      throw new NativeRouteContextError('unavailable');
    }
    current();
    if (Date.now() - started >= deadline) throw new NativeRouteContextError('timeout');
    return result;
  } catch (error) {
    if (signal?.aborted) throw nativeAbortError('Private route request cancelled.');
    if (identity.activeAccount() !== accountId || identity.revision() !== revision)
      throw new NativeRouteContextError('session');
    throw classify(error);
  } finally {
    active = false;
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}
export function readNativeRouteContext(
  identity: Account,
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<NativeRouteContextRead> {
  return request(
    identity,
    accountId,
    journeyId,
    'GET',
    undefined,
    readNativeRouteContextResult,
    signal,
  );
}
export function bindNativeRouteContext(
  identity: Account,
  accountId: string,
  journeyId: string,
  input: NativeRouteBindingInput,
  signal?: AbortSignal,
): Promise<NativeRouteBindingResult> {
  const snapshot = bindingInput(input);
  return request(
    identity,
    accountId,
    journeyId,
    'POST',
    snapshot,
    readNativeRouteBindingResult,
    signal,
  );
}
