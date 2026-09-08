import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  authAvailability,
  browserAccount,
  beginGoogleLogin,
  browserCsrf,
  exchangeGoogleLogin,
  logoutBrowser,
  renewBrowserSession,
  deleteBrowserAccount,
} from '../apps/web/lib/browser-auth';
afterEach(() => vi.unstubAllGlobals());
const id = '00000000-0000-4000-8000-000000000001';
describe('browser auth client', () => {
  it('treats only confirmed unauthenticated sessions as signed out', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    expect(await browserAccount()).toBeNull();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })));
    await expect(browserAccount()).rejects.toThrow('temporarily unavailable');
  });
  it('validates provider config and server responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(Response.json({ enabled: false, clientId: null })),
    );
    expect(await authAvailability()).toEqual({ enabled: false, clientId: null });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(Response.json({ accountId: 'untrusted-shape' })),
    );
    await expect(browserAccount()).rejects.toThrow();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(Response.json({ id, nonce: 'short', expiresAt: 'invalid' })),
    );
    await expect(beginGoogleLogin('csrf')).rejects.toThrow();
  });
  it('exchanges with cookie credentials and CSRF without persisting tokens', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        Response.json({ accountId: id, expiresAt: '2026-09-08T12:00:00Z', credential: 'ignored' }),
      );
    vi.stubGlobal('fetch', fetcher);
    expect(await exchangeGoogleLogin(id, 'provider-test-value', 'masked-csrf')).toEqual({
      accountId: id,
      expiresAt: '2026-09-08T12:00:00Z',
    });
    expect(fetcher).toHaveBeenCalledWith(
      '/api/v1/auth/google/exchange',
      expect.objectContaining({
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'masked-csrf' },
      }),
    );
  });
  it('gets fresh CSRF for logout and exposes rate-limit feedback', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'masked-csrf-token-for-testing' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);
    await logoutBrowser();
    expect(fetcher.mock.calls[1]?.[0]).toBe('/api/v1/auth/logout');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 429 })));
    await expect(browserCsrf()).rejects.toThrow('Wait a minute');
  });
  it('validates renewal and sends explicit account confirmation for deletion', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'masked-csrf-token-for-testing' }))
      .mockResolvedValueOnce(Response.json({ accountId: id, expiresAt: '2026-09-08T12:00:00Z' }))
      .mockResolvedValueOnce(Response.json({ token: 'masked-csrf-token-for-testing' }))
      .mockResolvedValueOnce(new Response(null, { status: 428 }));
    vi.stubGlobal('fetch', fetcher);
    expect(await renewBrowserSession()).toEqual({
      accountId: id,
      expiresAt: '2026-09-08T12:00:00Z',
    });
    await expect(deleteBrowserAccount(id)).rejects.toMatchObject({ status: 428 });
    expect(fetcher.mock.calls[3]).toEqual([
      '/api/v1/auth/account/delete',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        body: JSON.stringify({ confirmation: 'DELETE', accountId: id }),
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'masked-csrf-token-for-testing',
        },
      }),
    ]);
  });
});
