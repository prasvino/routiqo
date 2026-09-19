import type { components } from '@routiqo/api-client';

export type BrowserLiveErrorKind =
  'invalid' | 'authentication' | 'not_found' | 'conflict' | 'rate_limited' | 'unavailable';

export class BrowserLiveError extends Error {
  constructor(public readonly kind: BrowserLiveErrorKind) {
    super('Private LIVE request failed.');
  }
}

export type LiveConsent = components['schemas']['BrowserJourneyConsent'];
export type LiveConsentIntent = components['schemas']['BrowserJourneyConsentIntent'];
export type LiveRouteContext = components['schemas']['BrowserRouteContext'];
export type LiveRouteContextRead = components['schemas']['BrowserRouteContextRead'];
export type LiveRouteBinding = components['schemas']['BrowserRouteContextBindingRequest'];
export type LiveRouteBindingResult = components['schemas']['BrowserRouteContextBindingResponse'];
export type LiveSignalIssue = components['schemas']['BrowserSignalIssueRequest'];
export type LiveExpectedSignalIssue = components['schemas']['BrowserExpectedSignalIssueRequest'];
export type LiveSignalChoice = components['schemas']['BrowserSignalChoice'];
export type LiveSignalChoiceSnapshot = components['schemas']['BrowserSignalChoiceSnapshot'];
export type LiveSignalAcceptance = components['schemas']['BrowserSignalAcceptanceRequest'];
export type LiveSignalGrant = components['schemas']['BrowserSignalCommandGrant'];
export type LiveSignalReceipt = components['schemas']['BrowserSignalReceipt'];
export type LiveSignalStopResponse = components['schemas']['BrowserSignalStopResponse'];

const uuidPattern =
  /^(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const decimalPattern = /^(?:0|[1-9][0-9]{0,18})$/;
const maximumLong = '9223372036854775807';
const instantPattern = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;
const modes = ['driving', 'walking', 'cycling'] as const;
const categories = ['food_queue', 'parking', 'queue', 'restroom', 'traffic'] as const;
const values = [
  'queue_under_5',
  'queue_5_to_15',
  'queue_15_to_30',
  'queue_over_30',
  'traffic_moving',
  'traffic_slow',
  'traffic_very_slow',
  'traffic_stopped',
  'parking_available',
  'parking_filling',
  'parking_full',
  'food_queue_none',
  'food_queue_short',
  'food_queue_long',
  'restroom_usable',
  'restroom_busy',
  'restroom_problem_reported',
] as const;
const displayLabelPattern = /^(?: |\p{L}|\p{M}|\p{N}|\p{P}|\p{S})+$/u;

type LivePath =
  | `/api/v1/journeys/${string}/consent`
  | `/api/v1/journeys/${string}/route-context`
  | `/api/v1/journeys/${string}/signal-choices`
  | `/api/v1/journeys/${string}/signal-commands`
  | `/api/v1/journeys/${string}/signal-commands/expected-context`
  | `/api/v1/journeys/${string}/signal-commands/${string}/stop`
  | `/api/v1/journeys/${string}/signals/${string}`
  | `/api/v1/journeys/${string}/signals/${string}/withdraw`;

type PlainRecord = Record<string, unknown>;

function invalid(): never {
  throw new BrowserLiveError('invalid');
}

function plainRecord(value: unknown, keys: readonly string[]): PlainRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid();
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) invalid();
  const actual = Object.keys(value);
  if (actual.length !== keys.length || actual.some((key) => !keys.includes(key))) invalid();
  return value as PlainRecord;
}

export function readUuid(value: unknown): string {
  if (typeof value !== 'string' || uuidPattern.exec(value)?.[0] !== value) invalid();
  return value;
}

function readDecimal(value: unknown): string {
  if (
    typeof value !== 'string' ||
    decimalPattern.exec(value)?.[0] !== value ||
    (value.length === maximumLong.length && value > maximumLong)
  )
    invalid();
  return value;
}

