import { afterEach, expect, it, vi } from 'vitest';
import {
  BrowserRoutingError,
  calculateBrowserRoute,
  routingCoverageMessage,
  searchBrowserPlaces,
} from '../apps/web/lib/browser-routing';
const account = '00000000-0000-4000-8000-000000000001';
const request = { mode: 'walking', origin: [77, 12], destination: [77.1, 12.1] };
const result = {
  provider: 'mapbox',
  calculatedAt: '2026-09-09T12:00:00Z',
  routes: [
    {
      distanceMetres: 1200,
      durationSeconds: 600,
      geometry: [
        [77, 12],
        [77.1, 12.1],
      ],
    },
  ],
};
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (failure: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function csrfResponse() {
  return Response.json({ token: 'synthetic-csrf-token-for-routing-tests' });
}
function respond(response: Response) {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token: 'synthetic-csrf-token-for-routing-tests' }))
    .mockResolvedValueOnce(response);
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}
it('posts an account-bound private calculation and strips unrelated response fields', async () => {
  const fetcher = respond(Response.json({ ...result, private: 'discard' }));
  expect(await calculateBrowserRoute(account, request)).toEqual({
    ...result,
    routes: result.routes.map((route) => ({ ...route, steps: [] })),
  });
  expect(fetcher).toHaveBeenLastCalledWith(
    '/api/v1/routes',
    expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: expect.objectContaining({ 'X-Routiqo-Account': account }),
      body: JSON.stringify(request),
    }),
  );
});
it('recognizes coverage failures without consuming or exposing provider details', async () => {
  const cancel = vi.fn();
  respond(
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('private upstream details'));
        },
        cancel,
      }),
      { status: 422 },
    ),
  );
  await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({
    status: 422,
    message: routingCoverageMessage,
  });
  expect(cancel).toHaveBeenCalledOnce();
});
it('ignores rejected error-body cancellation and preserves the safe status result', async () => {
  const cancel = vi.fn(() => Promise.reject(new Error('private cancellation failure')));
  respond(new Response(new ReadableStream({ start() {}, cancel }), { status: 429 }));
  await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({
    status: 429,
    message: 'Too many route requests. Wait a minute and try again.',
  });
  await Promise.resolve();
  expect(cancel).toHaveBeenCalledOnce();
});
it('rejects invalid endpoints before making any request and honors pre-cancellation', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  await expect(calculateBrowserRoute(account, { ...request, origin: [77, 91] })).rejects.toThrow();
  await expect(calculateBrowserRoute(account, request, AbortSignal.abort())).rejects.toMatchObject({
    name: 'AbortError',
  });
  expect(fetcher).not.toHaveBeenCalled();
});
it('does not post after cancellation while obtaining CSRF', async () => {
  const controller = new AbortController();
  const fetcher = vi.fn(async () => {
    controller.abort();
    return Response.json({ token: 'synthetic-csrf-token-for-routing-tests' });
  });
  vi.stubGlobal('fetch', fetcher);
  await expect(calculateBrowserRoute(account, request, controller.signal)).rejects.toMatchObject({
    name: 'AbortError',
  });
  expect(fetcher).toHaveBeenCalledTimes(1);
});
it('snapshots coordinates and search text before waiting for CSRF', async () => {
  const routeCsrf = deferred<Response>();
  const routeFetcher = vi
    .fn<typeof fetch>()
    .mockReturnValueOnce(routeCsrf.promise)
    .mockResolvedValueOnce(Response.json(result));
  vi.stubGlobal('fetch', routeFetcher);
  const mutable = {
    ...request,
    origin: [...request.origin],
    destination: [...request.destination],
  };
  const route = calculateBrowserRoute(account, mutable);
  mutable.origin[0] = 0;
  mutable.destination[1] = 0;
  routeCsrf.resolve(csrfResponse());
  await expect(route).resolves.toMatchObject({ provider: 'mapbox' });
  expect(routeFetcher.mock.calls[1]![1]!.body).toBe(JSON.stringify(request));

  const searchCsrf = deferred<Response>();
  const placeResult = {
    provider: 'mapbox',
    attribution: 'Synthetic attribution',
    places: [{ id: 'place', label: 'Chennai', coordinate: [80, 13] }],
  };
  const searchFetcher = vi
    .fn<typeof fetch>()
    .mockReturnValueOnce(searchCsrf.promise)
    .mockResolvedValueOnce(Response.json(placeResult));
  vi.stubGlobal('fetch', searchFetcher);
  const search = searchBrowserPlaces(account, ' Chennai ');
  searchCsrf.resolve(csrfResponse());
  await expect(search).resolves.toEqual(placeResult);
  expect(searchFetcher.mock.calls[1]![1]!.body).toBe(JSON.stringify({ query: 'Chennai' }));
});
it('returns caller AbortError promptly during CSRF, headers and body reads', async () => {
  const csrf = deferred<Response>();
  const csrfFetcher = vi.fn<typeof fetch>().mockReturnValue(csrf.promise);
  vi.stubGlobal('fetch', csrfFetcher);
  const csrfController = new AbortController();
  const duringCsrf = calculateBrowserRoute(account, request, csrfController.signal);
  csrfController.abort(new Error('private caller reason'));
  await expect(duringCsrf).rejects.toMatchObject({ name: 'AbortError' });
  csrf.resolve(csrfResponse());
  await Promise.resolve();
  await Promise.resolve();
  expect(csrfFetcher).toHaveBeenCalledOnce();

  const headers = deferred<Response>();
  const headersFetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(csrfResponse())
    .mockReturnValueOnce(headers.promise);
  vi.stubGlobal('fetch', headersFetcher);
  const headersController = new AbortController();
  const duringHeaders = calculateBrowserRoute(account, request, headersController.signal);
  await vi.waitFor(() => expect(headersFetcher).toHaveBeenCalledTimes(2));
  headersController.abort();
  await expect(duringHeaders).rejects.toMatchObject({ name: 'AbortError' });

  const bodyCancel = vi.fn();
  const body = new ReadableStream<Uint8Array>({ start() {}, cancel: bodyCancel });
  const bodyFetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(csrfResponse())
    .mockResolvedValueOnce(new Response(body, { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', bodyFetcher);
  const bodyController = new AbortController();
  const duringBody = calculateBrowserRoute(account, request, bodyController.signal);
  await vi.waitFor(() => expect(body.locked).toBe(true));
  bodyController.abort();
  await expect(duringBody).rejects.toMatchObject({ name: 'AbortError' });
  expect(bodyCancel).toHaveBeenCalledOnce();
});
it('disposes a response that arrives after caller cancellation without awaiting cancellation', async () => {
  const headers = deferred<Response>();
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(csrfResponse())
    .mockReturnValueOnce(headers.promise);
  vi.stubGlobal('fetch', fetcher);
  const controller = new AbortController();
  const operation = calculateBrowserRoute(account, request, controller.signal);
  await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  controller.abort();
  await expect(operation).rejects.toMatchObject({ name: 'AbortError' });

  const cancel = vi.fn(() => new Promise<void>(() => undefined));
  headers.resolve(new Response(new ReadableStream({ start() {}, cancel })));
  await vi.waitFor(() => expect(cancel).toHaveBeenCalledOnce());
});
it('uses one deadline across slow CSRF and abort-ignoring response headers', async () => {
  vi.useFakeTimers();
  const csrf = deferred<Response>();
  const fetcher = vi
    .fn<typeof fetch>()
    .mockReturnValueOnce(csrf.promise)
    .mockReturnValueOnce(new Promise<Response>(() => undefined));
  vi.stubGlobal('fetch', fetcher);
  const operation = calculateBrowserRoute(account, request);
  const rejection = expect(operation).rejects.toMatchObject({ status: 503 });
  await vi.advanceTimersByTimeAsync(10_000);
  csrf.resolve(csrfResponse());
  await vi.advanceTimersByTimeAsync(7_999);
  expect(fetcher).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(1);
  await rejection;
  expect(fetcher).toHaveBeenCalledTimes(2);
});
it('never starts a POST when an abort-ignoring CSRF request finishes after its bound', async () => {
  vi.useFakeTimers();
  const csrf = deferred<Response>();
  const fetcher = vi.fn<typeof fetch>().mockReturnValue(csrf.promise);
  vi.stubGlobal('fetch', fetcher);
  const operation = calculateBrowserRoute(account, request);
  const rejection = expect(operation).rejects.toMatchObject({ status: 503 });
  await vi.advanceTimersByTimeAsync(12_000);
  await rejection;
  csrf.resolve(csrfResponse());
  await vi.advanceTimersByTimeAsync(6_000);
  expect(fetcher).toHaveBeenCalledOnce();
});
it('disposes response bodies that arrive after the shared deadline', async () => {
  vi.useFakeTimers();
  const headers = deferred<Response>();
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(csrfResponse())
    .mockReturnValueOnce(headers.promise);
  vi.stubGlobal('fetch', fetcher);
  const operation = calculateBrowserRoute(account, request);
  const rejection = expect(operation).rejects.toMatchObject({ status: 503 });
  await vi.advanceTimersByTimeAsync(0);
  expect(fetcher).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(18_000);
  await rejection;

  const cancel = vi.fn(() => Promise.reject(new Error('private late cleanup failure')));
  headers.resolve(new Response(new ReadableStream({ start() {}, cancel })));
  await vi.advanceTimersByTimeAsync(0);
  expect(cancel).toHaveBeenCalledOnce();
});
it('times out stalled bodies even when stream cancellation never settles', async () => {
  vi.useFakeTimers();
  const cancel = vi.fn(() => new Promise<void>(() => undefined));
  const stream = new ReadableStream<Uint8Array>({ start() {}, cancel });
  respond(new Response(stream, { headers: { 'Content-Type': 'application/json' } }));
  const operation = calculateBrowserRoute(account, request);
  const rejection = expect(operation).rejects.toMatchObject({ status: 503 });
  await vi.advanceTimersByTimeAsync(18_000);
  await rejection;
  expect(cancel).toHaveBeenCalledOnce();
});
it('distinguishes no route, authentication, throttling and invalid provider responses', async () => {
  respond(Response.json({ ...result, routes: [] }));
  expect((await calculateBrowserRoute(account, request)).routes).toEqual([]);
  for (const status of [401, 403, 429, 503]) {
    respond(new Response(null, { status }));
    await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({ status });
  }
  for (const invalid of [
    { ...result, provider: 'unknown' },
    { ...result, calculatedAt: 'yesterday' },
    {
      ...result,
      routes: [
        {
          ...result.routes[0],
          geometry: [
            [77, 99],
            [77, 12],
          ],
        },
      ],
    },
  ]) {
    respond(Response.json(invalid));
    await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({ status: 503 });
  }
});
it('cancels oversized streaming responses before consuming the rest', async () => {
  const cancel = vi.fn();
  respond(
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(new Uint8Array(1048577));
        },
        cancel,
      }),
      { headers: { 'Content-Type': 'application/json' } },
    ),
  );
  await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({ status: 503 });
  expect(cancel).toHaveBeenCalledOnce();
});
it('counts streamed bytes, including split multibyte data, for each operation limit', async () => {
  const cancel = vi.fn();
  const encoded = new TextEncoder().encode(`{"padding":"${'€'.repeat(87_382)}"}`);
  expect(encoded.byteLength).toBeGreaterThan(262_144);
  respond(
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(encoded.subarray(0, 262_140));
          controller.enqueue(encoded.subarray(262_140));
        },
        cancel,
      }),
      { headers: { 'Content-Type': 'application/json' } },
    ),
  );
  await expect(searchBrowserPlaces(account, 'Chennai')).rejects.toMatchObject({ status: 503 });
  expect(cancel).toHaveBeenCalledOnce();
});
it('rejects declared overflow, wrong media, redirects, unexpected success and malformed JSON', async () => {
  const cases: Response[] = [
    new Response('{}', {
      headers: { 'Content-Type': 'application/json', 'Content-Length': '1048577' },
    }),
    new Response('{}', { headers: { 'Content-Type': 'text/plain' } }),
    new Response('{}', { status: 201, headers: { 'Content-Type': 'application/json' } }),
    new Response('{}', { status: 302, headers: { 'Content-Type': 'application/json' } }),
    new Response('{', { headers: { 'Content-Type': 'application/json' } }),
    new Response(new Uint8Array([0xff]), {
      headers: { 'Content-Type': 'application/json' },
    }),
  ];
  const redirected = Response.json(result);
  Object.defineProperty(redirected, 'redirected', { value: true });
  cases.push(redirected);
  for (const response of cases) {
    respond(response);
    await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({ status: 503 });
  }
});
it('uses elapsed wall time to stop empty resolved chunks that starve timers', async () => {
  let now = 0;
  vi.spyOn(Date, 'now').mockImplementation(() => (now += 1_000));
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      controller.enqueue(new Uint8Array());
    },
  });
  respond(new Response(stream, { headers: { 'Content-Type': 'application/json' } }));
  await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({ status: 503 });
});
it('keeps safe status messages and never includes private response details', () => {
  expect(new BrowserRoutingError(401).message).toBe('Sign in again to calculate a route.');
  expect(new BrowserRoutingError(422).message).toBe(routingCoverageMessage);
  expect(new BrowserRoutingError(429).message).toBe(
    'Too many route requests. Wait a minute and try again.',
  );
  expect(new BrowserRoutingError(503).message).toBe(
    'Routing is unavailable. Check your connection and try again.',
  );
});
it('redacts local transport failures', async () => {
  const privateDetails = 'query=Private Home; token=secret; coordinates=77.123,12.456';
  vi.stubGlobal(
    'fetch',
    vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockRejectedValueOnce(new Error(privateDetails)),
  );
  const failure = await calculateBrowserRoute(account, request).catch((error: unknown) => error);
  expect(failure).toMatchObject({ status: 503 });
  expect((failure as Error).message).not.toContain(privateDetails);
  expect((failure as Error).message).toBe(
    'Routing is unavailable. Check your connection and try again.',
  );
});
