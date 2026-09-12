import { readJourneyInstant, readServerJourney, type ServerJourney } from '@routiqo/shared';
import { BrowserAuthError } from './browser-auth';

export interface JourneyHistoryCursor {
  startedAt: string;
  id: string;
}

export interface JourneyHistoryPage {
  journeys: ServerJourney[];
  next: JourneyHistoryCursor | null;
}

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

function readCursor(value: unknown, requireCanonicalTimestamp: boolean): JourneyHistoryCursor {
  if (
    !record(value) ||
    typeof value.startedAt !== 'string' ||
    typeof value.id !== 'string' ||
    !uuid.test(value.id)
  )
    throw new Error('Invalid journey history cursor.');
  const startedAt = readJourneyInstant(value.startedAt);
  if (requireCanonicalTimestamp && startedAt !== value.startedAt)
    throw new Error('Invalid journey history cursor.');
  return { startedAt, id: value.id };
}

function compareKeys(left: JourneyHistoryCursor, right: JourneyHistoryCursor): number {
  return left.startedAt.localeCompare(right.startedAt) || left.id.localeCompare(right.id);
}

function readChunk(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    let settled = false;
    const cleanUp = () => signal.removeEventListener('abort', abort);
    const abort = () => {
      if (settled) return;
      settled = true;
      cleanUp();
      reject(signal.reason ?? new DOMException('Journey history request cancelled.', 'AbortError'));
    };
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) {
      abort();
      return;
    }
    reader.read().then(
      (chunk) => {
        if (settled) return;
        settled = true;
        cleanUp();
        resolve(chunk);
      },
      (failure: unknown) => {
        if (settled) return;
        settled = true;
        cleanUp();
        reject(failure);
      },
    );
  });
}

async function readBoundedJson(response: Response, signal: AbortSignal): Promise<unknown> {
  if (!response.body) throw new Error('Journey history response has no body.');
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let raw = '';
  let bytes = 0;
  try {
    while (true) {
      const chunk = await readChunk(reader, signal);
      if (chunk.done) break;
      bytes += chunk.value.byteLength;
      if (bytes > 32 * 1024) throw new Error('Journey history exceeds its limit.');
      raw += decoder.decode(chunk.value, { stream: true });
    }
    raw += decoder.decode();
    signal.throwIfAborted();
    return JSON.parse(raw) as unknown;
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function readPage(value: unknown, suppliedCursor: JourneyHistoryCursor | null): JourneyHistoryPage {
  if (
    !record(value) ||
    !Array.isArray(value.journeys) ||
    value.journeys.length > 20 ||
    !('next' in value)
  )
    throw new Error('Invalid journey history.');

  const journeys = value.journeys.map(readServerJourney);
  const identifiers = new Set<string>();
  let previous = suppliedCursor;
  for (const journey of journeys) {
    if (identifiers.has(journey.id)) throw new Error('Duplicate journey history.');
    identifiers.add(journey.id);
    const key = { startedAt: journey.startedAt, id: journey.id };
    if (previous && compareKeys(key, previous) >= 0)
      throw new Error('Journey history is not strictly descending.');
    previous = key;
  }

  if (value.next === null) return { journeys, next: null };
  if (journeys.length !== 20) throw new Error('Invalid journey history cursor.');
  const next = readCursor(value.next, false);
  const last = journeys.at(-1)!;
  if (next.startedAt !== last.startedAt || next.id !== last.id)
    throw new Error('Invalid journey history cursor.');
  return { journeys, next };
}

export async function readBrowserJourneyPage(
  accountId: string,
  cursor: JourneyHistoryCursor | null = null,
  signal?: AbortSignal,
): Promise<JourneyHistoryPage> {
  if (!uuid.test(accountId)) throw new Error('Invalid account identity.');
  const validatedCursor = cursor === null ? null : readCursor(cursor, true);
  const cancellation = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(12000)])
    : AbortSignal.timeout(12000);
  cancellation.throwIfAborted();

  const query = new URLSearchParams({ limit: '20' });
  if (validatedCursor) {
    query.set('beforeStartedAt', validatedCursor.startedAt);
    query.set('beforeId', validatedCursor.id);
  }
  const response = await fetch(`/api/v1/journeys?${query.toString()}`, {
    credentials: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
    headers: { 'X-Routiqo-Account': accountId },
    signal: cancellation,
  });
  if (!response.ok) throw new BrowserAuthError(response.status);
  return readPage(await readBoundedJson(response, cancellation), validatedCursor);
}
