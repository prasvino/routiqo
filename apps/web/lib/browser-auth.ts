import type { components, paths } from '@routiqo/api-client';

export type GoogleChallenge = components['schemas']['GoogleChallenge'];
export type BrowserSession = components['schemas']['BrowserSession'];
export type BrowserAccount =
  paths['/api/v1/auth/session']['get']['responses'][200]['content']['application/json'];

export interface AuthAvailability {
  enabled: boolean;
  clientId: string | null;
}

const REQUEST_DEADLINE_MS = 12_000;
const MAX_JSON_BYTES = 16 * 1024;
type AuthPath =
  | 'config'
  | 'session'
  | 'csrf'
  | 'google/challenge'
  | 'google/exchange'
  | 'logout'
  | 'session/renew'
  | 'account/delete';

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;
const uuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
const instant = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value));

export class BrowserAuthError extends Error {
  constructor(public status: number) {
    super(
      status === 429
        ? 'Too many sign-in attempts. Wait a minute and try again.'
        : status === 401 || status === 403
          ? 'Your sign-in attempt expired. Please start again.'
          : 'Sign-in is temporarily unavailable. Check your connection and try again.',
    );
  }
}

class DeadlineExceededError extends Error {}

interface RequestDeadline {
  readonly signal: AbortSignal;
  readonly expired: boolean;
  race<T>(pending: Promise<T>): Promise<T>;
  throwIfExpired(): void;
  dispose(): void;
}

function createDeadline(): RequestDeadline {
  const controller = new AbortController();
  const expiresAt = Date.now() + REQUEST_DEADLINE_MS;
  let expired = false;
  let rejectTimeout!: (reason: DeadlineExceededError) => void;
  const timeout = new Promise<never>((_, reject) => {
    rejectTimeout = reject;
  });
  // The handler also covers disposal before this promise is ever used in a race.
  void timeout.catch(() => undefined);
  const timer = setTimeout(() => {
    expired = true;
    controller.abort();
    rejectTimeout(new DeadlineExceededError());
  }, REQUEST_DEADLINE_MS);

  const throwIfExpired = () => {
    if (expired || Date.now() >= expiresAt) {
      if (!expired) {
        expired = true;
        controller.abort();
        rejectTimeout(new DeadlineExceededError());
      }
      throw new DeadlineExceededError();
    }
  };

  return {
    signal: controller.signal,
    get expired() {
      return expired;
    },
    async race<T>(pending: Promise<T>): Promise<T> {
      // Observe a transport that rejects after the deadline has already won.
      void pending.catch(() => undefined);
      throwIfExpired();
      return Promise.race([pending, timeout]);
    },
    throwIfExpired,
    dispose() {
      clearTimeout(timer);
    },
  };
}

async function withDeadline<T>(operation: (deadline: RequestDeadline) => Promise<T>): Promise<T> {
  const deadline = createDeadline();
  try {
    return await operation(deadline);
  } catch (error) {
    if (error instanceof BrowserAuthError) throw error;
    throw new BrowserAuthError(503);
  } finally {
    deadline.dispose();
  }
}

function cancelBody(body: ReadableStream<Uint8Array> | null): void {
  if (!body) return;
  try {
    void body.cancel().catch(() => undefined);
  } catch {
    // Cancellation is best-effort and must never extend the request deadline.
  }
}

