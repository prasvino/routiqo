import type { paths } from '@routiqo/api-client';
import {
  readRouteRequest,
  readRouteResult,
  readPlaceQuery,
  readPlaceResults,
} from '@routiqo/shared';
import { browserCsrf, BrowserAuthError } from './browser-auth';

const requestLifetimeMilliseconds = 18_000;
const accountPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export const routingCoverageMessage =
  'This route is outside the supported routing area. Choose different places and try again.';

export class BrowserRoutingError extends Error {
  constructor(public readonly status: number) {
    super(
      status === 401 || status === 403
        ? 'Sign in again to calculate a route.'
        : status === 422
          ? routingCoverageMessage
          : status === 429
            ? 'Too many route requests. Wait a minute and try again.'
            : 'Routing is unavailable. Check your connection and try again.',
    );
  }
}

class RoutingDeadlineError extends Error {}

interface RoutingDeadline {
  readonly signal: AbortSignal;
  race<T>(pending: Promise<T>, discard?: (value: T) => void): Promise<T>;
  throwIfInactive(): void;
  dispose(): void;
}

function createDeadline(callerSignal?: AbortSignal): RoutingDeadline {
  const controller = new AbortController();
  const expiresAt = Date.now() + requestLifetimeMilliseconds;
  let deadlineExpired = false;
  let callerAborted = false;
  let rejectInterruption!: (failure: unknown) => void;
  const interruption = new Promise<never>((_resolve, reject) => {
    rejectInterruption = reject;
  });
  void interruption.catch(() => undefined);

  const abortForCaller = () => {
    if (callerAborted) return;
    callerAborted = true;
    const failure = new DOMException('Route request cancelled.', 'AbortError');
    controller.abort(failure);
    rejectInterruption(failure);
  };
  const expire = () => {
    if (deadlineExpired || callerAborted) return;
    deadlineExpired = true;
    const failure = new RoutingDeadlineError();
    controller.abort(failure);
    rejectInterruption(failure);
  };
  const timer = setTimeout(expire, requestLifetimeMilliseconds);
  callerSignal?.addEventListener('abort', abortForCaller, { once: true });
  if (callerSignal?.aborted) abortForCaller();

  const throwIfInactive = () => {
    if (callerSignal?.aborted || callerAborted) {
      abortForCaller();
      throw new DOMException('Route request cancelled.', 'AbortError');
    }
    if (deadlineExpired || Date.now() >= expiresAt) {
      expire();
      throw new RoutingDeadlineError();
    }
  };

  return {
    signal: controller.signal,
    race<T>(pending: Promise<T>, discard?: (value: T) => void): Promise<T> {
      void pending.then(
        (value) => {
          if (callerAborted || deadlineExpired) discard?.(value);
        },
        () => undefined,
      );
      throwIfInactive();
      return Promise.race([pending, interruption]);
    },
    throwIfInactive,
    dispose() {
      clearTimeout(timer);
      callerSignal?.removeEventListener('abort', abortForCaller);
    },
  };
}

function cancelBody(body: ReadableStream<Uint8Array> | null): void {
  if (!body) return;
  try {
    void body.cancel().catch(() => undefined);
  } catch {
    // Cleanup is best effort and must not affect the redacted request result.
  }
}

function cancelReader(reader: ReadableStreamDefaultReader<Uint8Array>): void {
  const release = () => {
    try {
      reader.releaseLock();
    } catch {
      // A pending read can retain its lock until the underlying source settles.
    }
  };
  try {
    void reader.cancel().then(release, release);
  } catch {
    // A hostile stream cannot be allowed to extend the transport operation.
  }
  release();
}

function contentLengthExceeds(response: Response, limit: number): boolean {
  const declared = response.headers.get('content-length');
  if (declared === null) return false;
  if (!/^\d+$/.test(declared)) return true;
  try {
    return BigInt(declared) > BigInt(limit);
  } catch {
    return true;
  }
}

async function fetchResponse(
  path: '/api/v1/routes' | '/api/v1/routes/places',
  init: RequestInit,
  deadline: RoutingDeadline,
): Promise<Response> {
  deadline.throwIfInactive();
  const pending = fetch(path, { ...init, signal: deadline.signal });
  const response = await deadline.race(pending, (late) => cancelBody(late.body));
  try {
    deadline.throwIfInactive();
  } catch (failure) {
    cancelBody(response.body);
    throw failure;
  }
  return response;
}

