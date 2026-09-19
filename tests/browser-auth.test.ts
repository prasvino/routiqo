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
  BrowserAuthError,
} from '../apps/web/lib/browser-auth';
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const id = '00000000-0000-4000-8000-000000000001';
const csrf = 'masked-csrf-token-for-testing';

const jsonResponse = (body: BodyInit, status = 200) =>
  new Response(body, { status, headers: { 'Content-Type': 'application/json' } });

describe('browser auth client', () => {
  it('treats only confirmed unauthenticated sessions as signed out', async () => {
    const unauthenticated = new Response('unused', { status: 401 });
    const cancel = vi.spyOn(unauthenticated.body!, 'cancel');
    const getReader = vi.spyOn(unauthenticated.body!, 'getReader');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(unauthenticated));
    expect(await browserAccount()).toBeNull();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(getReader).not.toHaveBeenCalled();
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
        redirect: 'error',
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

  it('rejects redirected responses and redacts HTTP error bodies', async () => {
    const redirected = Response.json({ token: csrf });
    Object.defineProperty(redirected, 'redirected', { value: true });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(redirected));
    await expect(browserCsrf()).rejects.toMatchObject({ status: 503 });

    const privateError = new Error('sensitive upstream detail');
    const errorResponse = new Response(
      new ReadableStream<Uint8Array>({
        cancel: () => Promise.reject(privateError),
      }),
      { status: 429 },
    );
    const cancel = vi.spyOn(errorResponse.body!, 'cancel');
    const getReader = vi.spyOn(errorResponse.body!, 'getReader');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse));
    const failure = browserCsrf();
    await expect(failure).rejects.toMatchObject({ status: 429 });
    await expect(failure).rejects.not.toHaveProperty('cause');
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(getReader).not.toHaveBeenCalled();
    await Promise.resolve();
  });

  it('requires status 200, a JSON content type, and a present body for JSON endpoints', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(JSON.stringify({ token: csrf }), 201)),
    );
    await expect(browserCsrf()).rejects.toMatchObject({ status: 503 });

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: csrf }))),
    );
    await expect(browserCsrf()).rejects.toMatchObject({ status: 503 });

    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(new Response(null, { headers: { 'Content-Type': 'application/json' } })),
    );
    await expect(browserCsrf()).rejects.toMatchObject({ status: 503 });
  });

  it('streams valid JSON and rejects oversized, malformed UTF-8, and malformed JSON bodies', async () => {
    const encoded = new TextEncoder().encode(JSON.stringify({ token: csrf }));
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              controller.enqueue(encoded.subarray(0, 7));
              controller.enqueue(encoded.subarray(7));
              controller.close();
            },
          }),
          { headers: { 'Content-Type': 'application/problem+json; charset=utf-8' } },
        ),
      ),
    );
    await expect(browserCsrf()).resolves.toBe(csrf);

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(JSON.stringify({ token: 'x'.repeat(17_000) }))),
    );
    await expect(browserCsrf()).rejects.toMatchObject({ status: 503 });

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(new Uint8Array([0x7b, 0xc3, 0x28, 0x7d]))),
    );
    await expect(browserCsrf()).rejects.toMatchObject({ status: 503 });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse('{')));
    await expect(browserCsrf()).rejects.toMatchObject({ status: 503 });
  });

  it('requires exact 204 acknowledgements for logout and account deletion', async () => {
    const logoutFetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: csrf }))
      .mockResolvedValueOnce(Response.json({ accepted: true }));
    vi.stubGlobal('fetch', logoutFetcher);
    await expect(logoutBrowser()).rejects.toMatchObject({ status: 503 });

    const deleteFetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: csrf }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', deleteFetcher);
    await expect(deleteBrowserAccount(id)).resolves.toBeUndefined();
  });

  it('bounds stalled headers even when fetch ignores abort and observes a late failure', async () => {
    vi.useFakeTimers();
    let rejectFetch!: (reason: Error) => void;
    const fetcher = vi.fn().mockImplementation(
      () =>
        new Promise<Response>((_, reject) => {
          rejectFetch = reject;
        }),
    );
    vi.stubGlobal('fetch', fetcher);

    const pending = browserCsrf();
    const rejection = expect(pending).rejects.toMatchObject({ status: 503 });
    await vi.advanceTimersByTimeAsync(12_000);
    await rejection;
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect((fetcher.mock.calls[0]?.[1] as RequestInit).signal?.aborted).toBe(true);
    rejectFetch(new Error('late transport failure'));
    await Promise.resolve();
  });

  it('bounds stalled body reads without waiting for stream cancellation', async () => {
    vi.useFakeTimers();
    const cancel = vi.fn(() => new Promise<void>(() => undefined));
    const body = new ReadableStream<Uint8Array>({
      pull: () => new Promise<void>(() => undefined),
      cancel,
    });
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(new Response(body, { headers: { 'Content-Type': 'application/json' } })),
    );

    const pending = browserCsrf();
    const rejection = expect(pending).rejects.toMatchObject({ status: 503 });
    await vi.advanceTimersByTimeAsync(12_000);
    await rejection;
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('uses one deadline for CSRF and mutation and never launches a late POST', async () => {
    vi.useFakeTimers();
    let resolveCsrf!: (response: Response) => void;
    const fetcher = vi.fn().mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          resolveCsrf = resolve;
        }),
    );
    vi.stubGlobal('fetch', fetcher);

    const pending = logoutBrowser();
    const rejection = expect(pending).rejects.toMatchObject({ status: 503 });
    await vi.advanceTimersByTimeAsync(12_000);
    await rejection;
    resolveCsrf(Response.json({ token: csrf }));
    await Promise.resolve();
    await Promise.resolve();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('does not restart the deadline after a slow CSRF response', async () => {
    vi.useFakeTimers();
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            setTimeout(() => resolve(Response.json({ token: csrf })), 11_000);
          }),
      )
      .mockImplementationOnce(() => new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetcher);

    const pending = renewBrowserSession();
    const rejection = expect(pending).rejects.toBeInstanceOf(BrowserAuthError);
    await vi.advanceTimersByTimeAsync(11_000);
    expect(fetcher).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1_000);
    await rejection;
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