async function request(
  path: AuthPath,
  deadline: RequestDeadline,
  body?: unknown,
  csrf?: string,
): Promise<Response> {
  deadline.throwIfExpired();
  const encodedBody = body === undefined ? undefined : JSON.stringify(body);
  deadline.throwIfExpired();
  const pending = fetch(`/api/v1/auth/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
    headers:
      body === undefined ? {} : { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf ?? '' },
    ...(encodedBody === undefined ? {} : { body: encodedBody }),
    signal: deadline.signal,
  });
  void pending.then(
    (lateResponse) => {
      if (deadline.expired) cancelBody(lateResponse.body);
    },
    () => undefined,
  );

  const response = await deadline.race(pending);
  try {
    deadline.throwIfExpired();
  } catch (error) {
    cancelBody(response.body);
    throw error;
  }
  if (response.redirected) {
    cancelBody(response.body);
    throw new DeadlineExceededError();
  }
  if (!response.ok && !(path === 'session' && response.status === 401)) {
    cancelBody(response.body);
    throw new BrowserAuthError(response.status);
  }
  return response;
}

function cancelReader(reader: ReadableStreamDefaultReader<Uint8Array>): void {
  try {
    void reader.cancel().catch(() => undefined);
  } catch {
    // A broken stream cannot be allowed to keep an auth operation pending.
  }
}

async function readJson(response: Response, deadline: RequestDeadline): Promise<unknown> {
  if (response.status !== 200) {
    cancelBody(response.body);
    throw new BrowserAuthError(503);
  }
  const contentType = response.headers.get('content-type');
  if (
    !contentType ||
    !/^application\/(?:[a-z0-9!#$&^_.+-]+\+)?json(?:\s*;|\s*$)/i.test(contentType)
  ) {
    cancelBody(response.body);
    throw new BrowserAuthError(503);
  }
  if (!response.body) throw new BrowserAuthError(503);

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  let complete = false;
  try {
    while (true) {
      const next = await deadline.race(reader.read());
      deadline.throwIfExpired();
      if (next.done) {
        complete = true;
        break;
      }
      if (next.value.byteLength === 0) continue;
      byteLength += next.value.byteLength;
      if (byteLength > MAX_JSON_BYTES) throw new BrowserAuthError(503);
      chunks.push(next.value);
    }
  } finally {
    if (!complete) cancelReader(reader);
  }

  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new BrowserAuthError(503);
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new BrowserAuthError(503);
  }
}

async function requestJson(
  path: AuthPath,
  deadline: RequestDeadline,
  body?: unknown,
  csrf?: string,
): Promise<unknown> {
  return readJson(await request(path, deadline, body, csrf), deadline);
}

async function requestNoContent(
  path: AuthPath,
  deadline: RequestDeadline,
  body: unknown,
  csrf: string,
): Promise<void> {
  const response = await request(path, deadline, body, csrf);
  if (response.status !== 204) {
    cancelBody(response.body);
    throw new BrowserAuthError(503);
  }
}

async function readCsrf(deadline: RequestDeadline): Promise<string> {
  const value = await requestJson('csrf', deadline);
  if (
    !record(value) ||
    typeof value.token !== 'string' ||
    value.token.length < 20 ||
    value.token.length > 1024
  )
    throw new BrowserAuthError(503);
  return value.token;
}

export async function authAvailability(): Promise<AuthAvailability> {
  return withDeadline(async (deadline) => {
    const value = await requestJson('config', deadline);
    if (
      !record(value) ||
      typeof value.enabled !== 'boolean' ||
      (value.enabled &&
        (typeof value.clientId !== 'string' ||
          !value.clientId.endsWith('.apps.googleusercontent.com')))
    )
      throw new BrowserAuthError(503);
    return { enabled: value.enabled, clientId: value.enabled ? (value.clientId as string) : null };
  });
}

export async function browserAccount(): Promise<BrowserAccount | null> {
  return withDeadline(async (deadline) => {
    const response = await request('session', deadline);
    if (response.status === 401) {
      cancelBody(response.body);
      return null;
    }
    const value = await readJson(response, deadline);
    if (!record(value) || !uuid(value.accountId)) throw new BrowserAuthError(503);
    return { accountId: value.accountId };
  });
}

export async function browserCsrf(): Promise<string> {
  return withDeadline(readCsrf);
}

export async function beginGoogleLogin(csrf: string): Promise<GoogleChallenge> {
  return withDeadline(async (deadline) => {
    const value = await requestJson('google/challenge', deadline, {}, csrf);
    if (
      !record(value) ||
      !uuid(value.id) ||
      typeof value.nonce !== 'string' ||
      !/^[A-Za-z0-9_-]{43}$/.test(value.nonce) ||
      !instant(value.expiresAt)
    )
      throw new BrowserAuthError(503);
    return { id: value.id, nonce: value.nonce, expiresAt: value.expiresAt };
  });
}

export async function exchangeGoogleLogin(
  challengeId: string,
  idToken: string,
  csrf: string,
): Promise<BrowserSession> {
  return withDeadline(async (deadline) => {
    const value = await requestJson('google/exchange', deadline, { challengeId, idToken }, csrf);
    if (!record(value) || !uuid(value.accountId) || !instant(value.expiresAt))
      throw new BrowserAuthError(503);
    return { accountId: value.accountId, expiresAt: value.expiresAt };
  });
}

export async function logoutBrowser(): Promise<void> {
  return withDeadline(async (deadline) => {
    const csrf = await readCsrf(deadline);
    deadline.throwIfExpired();
    await requestNoContent('logout', deadline, {}, csrf);
  });
}

export async function renewBrowserSession(): Promise<BrowserSession> {
  return withDeadline(async (deadline) => {
    const csrf = await readCsrf(deadline);
    deadline.throwIfExpired();
    const value = await requestJson('session/renew', deadline, {}, csrf);
    if (!record(value) || !uuid(value.accountId) || !instant(value.expiresAt))
      throw new BrowserAuthError(503);
    return { accountId: value.accountId, expiresAt: value.expiresAt };
  });
}

export async function deleteBrowserAccount(accountId: string): Promise<void> {
  return withDeadline(async (deadline) => {
    const body: paths['/api/v1/auth/account/delete']['post']['requestBody']['content']['application/json'] =
      { confirmation: 'DELETE', accountId };
    const csrf = await readCsrf(deadline);
    deadline.throwIfExpired();
    await requestNoContent('account/delete', deadline, body, csrf);
  });
}