function readCoordinate(value: unknown): [number, number] {
  if (
    !Array.isArray(value) ||
    value.length !== 2 ||
    typeof value[0] !== 'number' ||
    !Number.isFinite(value[0]) ||
    Math.abs(value[0]) > 180 ||
    typeof value[1] !== 'number' ||
    !Number.isFinite(value[1]) ||
    Math.abs(value[1]) > 90
  )
    invalid();
  return [value[0], value[1]];
}

interface InstantValue {
  raw: string;
  nanoseconds: bigint;
}

function readInstant(value: unknown): InstantValue {
  if (typeof value !== 'string') invalid();
  const match = instantPattern.exec(value);
  if (!match || match[0] !== value || value.startsWith('0000')) invalid();
  const milliseconds = Date.parse(`${match[1]}Z`);
  if (
    !Number.isFinite(milliseconds) ||
    new Date(milliseconds).toISOString().slice(0, 19) !== match[1]
  )
    invalid();
  const fraction = BigInt((match[2] ?? '').padEnd(9, '0') || '0');
  return { raw: value, nanoseconds: BigInt(milliseconds) * 1_000_000n + fraction };
}

function requireLifetime(
  issued: InstantValue,
  expires: InstantValue,
  maximumSeconds: number,
): void {
  const duration = expires.nanoseconds - issued.nanoseconds;
  if (duration <= 0n || duration > BigInt(maximumSeconds) * 1_000_000_000n) invalid();
}

export function readConsentIntent(value: unknown): LiveConsentIntent {
  const item = plainRecord(value, ['expectedGeneration', 'sharing']);
  if (typeof item.sharing !== 'boolean') invalid();
  return { expectedGeneration: readDecimal(item.expectedGeneration), sharing: item.sharing };
}

export function readConsent(
  value: unknown,
  journeyId: string,
  submitted?: LiveConsentIntent,
): LiveConsent {
  const item = plainRecord(value, ['journeyId', 'generation', 'sharing', 'journeyActive']);
  if (
    readUuid(item.journeyId) !== journeyId ||
    typeof item.sharing !== 'boolean' ||
    typeof item.journeyActive !== 'boolean' ||
    (item.sharing && !item.journeyActive)
  )
    invalid();
  const generation = readDecimal(item.generation);
  if (submitted) {
    if (item.sharing !== submitted.sharing) invalid();
    const actual = BigInt(generation);
    const expected = BigInt(submitted.expectedGeneration);
    if (
      submitted.sharing
        ? !item.journeyActive || actual !== expected + 1n
        : item.journeyActive && actual < expected + (expected === BigInt(maximumLong) ? 0n : 1n)
    )
      invalid();
  }
  return {
    journeyId,
    generation,
    sharing: item.sharing,
    journeyActive: item.journeyActive,
  };
}

export function readRouteBinding(value: unknown): LiveRouteBinding {
  const item = plainRecord(value, [
    'mode',
    'origin',
    'destination',
    'alternativeIndex',
    'expectedContextId',
  ]);
  if (!modes.includes(item.mode as (typeof modes)[number])) invalid();
  const origin = readCoordinate(item.origin);
  const destination = readCoordinate(item.destination);
  if (origin[0] === destination[0] && origin[1] === destination[1]) invalid();
  if (
    !Number.isInteger(item.alternativeIndex) ||
    (item.alternativeIndex as number) < 0 ||
    (item.alternativeIndex as number) > 2
  )
    invalid();
  const expectedContextId =
    item.expectedContextId === null ? null : readUuid(item.expectedContextId);
  return {
    mode: item.mode as LiveRouteBinding['mode'],
    origin,
    destination,
    alternativeIndex: item.alternativeIndex as number,
    expectedContextId,
  };
}

