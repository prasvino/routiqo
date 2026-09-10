import type { paths } from '@routiqo/api-client';
import {
  readRouteRequest,
  readRouteResult,
  readPlaceQuery,
  readPlaceResults,
} from '@routiqo/shared';
import { browserCsrf, BrowserAuthError } from './browser-auth';

export class BrowserRoutingError extends Error {
  constructor(public readonly status: number) {
    super(
      status === 401 || status === 403
        ? 'Sign in again to calculate a route.'
        : status === 429
          ? 'Too many route requests. Wait a minute and try again.'
          : 'Routing is unavailable. Check your connection and try again.',
    );
  }
}

/** Explicit private calculation only: no storage, automatic retries or location watching. */
export async function calculateBrowserRoute(
  accountId: string,
  input: unknown,
  signal?: AbortSignal,
) {
  const body: paths['/api/v1/routes']['post']['requestBody']['content']['application/json'] =
    readRouteRequest(input);
  return privateRequest(accountId, '/api/v1/routes', body, readRouteResult, 1048576, signal);
}
export async function searchBrowserPlaces(accountId: string, input: unknown, signal?: AbortSignal) {
  const body: paths['/api/v1/routes/places']['post']['requestBody']['content']['application/json'] =
    {
      query: readPlaceQuery(input),
    };
  return privateRequest(accountId, '/api/v1/routes/places', body, readPlaceResults, 262144, signal);
}
async function privateRequest<T>(
  accountId: string,
  path: '/api/v1/routes' | '/api/v1/routes/places',
  body: unknown,
  validate: (value: unknown) => T,
  limit: number,
  signal?: AbortSignal,
): Promise<T> {
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(accountId))
    throw new Error('Invalid account identity.');
  const cancellation = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(18000)])
    : AbortSignal.timeout(18000);
  try {
    cancellation.throwIfAborted();
    const csrf = await browserCsrf();
    cancellation.throwIfAborted();
    const response = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrf,
        'X-Routiqo-Account': accountId,
      },
      body: JSON.stringify(body),
      signal: cancellation,
    });
    if (!response.ok) throw new BrowserRoutingError(response.status);
    if (!response.body) throw new BrowserRoutingError(503);
    const reader = response.body.getReader();
    let bytes = 0;
    const decoder = new TextDecoder('utf-8', { fatal: true });
    let raw = '';
    try {
      while (true) {
        cancellation.throwIfAborted();
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.byteLength;
        if (bytes > limit) throw new BrowserRoutingError(503);
        raw += decoder.decode(chunk.value, { stream: true });
      }
      raw += decoder.decode();
      cancellation.throwIfAborted();
      return validate(JSON.parse(raw));
    } finally {
      await reader.cancel().catch(() => undefined);
      reader.releaseLock();
    }
  } catch (error) {
    if (signal?.aborted) throw new DOMException('Route request cancelled.', 'AbortError');
    if (error instanceof BrowserRoutingError) throw error;
    if (error instanceof BrowserAuthError) throw new BrowserRoutingError(error.status);
    throw new BrowserRoutingError(503);
  }
}
