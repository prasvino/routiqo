import { describe, expect, it, vi } from 'vitest';
import { proxyAdminRequest, readAdminProxyConfig, type AdminProxyConfig } from './admin-proxy';

const config: AdminProxyConfig = {
  origin: 'http://localhost:3001',
  upstream: 'http://127.0.0.1:8080',
  googleClientId: 'admin-test.apps.googleusercontent.com',
};
const url = 'http://localhost:3001/api/admin';

describe('admin proxy boundary', () => {
  it('stays disabled without explicit flag and rejects unsafe origins', async () => {
    expect(readAdminProxyConfig({ ROUTIQO_V3_ADMIN_ENABLED: 'false' })).toBeNull();
    expect(() =>
      readAdminProxyConfig({
        ROUTIQO_V3_ADMIN_ENABLED: 'true',
        ROUTIQO_ADMIN_ORIGIN: 'https://admin.example.test',
        ROUTIQO_ADMIN_API_ORIGIN: 'https://api.example.test/unsafe',
        ROUTIQO_ADMIN_GOOGLE_CLIENT_ID: config.googleClientId,
      }),
    ).toThrow();
    const fetcher = vi.fn<typeof fetch>();
    expect(
      (await proxyAdminRequest(new Request(`${url}/auth/csrf`), ['auth', 'csrf'], null, fetcher))
        .status,
    ).toBe(404);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('forwards only admin cookies and accepts only admin Set-Cookie', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (_url, init) => {
      const headers = new Headers(init?.headers);
      expect(headers.get('Cookie')).toBe('routiqo_admin_session=admin');
      expect(headers.has('X-Routiqo-Account')).toBe(false);
      const responseHeaders = new Headers({ 'Content-Type': 'application/json' });
      responseHeaders.append('Set-Cookie', 'routiqo_admin_csrf=token; Path=/; SameSite=Strict');
      responseHeaders.append('Set-Cookie', 'routiqo_session=consumer; Path=/');
      responseHeaders.append(
        'Set-Cookie',
        'routiqo_admin_session=bad; Domain=example.test; Path=/',
      );
      return new Response('{"token":"abc"}', { status: 200, headers: responseHeaders });
    });
    const request = new Request(`${url}/auth/csrf`, {
      headers: {
        Cookie: 'routiqo_session=consumer; routiqo_admin_session=admin',
        'X-Routiqo-Account': 'untrusted',
      },
    });
    const result = await proxyAdminRequest(request, ['auth', 'csrf'], config, fetcher);
    expect(result.status).toBe(200);
    expect(result.headers.getSetCookie()).toEqual([
      'routiqo_admin_csrf=token; Path=/; SameSite=Strict',
    ]);
    expect(result.headers.get('Cache-Control')).toBe('no-store');
  });

  it('rejects cross-site writes, unknown routes, bad cursors and redirects', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(null, { status: 302, headers: { Location: 'https://evil.example' } }),
      );
    const post = (origin: string) =>
      new Request(`${url}/auth/logout`, {
        method: 'POST',
        headers: { Origin: origin, 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'token' },
        body: '{}',
      });
    expect(
      (await proxyAdminRequest(post('https://evil.example'), ['auth', 'logout'], config, fetcher))
        .status,
    ).toBe(403);
    expect(
      (
        await proxyAdminRequest(
          new Request(`${url}/private/users`),
          ['private', 'users'],
          config,
          fetcher,
        )
      ).status,
    ).toBe(404);
    expect(
      (
        await proxyAdminRequest(
          new Request(`${url}/community-traffic/reports?cursor=bad%2Fpath`),
          ['community-traffic', 'reports'],
          config,
          fetcher,
        )
      ).status,
    ).toBe(404);
    expect(
      (await proxyAdminRequest(post(config.origin), ['auth', 'logout'], config, fetcher)).status,
    ).toBe(502);
  });

  it('bounds request body and response stream including a stalled stream', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(new TextEncoder().encode('{'));
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    const oversized = new Request(`${url}/auth/logout`, {
      method: 'POST',
      headers: {
        Origin: config.origin,
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token',
      },
      body: 'x'.repeat(4097),
    });
    expect((await proxyAdminRequest(oversized, ['auth', 'logout'], config, fetcher)).status).toBe(
      413,
    );
    const started = Date.now();
    const result = await proxyAdminRequest(
      new Request(`${url}/auth/csrf`),
      ['auth', 'csrf'],
      config,
      fetcher,
      30,
    );
    expect(result.status).toBe(502);
    expect(Date.now() - started).toBeLessThan(1000);
  });
});