function readRouteContext(value: unknown): LiveRouteContext {
  const item = plainRecord(value, ['contextId', 'revision', 'anchorIds', 'issuedAt', 'expiresAt']);
  if (!Array.isArray(item.anchorIds) || item.anchorIds.length < 1 || item.anchorIds.length > 128)
    invalid();
  const anchorIds = item.anchorIds.map(readUuid);
  if (
    new Set(anchorIds).size !== anchorIds.length ||
    anchorIds.some((anchor, index) => index > 0 && anchorIds[index - 1]! >= anchor)
  )
    invalid();
  const issuedAt = readInstant(item.issuedAt);
  const expiresAt = readInstant(item.expiresAt);
  requireLifetime(issuedAt, expiresAt, 15 * 60);
  return {
    contextId: readUuid(item.contextId),
    revision: readDecimal(item.revision),
    anchorIds,
    issuedAt: issuedAt.raw,
    expiresAt: expiresAt.raw,
  };
}

export function readRouteContextResult(value: unknown): { context: LiveRouteContext | null } {
  const item = plainRecord(value, ['context']);
  return { context: item.context === null ? null : readRouteContext(item.context) };
}

export function readRouteBindingResult(value: unknown): LiveRouteBindingResult {
  const item = plainRecord(value, ['status', 'context']);
  if (!['bound', 'no_route', 'no_eligible_anchors'].includes(item.status as string)) invalid();
  const context = item.context === null ? null : readRouteContext(item.context);
  if ((item.status === 'bound') !== (context !== null)) invalid();
  return { status: item.status as LiveRouteBindingResult['status'], context };
}

export function readSignalIssue(value: unknown): LiveSignalIssue {
  const item = plainRecord(value, ['anchorId']);
  return { anchorId: readUuid(item.anchorId) };
}

export function readExpectedSignalIssue(value: unknown): LiveExpectedSignalIssue {
  const item = plainRecord(value, ['anchorId', 'contextId', 'routeRevision', 'consentGeneration']);
  return {
    anchorId: readUuid(item.anchorId),
    contextId: readUuid(item.contextId),
    routeRevision: readDecimal(item.routeRevision),
    consentGeneration: readDecimal(item.consentGeneration),
  };
}

function readCategories(value: unknown): LiveSignalGrant['categories'] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 5) invalid();
  const accepted = value.map((category) => {
    if (!categories.includes(category as (typeof categories)[number])) invalid();
    return category as LiveSignalGrant['categories'][number];
  });
  if (
    new Set(accepted).size !== accepted.length ||
    accepted.some((category, index) => index > 0 && accepted[index - 1]! >= category)
  )
    invalid();
  return accepted;
}

function readDisplayLabel(value: unknown): string {
  const codePoints = typeof value === 'string' ? Array.from(value).length : 0;
  if (
    typeof value !== 'string' ||
    value.startsWith(' ') ||
    value.endsWith(' ') ||
    codePoints < 1 ||
    codePoints > 80 ||
    displayLabelPattern.exec(value)?.[0] !== value
  )
    invalid();
  return value;
}

export function readSignalChoiceSnapshot(
  value: unknown,
  nowNanoseconds = BigInt(Date.now()) * 1_000_000n,
): LiveSignalChoiceSnapshot {
  const item = plainRecord(value, [
    'contextId',
    'routeRevision',
    'consentGeneration',
    'issuedAt',
    'expiresAt',
    'choices',
  ]);
  if (!Array.isArray(item.choices) || item.choices.length < 1 || item.choices.length > 128)
    invalid();
  const choices = item.choices.map((value): LiveSignalChoice => {
    const choice = plainRecord(value, ['anchorId', 'displayLabel', 'categories']);
    return {
      anchorId: readUuid(choice.anchorId),
      displayLabel: readDisplayLabel(choice.displayLabel),
      categories: readCategories(choice.categories),
    };
  });
  if (
    new Set(choices.map((choice) => choice.anchorId)).size !== choices.length ||
    choices.some((choice, index) => index > 0 && choices[index - 1]!.anchorId >= choice.anchorId)
  )
    invalid();
  const issuedAt = readInstant(item.issuedAt);
  const expiresAt = readInstant(item.expiresAt);
  requireLifetime(issuedAt, expiresAt, 24 * 60 * 60);
  if (issuedAt.nanoseconds > nowNanoseconds || expiresAt.nanoseconds <= nowNanoseconds) invalid();
  return {
    contextId: readUuid(item.contextId),
    routeRevision: readDecimal(item.routeRevision),
    consentGeneration: readDecimal(item.consentGeneration),
    issuedAt: issuedAt.raw,
    expiresAt: expiresAt.raw,
    choices,
  };
}

