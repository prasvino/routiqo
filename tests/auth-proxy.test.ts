import { describe, expect, it, vi } from 'vitest';
import {
  proxyBrowserAuth,
  proxyBrowserJourneys,
  readBrowserAuthConfig,
} from '../apps/web/lib/auth-proxy';
const config = {
  clientId: 'test-client.apps.googleusercontent.com',
  upstream: 'http://127.0.0.1:8080',
  origin: 'http://localhost:3000',
};
const request = (body = '{}', origin: string | null = config.origin) =>
  new Request(`${config.origin}/api/v1/auth/google/exchange`, {
    method: 'POST',
    body,
    headers: { 'Content-Type': 'application/json', ...(origin ? { Origin: origin } : {}) },
  });
describe('same-origin auth proxy', () => {
  it('allows only journey routes and bounded cursor queries', async () => {
    const upstream = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => Response.json({ journeys: [], next: null }));
    const id = '00000000-0000-4000-8000-000000000001';
    for (const path of [
      ['..', 'auth'],
      ['https://attacker.invalid'],
      [id, 'delete'],
      [id, 'complete', 'extra'],
    ])
      expect(
        (
          await proxyBrowserJourneys(
            new Request(`${config.origin}/api/v1/journeys`),
            path,
            config,
            upstream,
          )
        ).status,
      ).toBe(404);
    for (const query of [
      'limit=51',
      'limit=1&limit=2',
      'accountId=' + id,
      'beforeId=' + id,
      'beforeStartedAt=bad&beforeId=' + id,
    ])
      expect(
        (
          await proxyBrowserJourneys(
            new Request(`${config.origin}/api/v1/journeys?${query}`),
            [],
            config,
            upstream,
          )
        ).status,
      ).toBe(400);
    expect(upstream).not.toHaveBeenCalled();
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys?limit=20`),
          [],
          config,
          upstream,
        )
      ).status,
    ).toBe(200);
    expect(upstream.mock.calls[0]?.[0]).toBe('http://127.0.0.1:8080/api/v1/journeys?limit=20');
  });
  it('keeps origin and credential filtering on journey writes', async () => {
    const upstream = vi.fn<typeof fetch>().mockImplementation(async () => Response.json({}));
    const id = '00000000-0000-4000-8000-000000000001';
    expect((await proxyBrowserJourneys(request('{}', null), [], config, upstream)).status).toBe(
      403,
    );
    expect((await proxyBrowserJourneys(request(), [id], config, upstream)).status).toBe(405);
    const incoming = request();
    incoming.headers.set('cookie', 'private=ignored; routiqo_session=opaque');
    incoming.headers.set('authorization', 'Bearer ignored');
    expect((await proxyBrowserJourneys(incoming, [id, 'complete'], config, upstream)).status).toBe(
      200,
    );
    const [url, options] = upstream.mock.calls[0]!;
    expect(url).toBe(`http://127.0.0.1:8080/api/v1/journeys/${id}/complete`);
    expect(new Headers(options?.headers).get('cookie')).toBe('routiqo_session=opaque');
    expect(new Headers(options?.headers).get('authorization')).toBeNull();
  });
  it('fails closed for missing/invalid configuration and unsupported destinations', async () => {
    expect(readBrowserAuthConfig({})).toBeNull();
    expect(() =>
      readBrowserAuthConfig({
        ROUTIQO_GOOGLE_CLIENT_ID: config.clientId,
        ROUTIQO_AUTH_API_URL: 'http://remote.invalid',
        ROUTIQO_WEB_ORIGIN: config.origin,
      }),
    ).toThrow();
    const upstream = vi.fn<typeof fetch>();
    expect((await proxyBrowserAuth(request(), 'google/exchange', null, upstream)).status).toBe(503);
    expect((await proxyBrowserAuth(request(), '../../elsewhere', config, upstream)).status).toBe(
      404,
    );
    expect((await proxyBrowserAuth(request(), 'session', config, upstream)).status).toBe(405);
    expect(upstream).not.toHaveBeenCalled();
  });
  it('rejects cross-origin requests, oversized bodies and query parameters before upstream', async () => {
    const upstream = vi.fn<typeof fetch>();
    for (const origin of [null, 'https://attacker.invalid'])
      expect(
        (await proxyBrowserAuth(request('{}', origin), 'google/exchange', config, upstream)).status,
      ).toBe(403);
    expect(
      (await proxyBrowserAuth(request('x'.repeat(20481)), 'google/exchange', config, upstream))
        .status,
    ).toBe(413);
    expect(
      (
        await proxyBrowserAuth(
          new Request(`${config.origin}/api/v1/auth/session?credential=invalid`),
          'session',
          config,
          upstream,
        )
      ).status,
    ).toBe(400);
    expect(upstream).not.toHaveBeenCalled();
  });
  it('forwards only auth headers and preserves separate cookie updates', async () => {
    const incoming = request();
    incoming.headers.set('Cookie', 'other=private; routiqo_session=session; routiqo_csrf=csrf');
    incoming.headers.set('Authorization', 'Bearer ignored');
    incoming.headers.set('X-Forwarded-For', 'untrusted');
    incoming.headers.set('X-XSRF-TOKEN', 'masked');
    const upstream = vi.fn<typeof fetch>().mockResolvedValue(
      new Response('{}', {
        headers: [
          ['Set-Cookie', 'routiqo_session=new; HttpOnly; Path=/'],
          ['Set-Cookie', 'routiqo_binding=; Max-Age=0; Path=/'],
          ['Set-Cookie', 'unrelated=ignored'],
          ['Content-Type', 'application/json'],
        ],
      }),
    );
    const result = await proxyBrowserAuth(incoming, 'google/exchange', config, upstream);
    const [url, options] = upstream.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:8080/api/v1/auth/google/exchange');
    const headers = new Headers(options?.headers);
    expect(headers.get('cookie')).toBe('routiqo_session=session; routiqo_csrf=csrf');
    expect(headers.get('authorization')).toBeNull();
    expect(headers.get('x-forwarded-for')).toBeNull();
    expect(headers.get('origin')).toBe(config.origin);
    expect(options?.redirect).toBe('manual');
    expect(result.headers.getSetCookie()).toHaveLength(2);
    expect(result.headers.get('cache-control')).toBe('no-store');
  });
  it('does not follow redirects and handles upstream failure or oversized responses', async () => {
    const get = () => new Request(`${config.origin}/api/v1/auth/session`);
    expect(
      (
        await proxyBrowserAuth(
          get(),
          'session',
          config,
          vi.fn<typeof fetch>().mockResolvedValue(
            new Response(null, {
              status: 302,
              headers: { Location: 'https://untrusted.invalid' },
            }),
          ),
        )
      ).status,
    ).toBe(502);
    expect(
      (
        await proxyBrowserAuth(
          get(),
          'session',
          config,
          vi.fn<typeof fetch>().mockRejectedValue(new Error('private upstream details')),
        )
      ).status,
    ).toBe(503);
    const huge = await proxyBrowserAuth(
      get(),
      'session',
      config,
      vi.fn<typeof fetch>().mockResolvedValue(new Response('x'.repeat(65537))),
    );
    expect(huge.status).toBe(503);
    expect(await huge.text()).toBe('');
  });
});
