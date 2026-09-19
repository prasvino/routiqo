import type { paths } from '@routiqo/api-client';
import { readServerJourney, type JourneyCommand, type JourneyDelivery } from '@routiqo/shared';
import { browserCsrf, BrowserAuthError } from './browser-auth';

const requestLifetimeMilliseconds = 12_000;
const journeyLimit = 4 * 1024;
const historyLimit = 32 * 1024;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

class JourneyDeadlineError extends Error {}
class JourneyMismatchError extends Error {}

interface JourneyDeadline {
  readonly signal: AbortSignal;
  readonly expired: boolean;
  race<T>(operation: Promise<T>): Promise<T>;
  throwIfExpired(): void;
  dispose(): void;
}

function createDeadline(): JourneyDeadline {
  const controller = new AbortController();
  const expiresAt = Date.now() + requestLifetimeMilliseconds;
  let expired = false;
  let rejectTimeout!: (error: JourneyDeadlineError) => void;
  const timeout = new Promise<never>((_resolve, reject) => {
    rejectTimeout = reject;
  });
  void timeout.catch(() => undefined);
  const expire = () => {
    if (expired) return;
    expired = true;
    controller.abort();
    rejectTimeout(new JourneyDeadlineError());
  };
  const timer = setTimeout(expire, requestLifetimeMilliseconds);
  const throwIfExpired = () => {
    if (expired || Date.now() >= expiresAt) {
      expire();
      throw new JourneyDeadlineError();
    }
  };
  return {
    signal: controller.signal,
    get expired() {
      return expired;
    },
    race<T>(operation: Promise<T>): Promise<T> {
      void operation.catch(() => undefined);
      throwIfExpired();
      return Promise.race([operation, timeout]);
    },
    throwIfExpired,
    dispose() {
      clearTimeout(timer);
    },
  };
}

function cancelBody(body: ReadableStream<Uint8Array> | null): void {
  if (!body) return;
  try {
    void body.cancel().catch(() => undefined);
  } catch {
    // Cleanup is best effort and must never extend a transport operation.
  }
}

function cancelReader(reader: ReadableStreamDefaultReader<Uint8Array>): void {
  const release = () => {
    try {
      reader.releaseLock();
    } catch {
      // A pending read may retain its lock until its underlying source settles.
    }
  };
  try {
    void reader.cancel().then(release, release);
  } catch {
    // A hostile stream cannot be allowed to change the redacted result.
  }
  release();
}

async function fetchResponse(
  path: string,
  init: RequestInit,
  deadline: JourneyDeadline,
): Promise<Response> {
  deadline.throwIfExpired();
  const pending = fetch(path, { ...init, signal: deadline.signal });
  void pending.then(
    (lateResponse) => {
      if (deadline.expired) cancelBody(lateResponse.body);
    },
    () => undefined,
  );
  const response = await deadline.race(pending);
  try {
    deadline.throwIfExpired();
  } catch (failure) {
    cancelBody(response.body);
    throw failure;
  }
  if (response.redirected) {
    cancelBody(response.body);
    throw new JourneyDeadlineError();
  }
  return response;
}