export function readSignalAcceptance(value: unknown): LiveSignalAcceptance {
  const item = plainRecord(value, [
    'anchorId',
    'value',
    'contextId',
    'routeRevision',
    'consentGeneration',
  ]);
  if (!values.includes(item.value as (typeof values)[number])) invalid();
  return {
    anchorId: readUuid(item.anchorId),
    value: item.value as LiveSignalAcceptance['value'],
    contextId: readUuid(item.contextId),
    routeRevision: readDecimal(item.routeRevision),
    consentGeneration: readDecimal(item.consentGeneration),
  };
}

export function readSignalGrant(value: unknown, requestedAnchor: string): LiveSignalGrant {
  const item = plainRecord(value, [
    'commandId',
    'anchorId',
    'contextId',
    'routeRevision',
    'consentGeneration',
    'categories',
    'issuedAt',
    'expiresAt',
  ]);
  const acceptedCategories = readCategories(item.categories);
  const anchorId = readUuid(item.anchorId);
  if (anchorId !== requestedAnchor) invalid();
  const issuedAt = readInstant(item.issuedAt);
  const expiresAt = readInstant(item.expiresAt);
  requireLifetime(issuedAt, expiresAt, 90);
  return {
    commandId: readUuid(item.commandId),
    anchorId,
    contextId: readUuid(item.contextId),
    routeRevision: readDecimal(item.routeRevision),
    consentGeneration: readDecimal(item.consentGeneration),
    categories: acceptedCategories,
    issuedAt: issuedAt.raw,
    expiresAt: expiresAt.raw,
  };
}

export function readExpectedSignalGrant(
  value: unknown,
  expected: LiveExpectedSignalIssue,
  nowNanoseconds = BigInt(Date.now()) * 1_000_000n,
): LiveSignalGrant {
  const grant = readSignalGrant(value, expected.anchorId);
  if (
    grant.contextId !== expected.contextId ||
    grant.routeRevision !== expected.routeRevision ||
    grant.consentGeneration !== expected.consentGeneration
  )
    invalid();
  const issuedAt = readInstant(grant.issuedAt);
  const expiresAt = readInstant(grant.expiresAt);
  if (issuedAt.nanoseconds > nowNanoseconds || expiresAt.nanoseconds <= nowNanoseconds) invalid();
  return grant;
}

export function readSignalReceipt(
  value: unknown,
  requestedCommand: string,
  withdrawal: boolean,
): LiveSignalReceipt {
  const item = plainRecord(value, [
    'commandId',
    'status',
    'receivedAt',
    'expiresAt',
    'retainUntil',
  ]);
  if (
    !['accepted', 'withdrawn', 'superseded'].includes(item.status as string) ||
    (withdrawal && item.status === 'accepted') ||
    readUuid(item.commandId) !== requestedCommand
  )
    invalid();
  const receivedAt = readInstant(item.receivedAt);
  const expiresAt = readInstant(item.expiresAt);
  const retainUntil = readInstant(item.retainUntil);
  if (
    expiresAt.nanoseconds - receivedAt.nanoseconds !== 15n * 60n * 1_000_000_000n ||
    retainUntil.nanoseconds - receivedAt.nanoseconds !== 24n * 60n * 60n * 1_000_000_000n
  )
    invalid();
  return {
    commandId: requestedCommand,
    status: item.status as LiveSignalReceipt['status'],
    receivedAt: receivedAt.raw,
    expiresAt: expiresAt.raw,
    retainUntil: retainUntil.raw,
  };
}

