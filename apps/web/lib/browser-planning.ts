import {
  ACCOUNT_PLANNING_MAX_REQUEST_BYTES,
  readAccountPlanning,
  samePlanningContent,
  utf8ByteLength,
  type AccountPlanningCopy,
  type AccountPlanningWrite,
} from '@routiqo/shared';
import { browserCsrf, BrowserAuthError } from './browser-auth';

const OPERATION_DEADLINE_MS = 20_000;
const MAX_RESPONSE_BYTES = 320 * 1024;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export type BrowserPlanningErrorKind =
  'session' | 'conflict' | 'rate' | 'too-large' | 'invalid' | 'unavailable' | 'uncertain';

const messages: Record<BrowserPlanningErrorKind, string> = {
  session: 'Sign in again to use your account copy. Plans on this device are unchanged.',
  conflict:
    'Your account copy changed on another device. Check it again before replacing or removing it.',
  rate: 'Too many account copy changes. Wait a minute and try again.',
  'too-large':
    'These plans are too large to keep on your account. Remove some plans or shorten notes, then try again.',
  invalid:
    'Some plans on this device can’t be kept on your account. Check for unusual characters in plan text.',
  unavailable: 'Your account copy is unavailable right now. Plans on this device are unchanged.',
  uncertain:
    'The change to your account copy wasn’t confirmed. Retry sends exactly the same request.',
};

export class BrowserPlanningError extends Error {
  constructor(public readonly kind: BrowserPlanningErrorKind) {
    super(messages[kind]);
  }
}

class DeadlineError extends Error {}

function statusError(status: number, write: boolean): BrowserPlanningError {
  if (status === 401 || status === 403) return new BrowserPlanningError('session');
  if (status === 409) return new BrowserPlanningError('conflict');
  if (status === 429) return new BrowserPlanningError('rate');
  if (status === 413) return new BrowserPlanningError('too-large');
  if (status === 400 || status === 415) return new BrowserPlanningError('invalid');
  if (status === 404) return new BrowserPlanningError('unavailable');
  return new BrowserPlanningError(write ? 'uncertain' : 'unavailable');
}

function cancelBody(body: ReadableStream<Uint8Array> | null): void {
  try {
    void body?.cancel().catch(() => undefined);
  } catch {
    // Best-effort cleanup must not replace the outcome.
  }
}

