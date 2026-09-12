import { afterEach, expect, it, vi } from 'vitest';
import { calculateBrowserRoute, routingCoverageMessage } from '../apps/web/lib/browser-routing';
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
afterEach(() => vi.unstubAllGlobals());
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
    ),
  );
  await expect(calculateBrowserRoute(account, request)).rejects.toMatchObject({ status: 503 });
  expect(cancel).toHaveBeenCalledOnce();
});