export function readSignalStopResponse(
  value: unknown,
  requestedCommand: string,
): LiveSignalStopResponse {
  const item = plainRecord(value, ['commandId', 'status', 'receipt']);
  if (item.status !== 'stopped' || readUuid(item.commandId) !== requestedCommand) invalid();
  let receipt: LiveSignalStopResponse['receipt'] = null;
  if (item.receipt !== null) {
    const parsed = readSignalReceipt(item.receipt, requestedCommand, true);
    if (parsed.status !== 'withdrawn' && parsed.status !== 'superseded') invalid();
    receipt = {
      commandId: parsed.commandId,
      status: parsed.status,
      receivedAt: parsed.receivedAt,
      expiresAt: parsed.expiresAt,
      retainUntil: parsed.retainUntil,
    };
  }
  return {
    commandId: requestedCommand,
    status: 'stopped',
    receipt,
  };
}

function errorKind(status: number): BrowserLiveErrorKind {
  if (status === 400 || status === 413 || status === 415) return 'invalid';
  if (status === 401 || status === 403) return 'authentication';
  if (status === 404) return 'not_found';
  if (status === 409) return 'conflict';
  if (status === 429) return 'rate_limited';
  return 'unavailable';
}

function ignoreCancellation(stream: ReadableStream<Uint8Array> | null): void {
  if (!stream) return;
  try {
    void stream.cancel().catch(() => undefined);
  } catch {
    // Cancellation is best effort and never changes the redacted request result.
  }
}

function ignoreReaderCancellation(reader: ReadableStreamDefaultReader<Uint8Array>): void {
  try {
    void reader.cancel().catch(() => undefined);
  } catch {
    // Cancellation is best effort and never changes the redacted request result.
  }
  try {
    reader.releaseLock();
  } catch {
    // A hostile pending read may keep its lock until it settles.
  }
}

function beforeAbort<T>(
  operation: Promise<T>,
  signal: AbortSignal,
  discard?: (value: T) => void,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    const finish = () => signal.removeEventListener('abort', aborted);
    const aborted = () => {
      if (settled) return;
      settled = true;
      finish();
      reject(signal.reason ?? new DOMException('Private LIVE request cancelled.', 'AbortError'));
    };
    signal.addEventListener('abort', aborted, { once: true });
    operation.then(
      (value) => {
        if (settled) {
          discard?.(value);
          return;
        }
        settled = true;
        finish();
        resolve(value);
      },
      (failure: unknown) => {
        if (settled) return;
        settled = true;
        finish();
        reject(failure);
      },
    );
    if (signal.aborted) aborted();
  });
}

function requireActive(signal: AbortSignal, expiresAt: number): void {
  signal.throwIfAborted();
  if (Date.now() >= expiresAt)
    throw new DOMException('Private LIVE request timed out.', 'TimeoutError');
}

