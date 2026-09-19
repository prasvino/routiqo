import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  acceptBrowserLiveSignalCommand,
  bindBrowserLiveRouteContext,
  BrowserLiveError,
  issueBrowserLiveSignalCommand,
  readBrowserLiveConsent,
  readBrowserLiveRouteContext,
  submitBrowserLiveConsent,
  withdrawBrowserLiveSignalCommand,
} from '../apps/web/lib/browser-live';

const accountId = '00000000-0000-0000-0000-000000000001';
const journeyId = '00000000-0000-0000-0000-000000000002';
const anchorId = '00000000-0000-0000-0000-000000000003';
const contextId = '00000000-0000-0000-0000-000000000004';
const commandId = '00000000-0000-0000-0000-000000000005';
const otherId = '00000000-0000-0000-0000-000000000006';
const maximumLong = '9223372036854775807';
const csrfToken = 'C'.repeat(32);
const issuedAt = '2026-09-19T10:00:00.123456789Z';
const contextExpiresAt = '2026-09-19T10:15:00.123456789Z';
const grantExpiresAt = '2026-09-19T10:01:30.123456789Z';
const evidenceExpiresAt = '2026-09-19T10:15:00.123456789Z';
const retainUntil = '2026-09-20T10:00:00.123456789Z';

const context = {
  contextId,
  revision: maximumLong,
  anchorIds: [anchorId, otherId],
  issuedAt,
  expiresAt: contextExpiresAt,
};

const grant = {
  commandId,
  anchorId,
  contextId,
  routeRevision: maximumLong,
  consentGeneration: maximumLong,
  categories: ['food_queue', 'parking', 'queue', 'restroom', 'traffic'],
  issuedAt,
  expiresAt: grantExpiresAt,
};

const receipt = {
  commandId,
  status: 'accepted',
  receivedAt: issuedAt,
  expiresAt: evidenceExpiresAt,
  retainUntil,
};

const binding = {
  mode: 'driving',
  origin: [80.1, 13.1],
  destination: [80.2, 13.2],
  alternativeIndex: 2,
  expectedContextId: null,
};

const acceptance = {
  anchorId,
  value: 'traffic_slow',
  contextId,
  routeRevision: maximumLong,
  consentGeneration: maximumLong,
};

function csrfResponse(): Response {
  return Response.json({ token: csrfToken });
}