async function readBoundedJson(
  response: Response,
  limit: number,
  deadline: JourneyDeadline,
): Promise<unknown> {
  if (response.status !== 200) {
    cancelBody(response.body);
    throw new Error('Journey response is invalid.');
  }
  const contentType = response.headers.get('content-type');
  if (
    !contentType ||
    !/^application\/(?:[a-z0-9!#$&^_.+-]+\+)?json(?:\s*;|\s*$)/i.test(contentType) ||
    !response.body
  ) {
    cancelBody(response.body);
    throw new Error('Journey response is invalid.');
  }
  let reader: ReadableStreamDefaultReader<Uint8Array>;
  try {
    reader = response.body.getReader();
  } catch {
    cancelBody(response.body);
    throw new Error('Journey response is invalid.');
  }
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let raw = '';
  let bytes = 0;
  let complete = false;
  try {
    while (true) {
      deadline.throwIfExpired();
      const chunk = await deadline.race(reader.read());
      deadline.throwIfExpired();
      if (chunk.done) {
        complete = true;
        break;
      }
      if (chunk.value.byteLength === 0) continue;
      bytes += chunk.value.byteLength;
      if (bytes > limit) throw new Error('Journey response exceeds its limit.');
      raw += decoder.decode(chunk.value, { stream: true });
    }
    raw += decoder.decode();
  } finally {
    if (!complete) cancelReader(reader);
    else reader.releaseLock();
  }
  deadline.throwIfExpired();
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    throw new Error('Journey response is invalid.');
  }
}

function responseError(response: Response): BrowserAuthError {
  cancelBody(response.body);
  return new BrowserAuthError(response.status);
}

/** Used by durable dispatch, never by local planning draft operations. */
export async function sendBrowserJourney(
  accountId: string,
  command: JourneyCommand,
): Promise<JourneyDelivery> {
  if (
    !uuid.test(accountId) ||
    !uuid.test(command.journeyId) ||
    (command.action !== 'complete' &&
      (command.action !== 'start' || !['trip', 'commute'].includes(command.kind)))
  )
    return { outcome: 'rejected' };

  const journeyId = command.journeyId;
  const action = command.action;
  const expectedKind: 'trip' | 'commute' | undefined =
    action === 'start' ? command.kind : undefined;
  const path = action === 'start' ? '/api/v1/journeys' : `/api/v1/journeys/${journeyId}/complete`;
  const start:
    paths['/api/v1/journeys']['post']['requestBody']['content']['application/json'] | undefined =
    expectedKind === undefined ? undefined : { id: journeyId, kind: expectedKind };
  const body = JSON.stringify(start ?? {});
  const deadline = createDeadline();
  try {
    const csrf = await deadline.race(browserCsrf());
    deadline.throwIfExpired();
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
        body,
      },
      deadline,
    );
    if (response.status === 401 || response.status === 403) {
      cancelBody(response.body);
      return { outcome: 'authentication' };
    }
    if (response.status === 409) {
      cancelBody(response.body);
      return { outcome: 'conflict' };
    }
    if (response.status === 429 || response.status === 408 || response.status >= 500) {
      cancelBody(response.body);
      return { outcome: 'transient' };
    }
    if (response.status !== 200) {
      cancelBody(response.body);
      return { outcome: 'rejected' };
    }
    let journey;
    try {
      journey = readServerJourney(await readBoundedJson(response, journeyLimit, deadline));
    } catch {
      return { outcome: 'transient' };
    }
    deadline.throwIfExpired();
    if (
      journey.id !== journeyId ||
      (action === 'start' && journey.kind !== expectedKind) ||
      (action === 'complete' && journey.status !== 'completed')
    )
      return { outcome: 'transient' };
    return { outcome: 'success', journey };
  } catch (failure) {
    if (failure instanceof BrowserAuthError && (failure.status === 401 || failure.status === 403))
      return { outcome: 'authentication' };
    return { outcome: 'transient' };
  } finally {
    deadline.dispose();
  }
}

/** Owner-bound lookup for reconciliation; missing is not proof that queued work should be removed. */
export async function readBrowserJourney(accountId: string, journeyId: string) {
  if (!uuid.test(accountId) || !uuid.test(journeyId)) throw new Error('Invalid journey identity.');
  const path = `/api/v1/journeys/${journeyId}`;
  const expectedId = journeyId;
  const deadline = createDeadline();
  try {
    const response = await fetchResponse(
      path,
      {
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: { 'X-Routiqo-Account': accountId },
      },
      deadline,
    );
    if (response.status === 404) {
      cancelBody(response.body);
      return null;
    }
    if (response.status !== 200) throw responseError(response);
    let journey;
    try {
      journey = readServerJourney(await readBoundedJson(response, journeyLimit, deadline));
    } catch {
      throw new Error('Journey response is invalid.');
    }
    deadline.throwIfExpired();
    if (journey.id !== expectedId) throw new JourneyMismatchError();
    return journey;
  } catch (failure) {
    if (failure instanceof BrowserAuthError) throw failure;
    if (failure instanceof JourneyMismatchError)
      throw new Error('Journey response does not match the request.');
    throw new Error('Journey request is unavailable.');
  } finally {
    deadline.dispose();
  }
}

export async function readRecentBrowserJourneys(accountId: string) {
  if (!uuid.test(accountId)) throw new Error('Invalid account identity.');
  const deadline = createDeadline();
  try {
    const response = await fetchResponse(
      '/api/v1/journeys?limit=20',
      {
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: { 'X-Routiqo-Account': accountId },
      },
      deadline,
    );
    if (response.status !== 200) throw responseError(response);
    const value = await readBoundedJson(response, historyLimit, deadline);
    if (
      typeof value !== 'object' ||
      value === null ||
      !('journeys' in value) ||
      !Array.isArray(value.journeys) ||
      value.journeys.length > 20
    )
      throw new Error('Invalid journey history.');
    const journeys = value.journeys.map(readServerJourney);
    if (new Set(journeys.map((journey) => journey.id)).size !== journeys.length)
      throw new Error('Duplicate journey history.');
    deadline.throwIfExpired();
    return journeys;
  } catch (failure) {
    if (failure instanceof BrowserAuthError) throw failure;
    throw new Error('Journey history is unavailable.');
  } finally {
    deadline.dispose();
  }
}