async function readBoundedText(response: Response, signal: AbortSignal): Promise<string> {
  const contentType = response.headers.get('content-type') ?? '';
  if (
    !/^application\/(?:[a-z0-9!#$&^_.+-]+\+)?json(?:\s*;|\s*$)/i.test(contentType) ||
    !response.body
  ) {
    cancelBody(response.body);
    throw new Error('Unexpected response.');
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  let complete = false;
  const abort = () => void reader.cancel().catch(() => undefined);
  signal.addEventListener('abort', abort, { once: true });
  try {
    while (true) {
      if (signal.aborted) throw new DeadlineError();
      const next = await reader.read();
      if (signal.aborted) throw new DeadlineError();
      if (next.done) {
        complete = true;
        break;
      }
      size += next.value.byteLength;
      if (size > MAX_RESPONSE_BYTES) throw new Error('Response too large.');
      chunks.push(next.value);
    }
  } finally {
    signal.removeEventListener('abort', abort);
    if (!complete) void reader.cancel().catch(() => undefined);
    try {
      reader.releaseLock();
    } catch {
      // A pending read may keep the lock; cancellation above already releases resources.
    }
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

interface Operation {
  method: 'GET' | 'POST';
  path: 'planning' | 'planning/delete';
  body?: string;
}

async function send<T>(
  accountId: string,
  operation: Operation,
  callerSignal: AbortSignal | undefined,
  accept: (response: Response, signal: AbortSignal) => Promise<T>,
): Promise<T> {
  if (!uuid.test(accountId)) throw new Error('Invalid account.');
  const write = operation.method === 'POST';
  const controller = new AbortController();
  let expired = false;
  const timer = setTimeout(() => {
    expired = true;
    controller.abort();
  }, OPERATION_DEADLINE_MS);
  const onAbort = () => controller.abort();
  callerSignal?.addEventListener('abort', onAbort, { once: true });
  if (callerSignal?.aborted) controller.abort();
  const cancelled = () => Boolean(callerSignal?.aborted) && !expired;
  try {
    const headers: Record<string, string> = { 'X-Routiqo-Account': accountId };
    if (write) {
      const csrf = await browserCsrf();
      if (controller.signal.aborted) throw new DeadlineError();
      headers['Content-Type'] = 'application/json';
      headers['X-XSRF-TOKEN'] = csrf;
    }
    const response = await fetch(`/api/v1/${operation.path}`, {
      method: operation.method,
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers,
      ...(operation.body === undefined ? {} : { body: operation.body }),
      signal: controller.signal,
    });
    if (controller.signal.aborted) {
      cancelBody(response.body);
      throw new DeadlineError();
    }
    if (response.redirected || !response.ok) {
      cancelBody(response.body);
      throw response.redirected
        ? new BrowserPlanningError(write ? 'uncertain' : 'unavailable')
        : statusError(response.status, write);
    }
    return await accept(response, controller.signal);
  } catch (failure) {
    if (cancelled()) throw new DOMException('Account copy request cancelled.', 'AbortError');
    if (failure instanceof BrowserPlanningError) throw failure;
    if (failure instanceof BrowserAuthError) {
      // CSRF could not be obtained, so nothing was sent.
      throw failure.status === 401 || failure.status === 403
        ? new BrowserPlanningError('session')
        : new BrowserPlanningError(failure.status === 429 ? 'rate' : 'unavailable');
    }
    throw new BrowserPlanningError(write ? 'uncertain' : 'unavailable');
  } finally {
    clearTimeout(timer);
    callerSignal?.removeEventListener('abort', onAbort);
  }
}

async function copyFrom(response: Response, signal: AbortSignal): Promise<AccountPlanningCopy> {
  if (response.status !== 200) {
    cancelBody(response.body);
    throw new Error('Unexpected status.');
  }
  return readAccountPlanning(JSON.parse(await readBoundedText(response, signal)) as unknown);
}

/** Explicit read of the verified account's copy. Version 0 means there is none. */
export function readBrowserAccountPlanning(
  accountId: string,
  signal?: AbortSignal,
): Promise<AccountPlanningCopy> {
  return send(accountId, { method: 'GET', path: 'planning' }, signal, copyFrom);
}

/**
 * Sends one exact write. The caller keeps `write` unchanged for an explicit retry after an
 * uncertain outcome; the server replays an identical mutation without creating a new version.
 */
export function saveBrowserAccountPlanning(
  accountId: string,
  write: AccountPlanningWrite,
  signal?: AbortSignal,
): Promise<AccountPlanningCopy> {
  const body = JSON.stringify(write);
  if (utf8ByteLength(body) > ACCOUNT_PLANNING_MAX_REQUEST_BYTES)
    return Promise.reject(new BrowserPlanningError('too-large'));
  return send(
    accountId,
    { method: 'POST', path: 'planning', body },
    signal,
    async (response, s) => {
      let copy: AccountPlanningCopy;
      try {
        copy = await copyFrom(response, s);
      } catch (failure) {
        if (failure instanceof DeadlineError) throw failure;
        throw new BrowserPlanningError('uncertain');
      }
      const sent = { version: 1 as const, plans: write.plans, saved: write.saved };
      if (copy.version !== write.expectedVersion + 1 || !samePlanningContent(copy.state, sent))
        throw new BrowserPlanningError('uncertain');
      return copy;
    },
  );
}

export function deleteBrowserAccountPlanning(
  accountId: string,
  expectedVersion: number,
  signal?: AbortSignal,
): Promise<void> {
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 1)
    return Promise.reject(new Error('Invalid account copy version.'));
  return send(
    accountId,
    { method: 'POST', path: 'planning/delete', body: JSON.stringify({ expectedVersion }) },
    signal,
    async (response) => {
      cancelBody(response.body);
      if (response.status !== 204) throw new BrowserPlanningError('uncertain');
    },
  );
}