function mutationFetch(body: unknown) {
  return vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(csrfResponse())
    .mockResolvedValueOnce(Response.json(body));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (failure: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('private browser LIVE clients', () => {
  it('reads exact owner consent and preserves maximum generation precision', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        journeyId,
        generation: maximumLong,
        sharing: false,
        journeyActive: true,
      }),
    );
    vi.stubGlobal('fetch', fetcher);

    await expect(readBrowserLiveConsent(accountId, journeyId)).resolves.toEqual({
      journeyId,
      generation: maximumLong,
      sharing: false,
      journeyActive: true,
    });
    expect(fetcher).toHaveBeenCalledWith(`/api/v1/journeys/${journeyId}/consent`, {
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: { 'X-Routiqo-Account': accountId },
      signal: expect.any(AbortSignal),
    });
  });

  it('submits one exact consent intent through bounded CSRF and snapshots mutable input', async () => {
    const csrf = deferred<Response>();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockReturnValueOnce(csrf.promise)
      .mockResolvedValueOnce(
        Response.json({ journeyId, generation: '1', sharing: true, journeyActive: true }),
      );
    vi.stubGlobal('fetch', fetcher);
    const input = { expectedGeneration: '0', sharing: true };

    const operation = submitBrowserLiveConsent(accountId, journeyId, input);
    input.expectedGeneration = '9';
    input.sharing = false;
    csrf.resolve(csrfResponse());

    await expect(operation).resolves.toMatchObject({ generation: '1', sharing: true });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[1]).toEqual([
      `/api/v1/journeys/${journeyId}/consent`,
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': csrfToken,
          'X-Routiqo-Account': accountId,
        },
        body: JSON.stringify({ expectedGeneration: '0', sharing: true }),
        signal: expect.any(AbortSignal),
      }),
    ]);
  });

  it('accepts completed disable and saturated active disable consent responses', async () => {
    for (const response of [
      { journeyId, generation: '0', sharing: false, journeyActive: false },
      { journeyId, generation: maximumLong, sharing: false, journeyActive: true },
    ]) {
      const fetcher = mutationFetch(response);
      vi.stubGlobal('fetch', fetcher);
      await expect(
        submitBrowserLiveConsent(accountId, journeyId, {
          expectedGeneration: maximumLong,
          sharing: false,
        }),
      ).resolves.toEqual(response);
    }
  });

  it('reads null context and validates bound and empty outcomes for every route mode', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(Response.json({ context: null })),
    );
    await expect(readBrowserLiveRouteContext(accountId, journeyId)).resolves.toEqual({
      context: null,
    });

    for (const [mode, response] of [
      ['driving', { status: 'bound', context }],
      ['walking', { status: 'no_route', context: null }],
      ['cycling', { status: 'no_eligible_anchors', context: null }],
    ] as const) {
      const fetcher = mutationFetch(response);
      vi.stubGlobal('fetch', fetcher);
      await expect(
        bindBrowserLiveRouteContext(accountId, journeyId, { ...binding, mode }),
      ).resolves.toEqual(response);
      expect(JSON.parse(fetcher.mock.calls[1]![1]!.body as string)).toEqual({ ...binding, mode });
    }
  });

  it('snapshots route coordinates before waiting for CSRF', async () => {
    const csrf = deferred<Response>();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockReturnValueOnce(csrf.promise)
      .mockResolvedValueOnce(Response.json({ status: 'no_route', context: null }));
    vi.stubGlobal('fetch', fetcher);
    const input = {
      ...binding,
      origin: [...binding.origin],
      destination: [...binding.destination],
    };
    const expected = JSON.stringify(input);

    const operation = bindBrowserLiveRouteContext(accountId, journeyId, input);
    input.origin[0] = 0;
    input.destination[1] = 0;
    csrf.resolve(csrfResponse());

    await expect(operation).resolves.toEqual({ status: 'no_route', context: null });
    expect(fetcher.mock.calls[1]![1]!.body).toBe(expected);
  });

  it('issues a grant with all sorted closed categories and raw nanosecond instants', async () => {
    const fetcher = mutationFetch(grant);
    vi.stubGlobal('fetch', fetcher);

    await expect(
      issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId }),
    ).resolves.toEqual(grant);
    expect(fetcher.mock.calls[1]![0]).toBe(`/api/v1/journeys/${journeyId}/signal-commands`);
    expect(JSON.parse(fetcher.mock.calls[1]![1]!.body as string)).toEqual({ anchorId });
  });

  it('accepts every closed signal value and retained receipts after evidence expiry', async () => {
    vi.useFakeTimers();
    vi.setSystemTime('2030-01-01T00:00:00Z');
    const values = [
      'queue_under_5',
      'queue_5_to_15',
      'queue_15_to_30',
      'queue_over_30',
      'traffic_moving',
      'traffic_slow',
      'traffic_very_slow',
      'traffic_stopped',
      'parking_available',
      'parking_filling',
      'parking_full',
      'food_queue_none',
      'food_queue_short',
      'food_queue_long',
      'restroom_usable',
      'restroom_busy',
      'restroom_problem_reported',
    ];
    for (const value of values) {
      const fetcher = mutationFetch(receipt);
      vi.stubGlobal('fetch', fetcher);
      await expect(
        acceptBrowserLiveSignalCommand(accountId, journeyId, commandId, {
          ...acceptance,
          value,
        }),
      ).resolves.toEqual(receipt);
      expect(fetcher).toHaveBeenCalledTimes(2);
    }
  });

  it('withdraws once and accepts both terminal receipt states', async () => {
    for (const status of ['withdrawn', 'superseded'] as const) {
      const response = { ...receipt, status };
      const fetcher = mutationFetch(response);
      vi.stubGlobal('fetch', fetcher);
      await expect(
        withdrawBrowserLiveSignalCommand(accountId, journeyId, commandId),
      ).resolves.toEqual(response);
      expect(fetcher).toHaveBeenCalledTimes(2);
      expect(fetcher.mock.calls[1]![0]).toBe(
        `/api/v1/journeys/${journeyId}/signals/${commandId}/withdraw`,
      );
      expect(fetcher.mock.calls[1]![1]!.body).toBe('{}');
    }
  });

  it('rejects malformed identities, exact shapes, coordinates, indices, enums and decimals before fetch', () => {
    const fetcher = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetcher);
    const invalidOperations = [
      () => readBrowserLiveConsent('00000000-0000-0000-0000-000000000000', journeyId),
      () => readBrowserLiveConsent('A0000000-0000-0000-0000-000000000001', journeyId),
      () => readBrowserLiveConsent(`${accountId}\n`, journeyId),
      () =>
        submitBrowserLiveConsent(accountId, journeyId, { expectedGeneration: '01', sharing: true }),
      () =>
        submitBrowserLiveConsent(accountId, journeyId, {
          expectedGeneration: '0\r\n',
          sharing: true,
        }),
      () =>
        submitBrowserLiveConsent(accountId, journeyId, {
          expectedGeneration: '0',
          sharing: true,
          extra: true,
        }),
      () => bindBrowserLiveRouteContext(accountId, journeyId, { ...binding, origin: [181, 0] }),
      () =>
        bindBrowserLiveRouteContext(accountId, journeyId, { ...binding, alternativeIndex: 1.5 }),
      () =>
        bindBrowserLiveRouteContext(accountId, journeyId, {
          ...binding,
          expectedContextId: undefined,
        }),
      () => issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId: 'private' }),
      () => issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId: `${anchorId}\u2028` }),
      () =>
        acceptBrowserLiveSignalCommand(accountId, journeyId, commandId, {
          ...acceptance,
          value: 'traffic_fast',
        }),
      () =>
        acceptBrowserLiveSignalCommand(accountId, journeyId, commandId, {
          ...acceptance,
          routeRevision: '9223372036854775808',
        }),
    ];
    for (const operation of invalidOperations) expect(operation).toThrow(BrowserLiveError);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('rejects cross-request response identities and invalid response relationships', async () => {
    const invalidResponses: Array<() => Promise<unknown>> = [
      () => {
        vi.stubGlobal(
          'fetch',
          mutationFetch({
            journeyId: otherId,
            generation: '1',
            sharing: true,
            journeyActive: true,
          }),
        );
        return submitBrowserLiveConsent(accountId, journeyId, {
          expectedGeneration: '0',
          sharing: true,
        });
      },
      () => {
        vi.stubGlobal('fetch', mutationFetch({ status: 'no_route', context }));
        return bindBrowserLiveRouteContext(accountId, journeyId, binding);
      },
      () => {
        vi.stubGlobal('fetch', mutationFetch({ ...grant, anchorId: otherId }));
        return issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId });
      },
      () => {
        vi.stubGlobal('fetch', mutationFetch({ ...receipt, commandId: otherId }));
        return acceptBrowserLiveSignalCommand(accountId, journeyId, commandId, acceptance);
      },
    ];

    for (const operation of invalidResponses) await expect(operation()).rejects.toThrow();
  });

  it('rejects unexpected fields, unsorted or duplicate lists, and invalid lifetimes', async () => {
    const cases = [
      () => {
        vi.stubGlobal('fetch', mutationFetch({ ...grant, private: true }));
        return issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId });
      },
      () => {
        vi.stubGlobal('fetch', mutationFetch({ ...grant, categories: ['traffic', 'queue'] }));
        return issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId });
      },
      () => {
        vi.stubGlobal('fetch', mutationFetch({ ...grant, categories: ['queue', 'queue'] }));
        return issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId });
      },
      () => {
        vi.stubGlobal(
          'fetch',
          mutationFetch({ ...grant, expiresAt: '2026-09-19T10:01:31.123456789Z' }),
        );
        return issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId });
      },
      () => {
        vi.stubGlobal(
          'fetch',
          mutationFetch({ ...grant, issuedAt: '2026-02-30T10:00:00.123456789Z' }),
        );
        return issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId });
      },
      () => {
        vi.stubGlobal(
          'fetch',
          mutationFetch({
            status: 'bound',
            context: {
              ...context,
              expiresAt: '2026-09-19T10:00:00.123456788Z',
            },
          }),
        );
        return bindBrowserLiveRouteContext(accountId, journeyId, binding);
      },
      () => {
        vi.stubGlobal(
          'fetch',
          mutationFetch({ ...receipt, retainUntil: '2026-09-20T10:00:00.123456788Z' }),
        );
        return acceptBrowserLiveSignalCommand(accountId, journeyId, commandId, acceptance);
      },
    ];
    for (const operation of cases) await expect(operation()).rejects.toThrow(BrowserLiveError);
  });

  it('bounds streamed JSON bytes and rejects malformed UTF-8 and non-JSON success', async () => {
    const oversized = new Response(new TextEncoder().encode(`{"padding":"${'x'.repeat(65536)}"}`), {
      headers: { 'Content-Type': 'application/json' },
    });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(oversized));
    await expect(readBrowserLiveConsent(accountId, journeyId)).rejects.toThrow(BrowserLiveError);

    const rejectedCancellation = vi.fn(() => Promise.reject(new Error('private cancel failure')));
    const hostileOversized = new ReadableStream<Uint8Array>({
      pull(controller) {
        controller.enqueue(new Uint8Array(65537));
      },
      cancel: rejectedCancellation,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(hostileOversized, {
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    await expect(readBrowserLiveConsent(accountId, journeyId)).rejects.toThrow(BrowserLiveError);
    await Promise.resolve();
    expect(rejectedCancellation).toHaveBeenCalledOnce();

    const oversizedCsrf = Response.json({ token: 'C'.repeat(4096) });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(oversizedCsrf));
    await expect(
      issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId }),
    ).rejects.toMatchObject({ kind: 'unavailable' });

    const malformed = new Response(new Uint8Array([0xff]), {
      headers: { 'Content-Type': 'application/json' },
    });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(malformed));
    await expect(readBrowserLiveConsent(accountId, journeyId)).rejects.toThrow(BrowserLiveError);

    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(new Response('{}', { headers: { 'Content-Type': 'text/plain' } })),
    );
    await expect(readBrowserLiveConsent(accountId, journeyId)).rejects.toThrow(BrowserLiveError);
  });

  it('rejects mocked redirects and classifies only bounded HTTP outcomes without reading bodies', async () => {
    const redirected = Response.json({
      journeyId,
      generation: '0',
      sharing: false,
      journeyActive: true,
    });
    Object.defineProperty(redirected, 'redirected', { value: true });
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(redirected));
    await expect(readBrowserLiveConsent(accountId, journeyId)).rejects.toMatchObject({
      kind: 'unavailable',
    });

    for (const [status, kind] of [
      [401, 'authentication'],
      [404, 'not_found'],
      [409, 'conflict'],
      [429, 'rate_limited'],
      [503, 'unavailable'],
    ] as const) {
      const body = new ReadableStream({
        pull() {
          throw new Error('error body must not be read');
        },
      });
      vi.stubGlobal(
        'fetch',
        vi.fn<typeof fetch>().mockResolvedValue(new Response(body, { status })),
      );
      await expect(readBrowserLiveConsent(accountId, journeyId)).rejects.toMatchObject({ kind });
    }
  });

  it('preserves caller AbortError and never starts a POST after delayed CSRF completes', async () => {
    const csrf = deferred<Response>();
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(csrf.promise);
    vi.stubGlobal('fetch', fetcher);
    const controller = new AbortController();
    const operation = issueBrowserLiveSignalCommand(
      accountId,
      journeyId,
      { anchorId },
      controller.signal,
    );
    controller.abort();

    await expect(operation).rejects.toMatchObject({ name: 'AbortError' });
    csrf.resolve(csrfResponse());
    await Promise.resolve();
    await Promise.resolve();
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('handles synchronous abort from an injected fetch and discards its late response', async () => {
    const controller = new AbortController();
    const cancelled = vi.fn();
    const response = new Response(
      new ReadableStream<Uint8Array>({
        cancel: cancelled,
      }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    const fetcher = vi.fn<typeof fetch>(() => {
      controller.abort();
      return Promise.resolve(response);
    });
    vi.stubGlobal('fetch', fetcher);

    await expect(
      readBrowserLiveConsent(accountId, journeyId, controller.signal),
    ).rejects.toMatchObject({ name: 'AbortError' });
    await Promise.resolve();
    expect(cancelled).toHaveBeenCalledOnce();

    const rejectingController = new AbortController();
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() => {
        rejectingController.abort();
        return Promise.reject(new Error('private transport failure'));
      }),
    );
    await expect(
      readBrowserLiveConsent(accountId, journeyId, rejectingController.signal),
    ).rejects.toMatchObject({ name: 'AbortError' });
    await Promise.resolve();
  });

  it('uses elapsed time to stop an immediate zero-byte stream that starves timers', async () => {
    let observedTime = 0;
    vi.spyOn(Date, 'now').mockImplementation(() => (observedTime += 1000));
    const emptyStream = new ReadableStream<Uint8Array>({
      pull(controller) {
        controller.enqueue(new Uint8Array());
      },
    });
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          new Response(emptyStream, { headers: { 'Content-Type': 'application/json' } }),
        ),
    );

    await expect(readBrowserLiveConsent(accountId, journeyId)).rejects.toMatchObject({
      kind: 'unavailable',
    });
  });

  it('times out uncooperative CSRF headers and response streams without retries', async () => {
    vi.useFakeTimers();
    const never = new Promise<Response>(() => undefined);
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(never);
    vi.stubGlobal('fetch', fetcher);
    const stalledCsrf = issueBrowserLiveSignalCommand(accountId, journeyId, { anchorId });
    const csrfRejection = expect(stalledCsrf).rejects.toMatchObject({ kind: 'unavailable' });
    await vi.advanceTimersByTimeAsync(12000);
    await csrfRejection;
    expect(fetcher).toHaveBeenCalledOnce();

    const cancellation = vi.fn(() => new Promise<void>(() => undefined));
    const stalledBody = new ReadableStream<Uint8Array>({ start() {}, cancel: cancellation });
    const response = new Response(stalledBody, { headers: { 'Content-Type': 'application/json' } });
    const bodyFetcher = vi.fn<typeof fetch>().mockResolvedValue(response);
    vi.stubGlobal('fetch', bodyFetcher);
    const stalledRead = readBrowserLiveConsent(accountId, journeyId);
    const bodyRejection = expect(stalledRead).rejects.toMatchObject({ kind: 'unavailable' });
    await vi.advanceTimersByTimeAsync(12000);
    await bodyRejection;
    expect(bodyFetcher).toHaveBeenCalledOnce();
    expect(cancellation).toHaveBeenCalledOnce();
  });

  it('uses one 30-second binding deadline across slow CSRF and the resource fetch', async () => {
    vi.useFakeTimers();
    const csrf = deferred<Response>();
    const resource = new Promise<Response>(() => undefined);
    const fetcher = vi
      .fn<typeof fetch>()
      .mockReturnValueOnce(csrf.promise)
      .mockReturnValueOnce(resource);
    vi.stubGlobal('fetch', fetcher);
    let settled = false;
    const operation = bindBrowserLiveRouteContext(accountId, journeyId, binding);
    const outcome = operation.then(
      () => {
        settled = true;
        return undefined;
      },
      (failure: unknown) => {
        settled = true;
        return failure;
      },
    );

    await vi.advanceTimersByTimeAsync(12000);
    expect(settled).toBe(false);
    csrf.resolve(csrfResponse());
    await vi.advanceTimersByTimeAsync(17999);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await expect(outcome).resolves.toMatchObject({ kind: 'unavailable' });
    expect(settled).toBe(true);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
