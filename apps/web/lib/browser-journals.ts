import { readTripJournal, readTripJournalWrite, type TripJournalWrite } from '@routiqo/shared';
import { browserCsrf, BrowserAuthError } from './browser-auth';

const OPERATION_DEADLINE_MS = 18_000;
const MAX_RESPONSE_BYTES = 32 * 1024;

export class BrowserJournalError extends Error {
  constructor(public readonly status: number) {
    super(
      status === 409
        ? 'This journal changed or is not available for editing. Review the latest version.'
        : status === 401 || status === 403
          ? 'Sign in again to open this journal.'
          : status === 404
            ? 'This trip journal could not be found.'
            : status === 429
              ? 'Too many journal saves. Wait a minute and try again.'
              : 'The journal is unavailable. Any attempted account save is unconfirmed.',
    );
  }
}

class OperationCancelledError extends Error {}
class OperationDeadlineError extends Error {}

interface OperationDeadline {
  readonly signal: AbortSignal;
  readonly stopped: boolean;
  readonly cancelled: boolean;
  checkpoint(): void;
  race<T>(pending: Promise<T>): Promise<T>;
  dispose(): void;
}

function createOperationDeadline(callerSignal?: AbortSignal): OperationDeadline {
  const controller = new AbortController();
  const expiresAt = Date.now() + OPERATION_DEADLINE_MS;
  let state: 'active' | 'cancelled' | 'expired' = 'active';
  let rejectStop!: (reason: OperationCancelledError | OperationDeadlineError) => void;
  const stopped = new Promise<never>((_, reject) => {
    rejectStop = reject;
  });
  void stopped.catch(() => undefined);

  const stop = (next: 'cancelled' | 'expired') => {
    if (state !== 'active') return;
    state = next;
    controller.abort();
    rejectStop(next === 'cancelled' ? new OperationCancelledError() : new OperationDeadlineError());
  };
  const onCallerAbort = () => stop('cancelled');

  // Install the handler before checking for an already-aborted caller signal.
  callerSignal?.addEventListener('abort', onCallerAbort, { once: true });
  if (callerSignal?.aborted) onCallerAbort();
  const timer = setTimeout(() => stop('expired'), OPERATION_DEADLINE_MS);

  const checkpoint = () => {
    if (state === 'active' && Date.now() >= expiresAt) stop('expired');
    if (state === 'cancelled') throw new OperationCancelledError();
    if (state === 'expired') throw new OperationDeadlineError();
  };

  return {
    signal: controller.signal,
    get stopped() {
      return state !== 'active';
    },
    get cancelled() {
      return state === 'cancelled';
    },
    checkpoint,
    async race<T>(pending: Promise<T>): Promise<T> {
      // Observe failures even when cancellation, timeout, or an elapsed check wins first.
      void pending.catch(() => undefined);
      checkpoint();
      return Promise.race([pending, stopped]);
    },
    dispose() {
      clearTimeout(timer);
      callerSignal?.removeEventListener('abort', onCallerAbort);
    },
  };
}

function cancelBody(body: ReadableStream<Uint8Array> | null): void {
  if (!body) return;
  try {
    void body.cancel().catch(() => undefined);
  } catch {
    // Cleanup is best-effort and must not replace the transport outcome.
  }
}

function cancelReader(reader: ReadableStreamDefaultReader<Uint8Array>): void {
  try {
    void reader.cancel().then(
      () => releaseReader(reader),
      () => releaseReader(reader),
    );
  } catch {
    // Cleanup is best-effort and must not extend the operation deadline.
  }
}

function releaseReader(reader: ReadableStreamDefaultReader<Uint8Array>): void {
  try {
    reader.releaseLock();
  } catch {
    // A pending read or broken injected stream may prevent releasing the lock.
  }
}

export function readBrowserTripJournal(accountId: string, journeyId: string, signal?: AbortSignal) {
  return request(accountId, journeyId, undefined, signal);
}