async function readJson(
  response: Response,
  limit: number,
  signal: AbortSignal,
  expiresAt: number,
): Promise<unknown> {
  if (response.status !== 200 || response.redirected) {
    ignoreCancellation(response.body);
    throw new BrowserLiveError(response.redirected ? 'unavailable' : errorKind(response.status));
  }
  const contentType = response.headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase();
  if (contentType !== 'application/json' || !response.body) {
    ignoreCancellation(response.body);
    throw new BrowserLiveError('unavailable');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let bytes = 0;
  let raw = '';
  let completed = false;
  try {
    while (true) {
      requireActive(signal, expiresAt);
      const chunk = await beforeAbort(reader.read(), signal);
      requireActive(signal, expiresAt);
      if (chunk.done) break;
      if (chunk.value.byteLength === 0) continue;
      bytes += chunk.value.byteLength;
      if (bytes > limit) throw new BrowserLiveError('unavailable');
      raw += decoder.decode(chunk.value, { stream: true });
    }
    raw += decoder.decode();
    requireActive(signal, expiresAt);
    const parsed: unknown = JSON.parse(raw);
    completed = true;
    return parsed;
  } finally {
    if (!completed) ignoreReaderCancellation(reader);
    else reader.releaseLock();
  }
}

async function fetchResponse(
  path: string,
  init: RequestInit,
  signal: AbortSignal,
  expiresAt: number,
): Promise<Response> {
  requireActive(signal, expiresAt);
  const response = await beforeAbort(fetch(path, { ...init, signal }), signal, (late) =>
    ignoreCancellation(late.body),
  );
  if (signal.aborted) {
    ignoreCancellation(response.body);
    signal.throwIfAborted();
  }
  try {
    requireActive(signal, expiresAt);
  } catch (failure) {
    ignoreCancellation(response.body);
    throw failure;
  }
  return response;
}

async function readCsrf(signal: AbortSignal, expiresAt: number): Promise<string> {
  const response = await fetchResponse(
    '/api/v1/auth/csrf',
    { credentials: 'same-origin', cache: 'no-store', redirect: 'error' },
    signal,
    expiresAt,
  );
  const raw = await readJson(response, 4 * 1024, signal, expiresAt);
  let value: PlainRecord;
  try {
    value = plainRecord(raw, ['token']);
  } catch {
    throw new BrowserLiveError('unavailable');
  }
  if (typeof value.token !== 'string' || value.token.length < 20 || value.token.length > 1024)
    throw new BrowserLiveError('unavailable');
  return value.token;
}

export async function liveRequest<T>(options: {
  accountId: string;
  path: LivePath;
  body?: unknown;
  timeoutMilliseconds: 12000 | 30000;
  responseLimit?: 65536 | 262144;
  signal?: AbortSignal | undefined;
  validate: (value: unknown) => T;
}): Promise<T> {
  let serializedBody: string | undefined;
  try {
    serializedBody = options.body === undefined ? undefined : JSON.stringify(options.body);
  } catch {
    throw new BrowserLiveError('invalid');
  }
  const expiresAt = Date.now() + options.timeoutMilliseconds;
  const deadline = new AbortController();
  const timer = setTimeout(
    () => deadline.abort(new DOMException('Private LIVE request timed out.', 'TimeoutError')),
    options.timeoutMilliseconds,
  );
  const cancellation = options.signal
    ? AbortSignal.any([options.signal, deadline.signal])
    : deadline.signal;
  try {
    requireActive(cancellation, expiresAt);
    const headers: Record<string, string> = { 'X-Routiqo-Account': options.accountId };
    if (serializedBody !== undefined) {
      headers['Content-Type'] = 'application/json';
      headers['X-XSRF-TOKEN'] = await readCsrf(cancellation, expiresAt);
    }
    requireActive(cancellation, expiresAt);
    const response = await fetchResponse(
      options.path,
      {
        method: serializedBody === undefined ? 'GET' : 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers,
        ...(serializedBody === undefined ? {} : { body: serializedBody }),
      },
      cancellation,
      expiresAt,
    );
    const raw = await readJson(
      response,
      options.responseLimit ?? 64 * 1024,
      cancellation,
      expiresAt,
    );
    let value: T;
    try {
      value = options.validate(raw);
    } catch {
      throw new BrowserLiveError('unavailable');
    }
    requireActive(cancellation, expiresAt);
    return value;
  } catch (failure) {
    if (options.signal?.aborted)
      throw new DOMException('Private LIVE request cancelled.', 'AbortError');
    if (failure instanceof BrowserLiveError) throw failure;
    throw new BrowserLiveError('unavailable');
  } finally {
    clearTimeout(timer);
  }
}