function responseFailure(response: Response): BrowserRoutingError {
  cancelBody(response.body);
  if (response.redirected || response.status < 400 || response.status > 599)
    return new BrowserRoutingError(503);
  return new BrowserRoutingError(response.status);
}

async function readBoundedJson(
  response: Response,
  limit: number,
  deadline: RoutingDeadline,
): Promise<unknown> {
  if (response.status !== 200 || response.redirected) throw responseFailure(response);
  const contentType = response.headers.get('content-type');
  if (
    !contentType ||
    !/^application\/(?:[a-z0-9!#$&^_.+-]+\+)?json(?:\s*;|\s*$)/i.test(contentType) ||
    !response.body ||
    contentLengthExceeds(response, limit)
  ) {
    cancelBody(response.body);
    throw new BrowserRoutingError(503);
  }

  let reader: ReadableStreamDefaultReader<Uint8Array>;
  try {
    reader = response.body.getReader();
  } catch {
    cancelBody(response.body);
    throw new BrowserRoutingError(503);
  }
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let raw = '';
  let bytes = 0;
  let complete = false;
  try {
    while (true) {
      deadline.throwIfInactive();
      const chunk = await deadline.race(reader.read());
      deadline.throwIfInactive();
      if (chunk.done) {
        complete = true;
        break;
      }
      bytes += chunk.value.byteLength;
      if (bytes > limit) throw new BrowserRoutingError(503);
      if (chunk.value.byteLength > 0) raw += decoder.decode(chunk.value, { stream: true });
    }
    raw += decoder.decode();
  } finally {
    if (complete) reader.releaseLock();
    else cancelReader(reader);
  }
  deadline.throwIfInactive();
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    throw new BrowserRoutingError(503);
  }
}

/** Explicit private calculation only: no storage, automatic retries or location watching. */
export async function calculateBrowserRoute(
  accountId: string,
  input: unknown,
  signal?: AbortSignal,
) {
  if (!accountPattern.test(accountId)) throw new Error('Invalid account identity.');
  const body: paths['/api/v1/routes']['post']['requestBody']['content']['application/json'] =
    readRouteRequest(input);
  return privateRequest(
    accountId,
    '/api/v1/routes',
    JSON.stringify(body),
    readRouteResult,
    1024 * 1024,
    signal,
  );
}

export async function searchBrowserPlaces(accountId: string, input: unknown, signal?: AbortSignal) {
  if (!accountPattern.test(accountId)) throw new Error('Invalid account identity.');
  const body: paths['/api/v1/routes/places']['post']['requestBody']['content']['application/json'] =
    {
      query: readPlaceQuery(input),
    };
  return privateRequest(
    accountId,
    '/api/v1/routes/places',
    JSON.stringify(body),
    readPlaceResults,
    256 * 1024,
    signal,
  );
}

async function privateRequest<T>(
  accountId: string,
  path: '/api/v1/routes' | '/api/v1/routes/places',
  serializedBody: string,
  validate: (value: unknown) => T,
  limit: number,
  signal?: AbortSignal,
): Promise<T> {
  const deadline = createDeadline(signal);
  try {
    deadline.throwIfInactive();
    const csrf = await deadline.race(browserCsrf());
    deadline.throwIfInactive();
    const response = await fetchResponse(
      path,
      {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': csrf,
          'X-Routiqo-Account': accountId,
        },
        body: serializedBody,
      },
      deadline,
    );
    const raw = await readBoundedJson(response, limit, deadline);
    let value: T;
    try {
      value = validate(raw);
    } catch {
      throw new BrowserRoutingError(503);
    }
    deadline.throwIfInactive();
    return value;
  } catch (error) {
    if (signal?.aborted) throw new DOMException('Route request cancelled.', 'AbortError');
    if (error instanceof BrowserRoutingError) throw error;
    if (error instanceof BrowserAuthError) throw new BrowserRoutingError(error.status);
    throw new BrowserRoutingError(503);
  } finally {
    deadline.dispose();
  }
}
