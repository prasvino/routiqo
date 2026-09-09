import { expect, it, vi } from 'vitest';
import { proxyBrowserRouting } from '../apps/web/lib/auth-proxy';
const config = {
  clientId: 'synthetic.apps.googleusercontent.com',
  upstream: 'http://localhost:8080',
  origin: 'http://localhost:3000',
};
const request = (url = 'http://localhost:3000/api/v1/routes') =>
  new Request(url, {
    method: 'POST',
    headers: {
      origin: config.origin,
      'content-type': 'application/json',
      'x-routiqo-account': 'synthetic-account',
      'x-xsrf-token': 'synthetic-csrf',
    },
    body: '{}',
  });
it('forwards only the fixed routing endpoint and rejects query injection', async () => {
  const upstream = vi.fn(async () => Response.json({ routes: [] }));
  expect((await proxyBrowserRouting(request(), config, upstream)).status).toBe(200);
  expect(upstream.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/v1/routes');
  expect(
    (
      await proxyBrowserRouting(
        request('http://localhost:3000/api/v1/routes?url=https://wrong.example'),
        config,
        upstream,
      )
    ).status,
  ).toBe(400);
  expect(upstream).toHaveBeenCalledTimes(1);
});
it('allows bounded route geometry while rejecting oversized bodies and unavailable config', async () => {
  expect((await proxyBrowserRouting(request(), null)).status).toBe(503);
  const upstream = vi.fn(async () => new Response('x'.repeat(80 * 1024)));
  expect((await proxyBrowserRouting(request(), config, upstream)).status).toBe(200);
  const huge = vi.fn(async () => new Response('x'.repeat(1024 * 1024 + 1)));
  expect((await proxyBrowserRouting(request(), config, huge)).status).toBe(503);
});
