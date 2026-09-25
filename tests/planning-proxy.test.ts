import { describe, expect, it, vi } from 'vitest';
import { proxyBrowserJourneys, proxyBrowserPlanning } from '../apps/web/lib/auth-proxy';

const config = {
  clientId: 'test-client.apps.googleusercontent.com',
  upstream: 'http://127.0.0.1:8080',
  origin: 'http://localhost:3000',
};
const account = '00000000-0000-4000-8000-000000000001';
const post = (path: string, body: string, headers: Record<string, string> = {}) =>
  new Request(`${config.origin}/api/v1/${path}`, {
    method: 'POST',
    body,
    headers: {
      'Content-Type': 'application/json',
      Origin: config.origin,
      'X-Routiqo-Account': account,
      'X-XSRF-TOKEN': 'csrf',
      Cookie: 'routiqo_session=s; unrelated=x',
      ...headers,
    },
  });

describe('account planning proxy', () => {
  it('is unavailable without configuration and absent while the flag is off', async () => {
    const upstream = vi.fn<typeof fetch>();
    const read = new Request(`${config.origin}/api/v1/planning`);
    expect((await proxyBrowserPlanning(read, [], null, upstream, true)).status).toBe(503);
    expect((await proxyBrowserPlanning(read, [], config, upstream)).status).toBe(404);
    expect((await proxyBrowserPlanning(read, [], config, upstream, false)).status).toBe(404);
    expect(upstream).not.toHaveBeenCalled();
  });

  it('forwards only the allowlisted paths and methods', async () => {
    const upstream = vi
      .fn<typeof fetch>()
      .mockImplementation(async () =>
        Response.json({ version: 0, updatedAt: null, plans: [], saved: [] }),
      );
    const read = new Request(`${config.origin}/api/v1/planning`, {
      headers: { 'X-Routiqo-Account': account, Cookie: 'routiqo_session=s; other=y' },
    });
    const response = await proxyBrowserPlanning(read, [], config, upstream, true);
    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('no-store');
    expect(upstream.mock.calls[0]?.[0]).toBe(`${config.upstream}/api/v1/planning`);
    const forwarded = new Headers(upstream.mock.calls[0]?.[1]?.headers);
    expect(forwarded.get('x-routiqo-account')).toBe(account);
    expect(forwarded.get('cookie')).toBe('routiqo_session=s');
    expect(upstream.mock.calls[0]?.[1]?.redirect).toBe('manual');

    expect(
      (
        await proxyBrowserPlanning(
          post('planning/delete', '{"expectedVersion":1}'),
          ['delete'],
          config,
          upstream,
          true,
        )
      ).status,
    ).toBe(200);
    expect(upstream.mock.calls[1]?.[0]).toBe(`${config.upstream}/api/v1/planning/delete`);

    upstream.mockClear();
    const denied: [Request, string[], number][] = [
      [new Request(`${config.origin}/api/v1/planning/delete`), ['delete'], 405],
      [new Request(`${config.origin}/api/v1/planning`, { method: 'PUT', body: '{}' }), [], 405],
      [new Request(`${config.origin}/api/v1/planning/other`), ['other'], 404],
      [new Request(`${config.origin}/api/v1/planning/delete/x`), ['delete', 'x'], 404],
      [new Request(`${config.origin}/api/v1/planning?x=1`), [], 400],
    ];
    for (const [request, path, status] of denied)
      expect((await proxyBrowserPlanning(request, path, config, upstream, true)).status).toBe(
        status,
      );
    expect(upstream).not.toHaveBeenCalled();
  });

  it('enforces origin, JSON and the path-specific request bounds', async () => {
    const upstream = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => new Response(null, { status: 204 }));
    expect(
      (
        await proxyBrowserPlanning(
          post('planning', '{}', { Origin: 'https://evil.invalid' }),
          [],
          config,
          upstream,
          true,
        )
      ).status,
    ).toBe(403);
    expect(
      (
        await proxyBrowserPlanning(
          post('planning', '{}', { 'Content-Type': 'text/plain' }),
          [],
          config,
          upstream,
          true,
        )
      ).status,
    ).toBe(415);
    expect(upstream).not.toHaveBeenCalled();

    const large = `{"x":"${'a'.repeat(200 * 1024)}"}`;
    expect(
      (await proxyBrowserPlanning(post('planning', large), [], config, upstream, true)).status,
    ).toBe(204);
    expect(Buffer.from(upstream.mock.calls[0]?.[1]?.body as Uint8Array).byteLength).toBe(
      large.length,
    );
    expect(
      (
        await proxyBrowserPlanning(
          post('planning', 'a'.repeat(264 * 1024 + 1)),
          [],
          config,
          upstream,
          true,
        )
      ).status,
    ).toBe(413);
    expect(
      (
        await proxyBrowserPlanning(
          post('planning/delete', 'a'.repeat(20 * 1024 + 1)),
          ['delete'],
          config,
          upstream,
          true,
        )
      ).status,
    ).toBe(413);
    // The larger allowance never leaks to other browser routes.
    expect(
      (
        await proxyBrowserJourneys(
          post('journeys', 'a'.repeat(20 * 1024 + 1)),
          [],
          config,
          upstream,
        )
      ).status,
    ).toBe(413);
    expect(upstream).toHaveBeenCalledTimes(1);
  });

  it('bounds responses and rejects upstream redirects', async () => {
    const tooLarge = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response('a'.repeat(320 * 1024 + 1), { status: 200 }));
    const read = new Request(`${config.origin}/api/v1/planning`);
    expect((await proxyBrowserPlanning(read, [], config, tooLarge, true)).status).toBe(503);
    const redirect = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(null, { status: 302, headers: { Location: 'https://x.invalid' } }),
      );
    expect((await proxyBrowserPlanning(read, [], config, redirect, true)).status).toBe(502);
    const conflict = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 409 }));
    expect(
      (await proxyBrowserPlanning(post('planning', '{}'), [], config, conflict, true)).status,
    ).toBe(409);
  });
});
