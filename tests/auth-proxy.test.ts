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
  it('allows only a bounded GET for provider alerts', async () => {
    const id = '00000000-0000-4000-8000-000000000001';
    const path = [id, 'provider-alerts'];
    const upstream = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ alerts: [] }));
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${id}/provider-alerts`),
          path,
          config,
          upstream,
        )
      ).status,
    ).toBe(200);
    expect(upstream.mock.calls[0]?.[0]).toBe(
      `${config.upstream}/api/v1/journeys/${id}/provider-alerts`,
    );
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${id}/provider-alerts`, { method: 'POST' }),
          path,
          config,
          upstream,
        )
      ).status,
    ).toBe(405);
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${id}/provider-alerts?x=1`),
          path,
          config,
          upstream,
        )
      ).status,
    ).toBe(400);
    expect(upstream).toHaveBeenCalledTimes(1);
  });
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
  it('allows only GET and POST for the exact journey consent path', async () => {
    const upstream = vi.fn<typeof fetch>().mockImplementation(async () =>
      Response.json({
        journeyId: '00000000-0000-4000-8000-000000000001',
        generation: '9223372036854775807',
        sharing: false,
        journeyActive: true,
      }),
    );
    const id = '00000000-0000-4000-8000-000000000001';
    const get = new Request(`${config.origin}/api/v1/journeys/${id}/consent`, {
      headers: { Cookie: 'routiqo_session=opaque', 'X-Routiqo-Account': id },
    });
    const getResponse = await proxyBrowserJourneys(get, [id, 'consent'], config, upstream);
    expect(getResponse.status).toBe(200);
    expect((await getResponse.json()).generation).toBe('9223372036854775807');
    expect(upstream.mock.calls[0]?.[0]).toBe(`http://127.0.0.1:8080/api/v1/journeys/${id}/consent`);
    const post = new Request(`${config.origin}/api/v1/journeys/${id}/consent`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Origin: config.origin,
        'X-XSRF-TOKEN': 'masked',
        'X-Routiqo-Account': id,
      },
      body: JSON.stringify({ expectedGeneration: '0', sharing: false }),
    });
    const postResponse = await proxyBrowserJourneys(post, [id, 'consent'], config, upstream);
    expect(postResponse.status).toBe(200);
    expect((await postResponse.json()).generation).toBe('9223372036854775807');
    expect(String(upstream.mock.calls[1]?.[1]?.body)).toBe(
      JSON.stringify({ expectedGeneration: '0', sharing: false }),
    );
    for (const request of [
      new Request(`${config.origin}/api/v1/journeys/${id}/consent`, { method: 'PUT' }),
      new Request(`${config.origin}/api/v1/journeys/${id}/consent?actor=${id}`),
    ])
      expect((await proxyBrowserJourneys(request, [id, 'consent'], config, upstream)).status).toBe(
        request.method === 'PUT' ? 405 : 400,
      );
    expect(upstream).toHaveBeenCalledTimes(2);
  });
  it('allows only exact route-context requests and gives binding a bounded 25 second window', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout');
    const upstream = vi.fn<typeof fetch>().mockImplementation(async () =>
      Response.json({
        status: 'bound',
        context: {
          contextId: '00000000-0000-4000-8000-000000000002',
          revision: '9223372036854775807',
          anchorIds: ['00000000-0000-4000-8000-000000000003'],
          issuedAt: '2026-09-14T00:00:00Z',
          expiresAt: '2026-09-14T00:15:00Z',
        },
      }),
    );
    const id = '00000000-0000-4000-8000-000000000001';
    const body = JSON.stringify({
      mode: 'driving',
      origin: [-0.02, 0],
      destination: [0.02, 0],
      alternativeIndex: 0,
      expectedContextId: null,
    });
    const post = new Request(`${config.origin}/api/v1/journeys/${id}/route-context`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Origin: config.origin,
        'X-XSRF-TOKEN': 'masked',
        'X-Routiqo-Account': id,
      },
      body,
    });
    const postResponse = await proxyBrowserJourneys(post, [id, 'route-context'], config, upstream);
    expect(postResponse.status).toBe(200);
    expect((await postResponse.json()).context.revision).toBe('9223372036854775807');
    expect(upstream.mock.calls[0]?.[0]).toBe(
      `http://127.0.0.1:8080/api/v1/journeys/${id}/route-context`,
    );
    expect(String(upstream.mock.calls[0]?.[1]?.body)).toBe(body);
    expect(upstream.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal);

    const get = new Request(`${config.origin}/api/v1/journeys/${id}/route-context`, {
      headers: { Cookie: 'routiqo_session=opaque', 'X-Routiqo-Account': id },
    });
    expect((await proxyBrowserJourneys(get, [id, 'route-context'], config, upstream)).status).toBe(
      200,
    );
    for (const invalid of [
      new Request(`${config.origin}/api/v1/journeys/${id}/route-context`, { method: 'PUT' }),
      new Request(`${config.origin}/api/v1/journeys/${id}/route-context?retry=true`),
    ])
      expect(
        (await proxyBrowserJourneys(invalid, [id, 'route-context'], config, upstream)).status,
      ).toBe(invalid.method === 'PUT' ? 405 : 400);
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${id}/route-context/replace`),
          [id, 'route-context', 'replace'],
          config,
          upstream,
        )
      ).status,
    ).toBe(404);
    expect(upstream).toHaveBeenCalledTimes(2);
    expect(timeout).toHaveBeenNthCalledWith(1, 25000);
    expect(timeout).toHaveBeenNthCalledWith(2, 8000);
    timeout.mockRestore();
  });
  it('allows only the existing exact POST signal leaves with standard bounds', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout');
    const upstream = vi.fn<typeof fetch>().mockImplementation(async () =>
      Response.json({
        commandId: '00000000-0000-4000-8000-000000000002',
        status: 'accepted',
        receivedAt: '2026-09-14T00:00:00Z',
        expiresAt: '2026-09-14T00:15:00Z',
        retainUntil: '2026-09-15T00:00:00Z',
      }),
    );
    const journey = '00000000-0000-4000-8000-000000000001';
    const command = '00000000-0000-4000-8000-000000000002';
    const cases: Array<[string[], unknown]> = [
      [[journey, 'signal-commands'], { anchorId: command }],
      [
        [journey, 'signals', command],
        {
          anchorId: command,
          value: 'queue_under_5',
          contextId: command,
          routeRevision: '9223372036854775807',
          consentGeneration: '9223372036854775807',
        },
      ],
      [[journey, 'signals', command, 'withdraw'], {}],
      [[journey, 'signal-commands', command, 'stop'], {}],
    ];
    for (const [path, payload] of cases) {
      const body = JSON.stringify(payload);
      const response = await proxyBrowserJourneys(
        new Request(`${config.origin}/api/v1/journeys/${path.join('/')}`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Origin: config.origin,
            'X-XSRF-TOKEN': 'masked',
            'X-Routiqo-Account': journey,
          },
          body,
        }),
        path,
        config,
        upstream,
      );
      expect(response.status).toBe(200);
      expect(String(upstream.mock.calls.at(-1)?.[1]?.body)).toBe(body);
    }
    for (const path of [
      [journey, 'signal-commands', command],
      [journey, 'signals'],
      [journey, 'signals', command, 'withdraw', 'extra'],
      [journey, 'signal-commands', command, 'stop', 'extra'],
    ])
      expect(
        (
          await proxyBrowserJourneys(
            new Request(`${config.origin}/api/v1/journeys/${path.join('/')}`),
            path,
            config,
            upstream,
          )
        ).status,
      ).toBe(404);
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${journey}/signals/${command}`),
          [journey, 'signals', command],
          config,
          upstream,
        )
      ).status,
    ).toBe(405);
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${journey}/signal-commands?retry=true`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Origin: config.origin },
            body: '{}',
          }),
          [journey, 'signal-commands'],
          config,
          upstream,
        )
      ).status,
    ).toBe(400);
    expect(upstream).toHaveBeenCalledTimes(4);
    expect(timeout).toHaveBeenCalledTimes(4);
    expect(timeout).toHaveBeenLastCalledWith(8000);
    timeout.mockRestore();
  });
  it('allows only exact POST for command stop with query denial and the standard response bound', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout');
    const journey = '00000000-0000-4000-8000-000000000001';
    const command = '00000000-0000-4000-8000-000000000002';
    const response = { commandId: command, status: 'stopped', receipt: null };
    const upstream = vi.fn<typeof fetch>().mockResolvedValue(Response.json(response));
    const path = [journey, 'signal-commands', command, 'stop'];
    const request = new Request(`${config.origin}/api/v1/journeys/${path.join('/')}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Origin: config.origin,
        'X-XSRF-TOKEN': 'masked',
        'X-Routiqo-Account': journey,
      },
      body: '{}',
    });
    const result = await proxyBrowserJourneys(request, path, config, upstream);
    expect(result.status).toBe(200);
    expect(await result.json()).toEqual(response);
    expect(upstream.mock.calls[0]?.[0]).toBe(
      `${config.upstream}/api/v1/journeys/${path.join('/')}`,
    );
    expect(String(upstream.mock.calls[0]?.[1]?.body)).toBe('{}');
    expect(timeout).toHaveBeenCalledWith(8000);

    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${path.join('/')}`),
          path,
          config,
          upstream,
        )
      ).status,
    ).toBe(405);
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${path.join('/')}?retry=true`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Origin: config.origin },
            body: '{}',
          }),
          path,
          config,
          upstream,
        )
      ).status,
    ).toBe(400);
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${path.join('/')}/extra`),
          [...path, 'extra'],
          config,
          upstream,
        )
      ).status,
    ).toBe(404);
    expect(upstream).toHaveBeenCalledOnce();

    upstream.mockResolvedValueOnce(
      new Response(new Uint8Array(64 * 1024 + 1), {
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const oversized = await proxyBrowserJourneys(
      new Request(`${config.origin}/api/v1/journeys/${path.join('/')}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Origin: config.origin },
        body: '{}',
      }),
      path,
      config,
      upstream,
    );
    expect(oversized.status).toBe(503);
    expect(upstream).toHaveBeenCalledTimes(2);
    timeout.mockRestore();
  });
  it('allows only exact signal-choice GET and expected-context POST with distinct response bounds', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout');
    const journey = '00000000-0000-4000-8000-000000000001';
    const largeChoiceBody = JSON.stringify({ padding: 'x'.repeat(70 * 1024) });
    const upstream = vi
      .fn<typeof fetch>()
      .mockImplementation(async (url) =>
        String(url).endsWith('/signal-choices')
          ? new Response(largeChoiceBody, { headers: { 'Content-Type': 'application/json' } })
          : Response.json({}),
      );

    const choiceResponse = await proxyBrowserJourneys(
      new Request(`${config.origin}/api/v1/journeys/${journey}/signal-choices`, {
        headers: { Cookie: 'routiqo_session=opaque', 'X-Routiqo-Account': journey },
      }),
      [journey, 'signal-choices'],
      config,
      upstream,
    );
    expect(choiceResponse.status).toBe(200);
    expect(await choiceResponse.text()).toBe(largeChoiceBody);
    expect(upstream.mock.calls[0]?.[0]).toBe(
      `${config.upstream}/api/v1/journeys/${journey}/signal-choices`,
    );

    const expectedBody = JSON.stringify({
      anchorId: '00000000-0000-4000-8000-000000000002',
      contextId: '00000000-0000-4000-8000-000000000003',
      routeRevision: '9223372036854775807',
      consentGeneration: '9007199254740993',
    });
    const issueResponse = await proxyBrowserJourneys(
      new Request(`${config.origin}/api/v1/journeys/${journey}/signal-commands/expected-context`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Origin: config.origin,
          'X-XSRF-TOKEN': 'masked',
          'X-Routiqo-Account': journey,
        },
        body: expectedBody,
      }),
      [journey, 'signal-commands', 'expected-context'],
      config,
      upstream,
    );
    expect(issueResponse.status).toBe(200);
    expect(upstream.mock.calls[1]?.[0]).toBe(
      `${config.upstream}/api/v1/journeys/${journey}/signal-commands/expected-context`,
    );
    expect(String(upstream.mock.calls[1]?.[1]?.body)).toBe(expectedBody);
    expect(timeout).toHaveBeenNthCalledWith(1, 8000);
    expect(timeout).toHaveBeenNthCalledWith(2, 8000);

    for (const [method, path, expectedStatus] of [
      ['POST', [journey, 'signal-choices'], 405],
      ['GET', [journey, 'signal-commands', 'expected-context'], 405],
      ['GET', [journey, 'signal-choices', 'extra'], 404],
      ['POST', [journey, 'signal-commands', 'expected-context', 'extra'], 404],
    ] as const) {
      const response = await proxyBrowserJourneys(
        new Request(`${config.origin}/api/v1/journeys/${path.join('/')}`, {
          method,
          ...(method === 'POST'
            ? { headers: { 'Content-Type': 'application/json', Origin: config.origin }, body: '{}' }
            : {}),
        }),
        [...path],
        config,
        upstream,
      );
      expect(response.status).toBe(expectedStatus);
    }
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${journey}/signal-choices?retry=true`),
          [journey, 'signal-choices'],
          config,
          upstream,
        )
      ).status,
    ).toBe(400);
    expect(upstream).toHaveBeenCalledTimes(2);
    timeout.mockRestore();
  });

  it('caps expected-context proxy responses at 64 KiB and choice reads at 256 KiB', async () => {
    const journey = '00000000-0000-4000-8000-000000000001';
    const oversized = (size: number) =>
      new Response(new Uint8Array(size), { headers: { 'Content-Type': 'application/json' } });
    const upstream = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(oversized(64 * 1024 + 1))
      .mockResolvedValueOnce(oversized(256 * 1024 + 1));
    const post = new Request(
      `${config.origin}/api/v1/journeys/${journey}/signal-commands/expected-context`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Origin: config.origin },
        body: '{}',
      },
    );
    expect(
      (
        await proxyBrowserJourneys(
          post,
          [journey, 'signal-commands', 'expected-context'],
          config,
          upstream,
        )
      ).status,
    ).toBe(503);
    expect(
      (
        await proxyBrowserJourneys(
          new Request(`${config.origin}/api/v1/journeys/${journey}/signal-choices`),
          [journey, 'signal-choices'],
          config,
          upstream,
        )
      ).status,
    ).toBe(503);
    expect(upstream).toHaveBeenCalledTimes(2);
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