/** Caller must durably retain this mutation before invoking transport; no automatic retry or storage here. */
export function saveBrowserTripJournal(
  accountId: string,
  journeyId: string,
  input: unknown,
  signal?: AbortSignal,
) {
  return request(accountId, journeyId, readTripJournalWrite(input), signal);
}

async function readJson(response: Response, deadline: OperationDeadline): Promise<unknown> {
  if (response.status !== 200) {
    cancelBody(response.body);
    throw new BrowserJournalError(503);
  }
  const contentType = response.headers.get('content-type');
  if (
    !contentType ||
    !/^application\/(?:[a-z0-9!#$&^_.+-]+\+)?json(?:\s*;|\s*$)/i.test(contentType)
  ) {
    cancelBody(response.body);
    throw new BrowserJournalError(503);
  }
  if (!response.body) throw new BrowserJournalError(503);

  let reader: ReadableStreamDefaultReader<Uint8Array>;
  try {
    reader = response.body.getReader();
  } catch {
    cancelBody(response.body);
    throw new BrowserJournalError(503);
  }
  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  let complete = false;
  try {
    while (true) {
      deadline.checkpoint();
      const next = await deadline.race(reader.read());
      deadline.checkpoint();
      if (next.done) {
        complete = true;
        break;
      }
      if (next.value.byteLength === 0) continue;
      byteLength += next.value.byteLength;
      if (byteLength > MAX_RESPONSE_BYTES) throw new BrowserJournalError(503);
      chunks.push(next.value);
    }
  } finally {
    if (!complete) cancelReader(reader);
    releaseReader(reader);
  }

  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  let raw: string;
  try {
    raw = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new BrowserJournalError(503);
  }
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    throw new BrowserJournalError(503);
  }
}

async function request(
  accountId: string,
  journeyId: string,
  edit?: TripJournalWrite,
  callerSignal?: AbortSignal,
) {
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
  if (!uuid.test(accountId) || !uuid.test(journeyId)) throw new Error('Invalid journal identity.');

  const deadline = createOperationDeadline(callerSignal);
  try {
    // Snapshot the validated mutation before any asynchronous CSRF work.
    const serializedEdit = edit === undefined ? undefined : JSON.stringify(edit);
    deadline.checkpoint();

    const headers: Record<string, string> = { 'X-Routiqo-Account': accountId };
    if (serializedEdit !== undefined) {
      const csrf = await deadline.race(browserCsrf());
      deadline.checkpoint();
      headers['Content-Type'] = 'application/json';
      headers['X-XSRF-TOKEN'] = csrf;
    }
    deadline.checkpoint();

    const pending = fetch(`/api/v1/journeys/${journeyId}/journal`, {
      method: serializedEdit === undefined ? 'GET' : 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers,
      ...(serializedEdit === undefined ? {} : { body: serializedEdit }),
      signal: deadline.signal,
    });
    void pending.then(
      (lateResponse) => {
        if (deadline.stopped) cancelBody(lateResponse.body);
      },
      () => undefined,
    );

    const response = await deadline.race(pending);
    try {
      deadline.checkpoint();
    } catch (failure) {
      cancelBody(response.body);
      throw failure;
    }
    if (response.redirected) {
      cancelBody(response.body);
      throw new BrowserJournalError(503);
    }
    if (!response.ok) {
      cancelBody(response.body);
      throw new BrowserJournalError(response.status);
    }

    const value = await readJson(response, deadline);
    deadline.checkpoint();
    const journal = readTripJournal(value);
    deadline.checkpoint();
    if (
      journal.journey.id !== journeyId ||
      (edit &&
        (journal.annotation.title !== edit.title ||
          journal.annotation.notes !== edit.notes ||
          journal.annotation.version !== edit.expectedVersion + 1))
    )
      throw new BrowserJournalError(503);
    return journal;
  } catch (failure) {
    if (deadline.cancelled || callerSignal?.aborted)
      throw new DOMException('Journal request cancelled.', 'AbortError');
    if (failure instanceof BrowserJournalError) throw failure;
    if (failure instanceof BrowserAuthError) throw new BrowserJournalError(failure.status);
    throw new BrowserJournalError(503);
  } finally {
    deadline.dispose();
  }
}
