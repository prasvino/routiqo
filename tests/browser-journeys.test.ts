import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  sendBrowserJourney,
  readBrowserJourney,
  readRecentBrowserJourneys,
} from '../apps/web/lib/browser-journeys';
const account = '00000000-0000-4000-8000-000000000001';
const id = '00000000-0000-4000-8000-000000000002';
const command = { journeyId: id, action: 'start' as const, kind: 'trip' as const };
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
function respond(response: Response) {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token: 'masked-csrf-token-for-testing' }))
    .mockResolvedValueOnce(response);
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}
describe('browser journey delivery', () => {
  it('bounds recent history and rejects duplicate or malformed records', async () => {
    const journey = {
      id,
      kind: 'trip',
      status: 'active',
      startedAt: '2026-09-08T12:00:00Z',
      completedAt: null,
    };
    const fetcher = vi.fn(async () => Response.json({ journeys: [journey], next: null }));
    vi.stubGlobal('fetch', fetcher);
    expect(await readRecentBrowserJourneys(account)).toHaveLength(1);
    expect(fetcher).toHaveBeenCalledWith(
      '/api/v1/journeys?limit=20',
      expect.objectContaining({ headers: { 'X-Routiqo-Account': account } }),
    );
    for (const journeys of [[journey, journey], Array(21).fill(journey), [{}]]) {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => Response.json({ journeys })),
      );
      await expect(readRecentBrowserJourneys(account)).rejects.toThrow();
    }
  });
  it('reads authoritative state with the account guard and strips unrelated fields', async () => {
    const fetcher = vi.fn(async () =>
      Response.json({
        id,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
        owner: 'private',
      }),
    );
    vi.stubGlobal('fetch', fetcher);
    const result = await readBrowserJourney(account, id);
    expect(result?.id).toBe(id);
    expect(result).not.toHaveProperty('owner');
    expect(fetcher).toHaveBeenCalledWith(
      `/api/v1/journeys/${id}`,
      expect.objectContaining({
        headers: { 'X-Routiqo-Account': account },
        cache: 'no-store',
        redirect: 'error',
      }),
    );
  });
  it('distinguishes missing records from unavailable or unrelated results', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 404 })),
    );
    expect(await readBrowserJourney(account, id)).toBeNull();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 401 })),
    );
    await expect(readBrowserJourney(account, id)).rejects.toThrow();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        Response.json({
          id: account,
          kind: 'trip',
          status: 'active',
          startedAt: '2026-09-08T12:00:00Z',
          completedAt: null,
        }),
      ),
    );
    await expect(readBrowserJourney(account, id)).rejects.toThrow('does not match');
  });

  it('discards a nonempty 404 response body without reading or leaking private content', async () => {
    const cancelSpy = vi.fn();
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('{"private":"secret-journey-content"}'));
      },
      cancel(reason) {
        cancelSpy(reason);
      },
    });
    const getReaderSpy = vi.spyOn(body, 'getReader');

    const fetcher = vi.fn(
      async () =>
        new Response(body, {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    vi.stubGlobal('fetch', fetcher);

    const result = await readBrowserJourney(account, id);
    expect(result).toBeNull();
    expect(cancelSpy).toHaveBeenCalledOnce();
    expect(getReaderSpy).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('returns null on 404 even when stream cancellation cleanup rejects', async () => {
    const privateCleanupError = new Error('private-stream-cleanup-failure');
    const cancelSpy = vi.fn(() => Promise.reject(privateCleanupError));
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('{"private":"secret-journey-content"}'));
      },
      cancel: cancelSpy,
    });
    const getReaderSpy = vi.spyOn(body, 'getReader');

    const fetcher = vi.fn(
      async () =>
        new Response(body, {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    vi.stubGlobal('fetch', fetcher);

    const result = await readBrowserJourney(account, id);
    expect(result).toBeNull();
    expect(cancelSpy).toHaveBeenCalledOnce();
    expect(getReaderSpy).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledOnce();
    await Promise.resolve();
  });

  it('binds the account partition and sends a stable retry identity', async () => {
    const fetcher = respond(
      Response.json({
        id,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
        credential: 'discard',
      }),
    );
    const delivered = await sendBrowserJourney(account, command);
    expect(delivered.outcome).toBe('success');
    expect(JSON.stringify(delivered)).not.toContain('credential');
    expect(fetcher.mock.calls[1]).toEqual([
      '/api/v1/journeys',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        redirect: 'error',
        body: JSON.stringify({ id, kind: 'trip' }),
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'masked-csrf-token-for-testing',
          'X-Routiqo-Account': account,
        },
      }),
    ]);
  });
  it('classifies retryable and blocked HTTP responses', async () => {
    for (const [status, outcome] of [
      [401, 'authentication'],
      [403, 'authentication'],
      [409, 'conflict'],
      [408, 'transient'],
      [429, 'transient'],
      [503, 'transient'],
      [400, 'rejected'],
      [404, 'rejected'],
    ] as const) {
      respond(new Response(null, { status }));
      expect(await sendBrowserJourney(account, command)).toEqual({ outcome });
    }
  });
  it('retains work for malformed or unrelated successful results', async () => {
    for (const body of [
      {},
      {
        id: account,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
      },
    ]) {
      respond(Response.json(body));
      expect(await sendBrowserJourney(account, command)).toEqual({ outcome: 'transient' });
    }
    respond(
      Response.json({
        id,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
      }),
    );
    expect(await sendBrowserJourney(account, { journeyId: id, action: 'complete' })).toEqual({
      outcome: 'transient',
    });
  });

  it('snapshots the command before delayed CSRF and sends exactly once', async () => {
    const csrf = deferred<Response>();
    const fetcher = vi
      .fn()
      .mockReturnValueOnce(csrf.promise)
      .mockResolvedValueOnce(
        Response.json({
          id,
          kind: 'trip',
          status: 'active',
          startedAt: '2026-09-08T12:00:00Z',
          completedAt: null,
        }),
      );
    vi.stubGlobal('fetch', fetcher);
    const mutable = { journeyId: id, action: 'start', kind: 'trip' };
    const operation = sendBrowserJourney(account, mutable as typeof command);
    mutable.journeyId = account;
    mutable.action = 'complete';
    mutable.kind = 'commute';
    csrf.resolve(Response.json({ token: 'masked-csrf-token-for-testing' }));

    await expect(operation).resolves.toMatchObject({ outcome: 'success' });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[1]).toEqual([
      '/api/v1/journeys',
      expect.objectContaining({ body: JSON.stringify({ id, kind: 'trip' }) }),
    ]);
  });

  it('does not start a late POST after the whole operation deadline', async () => {
    vi.useFakeTimers();
    const csrf = deferred<Response>();
    const fetcher = vi.fn().mockReturnValue(csrf.promise);
    vi.stubGlobal('fetch', fetcher);
    const operation = sendBrowserJourney(account, command);
    const outcome = operation.then((value) => value);

    await vi.advanceTimersByTimeAsync(12000);
    await expect(outcome).resolves.toEqual({ outcome: 'transient' });
    csrf.resolve(Response.json({ token: 'masked-csrf-token-for-testing' }));
    await Promise.resolve();
    await Promise.resolve();
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('gives the resource request only the deadline remaining after slow CSRF', async () => {
    vi.useFakeTimers();
    const csrf = deferred<Response>();
    const resource = new Promise<Response>(() => undefined);
    const fetcher = vi.fn().mockReturnValueOnce(csrf.promise).mockReturnValueOnce(resource);
    vi.stubGlobal('fetch', fetcher);
    let settled = false;
    const operation = sendBrowserJourney(account, command);
    const outcome = operation.then((value) => {
      settled = true;
      return value;
    });

    await vi.advanceTimersByTimeAsync(11000);
    expect(settled).toBe(false);
    csrf.resolve(Response.json({ token: 'masked-csrf-token-for-testing' }));
    await vi.advanceTimersByTimeAsync(999);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await expect(outcome).resolves.toEqual({ outcome: 'transient' });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('bounds UTF-8 bytes and treats malformed success responses as transient', async () => {
    respond(
      Response.json({
        id,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
        padding: '€'.repeat(1400),
      }),
    );
    await expect(sendBrowserJourney(account, command)).resolves.toEqual({ outcome: 'transient' });

    const responses = [
      new Response(new Uint8Array([0xff]), {
        headers: { 'Content-Type': 'application/json' },
      }),
      new Response('{', { headers: { 'Content-Type': 'application/json' } }),
      new Response('{}', { headers: { 'Content-Type': 'text/plain' } }),
    ];
    const redirected = Response.json({});
    Object.defineProperty(redirected, 'redirected', { value: true });
    responses.push(redirected);
    for (const response of responses) {
      respond(response);
      await expect(sendBrowserJourney(account, command)).resolves.toEqual({
        outcome: 'transient',
      });
    }
    respond(new Response(null, { status: 204 }));
    await expect(sendBrowserJourney(account, command)).resolves.toEqual({ outcome: 'rejected' });

    const rejectedCancellation = vi.fn(() => Promise.reject(new Error('private cancel failure')));
    const oversized = new ReadableStream<Uint8Array>({
      pull(controller) {
        controller.enqueue(new Uint8Array(4097));
      },
      cancel: rejectedCancellation,
    });
    respond(
      new Response(oversized, {
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    await expect(sendBrowserJourney(account, command)).resolves.toEqual({ outcome: 'transient' });
    await Promise.resolve();
    expect(rejectedCancellation).toHaveBeenCalledOnce();
  });

  it('bounds stalled headers and bodies without awaiting cancellation', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => undefined)),
    );
    const headers = readBrowserJourney(account, id).catch((failure: unknown) => failure);
    await vi.advanceTimersByTimeAsync(12000);
    await expect(headers).resolves.toEqual(new Error('Journey request is unavailable.'));

    const cancellation = vi.fn(() => new Promise<void>(() => undefined));
    const body = new ReadableStream<Uint8Array>({ start() {}, cancel: cancellation });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(body, { headers: { 'Content-Type': 'application/json' } })),
    );
    const streamed = readBrowserJourney(account, id).catch((failure: unknown) => failure);
    await vi.advanceTimersByTimeAsync(12000);
    await expect(streamed).resolves.toEqual(new Error('Journey request is unavailable.'));
    expect(cancellation).toHaveBeenCalledOnce();
  });

  it('uses elapsed checks to stop an immediate empty-chunk stream', async () => {
    let now = 0;
    let pulls = 0;
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    const cancellation = vi.fn();
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulls += 1;
        now += 2000;
        controller.enqueue(new Uint8Array());
      },
      cancel: cancellation,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(body, { headers: { 'Content-Type': 'application/json' } })),
    );

    await expect(readBrowserJourney(account, id)).rejects.toThrow(
      'Journey request is unavailable.',
    );
    expect(pulls).toBeGreaterThan(1);
    expect(cancellation).toHaveBeenCalledOnce();
  });

  it('bounds recent history response bytes before JSON parsing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ journeys: [], padding: '€'.repeat(Math.ceil(32768 / 3)) })),
    );
    await expect(readRecentBrowserJourneys(account)).rejects.toThrow(
      'Journey history is unavailable.',
    );
  });
});
