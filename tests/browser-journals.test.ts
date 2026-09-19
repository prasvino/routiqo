import { afterEach, expect, it, vi } from 'vitest';
import {
  BrowserJournalError,
  readBrowserTripJournal,
  saveBrowserTripJournal,
} from '../apps/web/lib/browser-journals';
import { proxyBrowserJourneys } from '../apps/web/lib/auth-proxy';
const account = '00000000-0000-4000-8000-000000000001';
const id = '00000000-0000-4000-8000-000000000002';
const edit = { title: 'Trip', notes: 'My notes', expectedVersion: 0, mutationId: account };
const result = {
  journey: {
    id,
    kind: 'trip',
    status: 'completed',
    startedAt: '2026-09-12T01:00:00Z',
    completedAt: '2026-09-12T02:00:00Z',
  },
  annotation: {
    title: edit.title,
    notes: edit.notes,
    version: 1,
    updatedAt: '2026-09-12T03:00:00Z',
  },
};
const csrf = 'synthetic-journal-csrf-for-tests';

const jsonResponse = (body: BodyInit, status = 200) =>
  new Response(body, { status, headers: { 'Content-Type': 'application/json' } });

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
it('reads an owner-bound journal and rejects another journey response', async () => {
  const fetcher = vi.fn(async () => Response.json(result));
  vi.stubGlobal('fetch', fetcher);
  expect((await readBrowserTripJournal(account, id)).annotation.notes).toBe(edit.notes);
  expect(fetcher).toHaveBeenCalledWith(
    `/api/v1/journeys/${id}/journal`,
    expect.objectContaining({ headers: { 'X-Routiqo-Account': account }, cache: 'no-store' }),
  );
  await expect(readBrowserTripJournal(account, account)).rejects.toMatchObject({ status: 503 });
});
it('preserves the supplied mutation ID and rejects mismatched acknowledgements', async () => {
  for (const version of [1, 2]) {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'synthetic-journal-csrf-for-tests' }))
      .mockResolvedValueOnce(
        Response.json({ ...result, annotation: { ...result.annotation, version } }),
      );
    vi.stubGlobal('fetch', fetcher);
    if (version === 1)
      expect((await saveBrowserTripJournal(account, id, edit)).annotation.version).toBe(1);
    else
      await expect(saveBrowserTripJournal(account, id, edit)).rejects.toMatchObject({
        status: 503,
      });
    expect(fetcher).toHaveBeenLastCalledWith(
      `/api/v1/journeys/${id}/journal`,
      expect.objectContaining({ method: 'POST', body: JSON.stringify(edit) }),
    );
  }
});
it('preserves conflict/auth outcomes and bounds oversized responses', async () => {
  for (const status of [401, 404, 409, 429]) {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status })),
    );
    await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status });
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => jsonResponse(JSON.stringify({ padding: 'é'.repeat(16_385) }))),
  );
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });
});

it('requires exact JSON success responses and rejects redirects', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(JSON.stringify(result), 201)));
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });

  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(result))));
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });

  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(new Response(null, { headers: { 'Content-Type': 'application/json' } })),
  );
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });

  const redirected = Response.json(result);
  Object.defineProperty(redirected, 'redirected', { value: true });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(redirected));
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });
});

it('streams at most 32 KiB and strictly decodes UTF-8 JSON', async () => {
  const encoder = new TextEncoder();
  const exactBase = JSON.stringify({ ...result, padding: '' });
  const exact = JSON.stringify({
    ...result,
    padding: 'x'.repeat(32 * 1024 - encoder.encode(exactBase).byteLength),
  });
  expect(encoder.encode(exact)).toHaveLength(32 * 1024);
  const exactBytes = encoder.encode(exact);
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(
        new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(exactBytes.subarray(0, 10_000));
            controller.enqueue(exactBytes.subarray(10_000));
            controller.close();
          },
        }),
        { headers: { 'Content-Type': 'application/problem+json; charset=utf-8' } },
      ),
    ),
  );
  await expect(readBrowserTripJournal(account, id)).resolves.toMatchObject({
    journey: { id },
    annotation: { title: edit.title, notes: edit.notes, version: 1 },
  });

  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(`${exact} `)));
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });

  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(jsonResponse(new Uint8Array([0x7b, 0xc3, 0x28, 0x7d]))),
  );
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });

  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse('{')));
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });
});

it('redacts HTTP error bodies and ignores rejected cancellation cleanup', async () => {
  const privateFailure = new Error('private upstream journal detail');
  const response = new Response(
    new ReadableStream<Uint8Array>({ cancel: () => Promise.reject(privateFailure) }),
    { status: 409 },
  );
  const cancel = vi.spyOn(response.body!, 'cancel');
  const getReader = vi.spyOn(response.body!, 'getReader');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));

  const pending = readBrowserTripJournal(account, id);
  await expect(pending).rejects.toMatchObject({ status: 409 });
  await expect(pending).rejects.not.toHaveProperty('cause');
  expect(cancel).toHaveBeenCalledOnce();
  expect(getReader).not.toHaveBeenCalled();
  await Promise.resolve();

  const readerCancel = vi.fn(() => Promise.reject(privateFailure));
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(
        new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(new Uint8Array(32 * 1024 + 1));
          },
          cancel: readerCancel,
        }),
        { headers: { 'Content-Type': 'application/json' } },
      ),
    ),
  );
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });
  expect(readerCancel).toHaveBeenCalledOnce();
  await Promise.resolve();
});

it('bounds stalled headers and observes a late fetch failure', async () => {
  vi.useFakeTimers();
  let rejectFetch!: (reason: Error) => void;
  const fetcher = vi.fn().mockImplementation(
    () =>
      new Promise<Response>((_, reject) => {
        rejectFetch = reject;
      }),
  );
  vi.stubGlobal('fetch', fetcher);

  const pending = readBrowserTripJournal(account, id);
  const rejection = expect(pending).rejects.toMatchObject({ status: 503 });
  await vi.advanceTimersByTimeAsync(18_000);
  await rejection;
  expect(fetcher).toHaveBeenCalledOnce();
  expect((fetcher.mock.calls[0]?.[1] as RequestInit).signal?.aborted).toBe(true);
  rejectFetch(new Error('late journal transport failure'));
  await Promise.resolve();
});

it('bounds stalled body reads without awaiting hanging cancellation', async () => {
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

  const pending = readBrowserTripJournal(account, id);
  const rejection = expect(pending).rejects.toMatchObject({ status: 503 });
  await vi.advanceTimersByTimeAsync(18_000);
  await rejection;
  expect(cancel).toHaveBeenCalledOnce();
});

it('uses elapsed checks when immediate empty chunks starve timer callbacks', async () => {
  let now = 0;
  vi.spyOn(Date, 'now').mockImplementation(() => now);
  const cancel = vi.fn();
  const pull = vi.fn((controller: ReadableStreamDefaultController<Uint8Array>) => {
    now += 1_000;
    controller.enqueue(new Uint8Array());
  });
  const body = new ReadableStream<Uint8Array>({
    pull,
    cancel,
  });
  const getReader = vi.spyOn(body, 'getReader');
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(new Response(body, { headers: { 'Content-Type': 'application/json' } })),
  );

  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });
  expect(getReader).toHaveBeenCalledOnce();
  expect(pull.mock.calls.length).toBeGreaterThan(1);
  expect(cancel).toHaveBeenCalledOnce();
});

it('handles synchronous caller cancellation and discards the late response', async () => {
  const controller = new AbortController();
  const lateResponse = Response.json(result);
  const cancel = vi.spyOn(lateResponse.body!, 'cancel');
  const fetcher = vi.fn().mockImplementation(() => {
    controller.abort();
    return Promise.resolve(lateResponse);
  });
  vi.stubGlobal('fetch', fetcher);

  await expect(readBrowserTripJournal(account, id, controller.signal)).rejects.toMatchObject({
    name: 'AbortError',
    message: 'Journal request cancelled.',
  });
  await Promise.resolve();
  expect(cancel).toHaveBeenCalledOnce();
});

it('does not POST after caller cancellation while CSRF finishes independently', async () => {
  let resolveCsrf!: (response: Response) => void;
  const fetcher = vi.fn().mockImplementation(
    () =>
      new Promise<Response>((resolve) => {
        resolveCsrf = resolve;
      }),
  );
  vi.stubGlobal('fetch', fetcher);
  const controller = new AbortController();

  const pending = saveBrowserTripJournal(account, id, edit, controller.signal);
  const rejection = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  controller.abort();
  await rejection;
  resolveCsrf(Response.json({ token: csrf }));
  for (let flush = 0; flush < 8; flush++) await Promise.resolve();
  expect(fetcher).toHaveBeenCalledOnce();
});

it('shares the 18-second budget across slow CSRF and the journal POST', async () => {
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

  const pending = saveBrowserTripJournal(account, id, edit);
  const rejection = expect(pending).rejects.toBeInstanceOf(BrowserJournalError);
  await vi.advanceTimersByTimeAsync(11_000);
  expect(fetcher).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(6_999);
  expect((fetcher.mock.calls[1]?.[1] as RequestInit).signal?.aborted).toBe(false);
  await vi.advanceTimersByTimeAsync(1);
  await rejection;
  expect((fetcher.mock.calls[1]?.[1] as RequestInit).signal?.aborted).toBe(true);
  expect(fetcher).toHaveBeenCalledTimes(2);
});

it('snapshots the serialized edit before awaiting CSRF and does not retry', async () => {
  let resolveCsrf!: (response: Response) => void;
  const fetcher = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          resolveCsrf = resolve;
        }),
    )
    .mockResolvedValueOnce(Response.json(result));
  vi.stubGlobal('fetch', fetcher);
  const mutable = { ...edit };

  const pending = saveBrowserTripJournal(account, id, mutable);
  mutable.title = 'Changed after dispatch';
  resolveCsrf(Response.json({ token: csrf }));
  await expect(pending).resolves.toMatchObject({
    journey: { id },
    annotation: { title: edit.title, notes: edit.notes, version: 1 },
  });
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(fetcher.mock.calls[1]).toEqual([
    `/api/v1/journeys/${id}/journal`,
    expect.objectContaining({
      method: 'POST',
      body: JSON.stringify(edit),
      redirect: 'error',
      credentials: 'same-origin',
      cache: 'no-store',
    }),
  ]);
});
it('allows only GET and POST on the fixed journal suffix without query parameters', async () => {
  const config = {
    clientId: 'test.apps.googleusercontent.com',
    upstream: 'http://localhost:8080',
    origin: 'http://localhost:3000',
  };
  const fetcher = vi.fn(async () => Response.json(result));
  for (const method of ['GET', 'POST']) {
    const request = new Request(`http://localhost:3000/api/v1/journeys/${id}/journal`, {
      method,
      headers: { Origin: config.origin, 'Content-Type': 'application/json' },
      ...(method === 'POST' ? { body: JSON.stringify(edit) } : {}),
    });
    expect((await proxyBrowserJourneys(request, [id, 'journal'], config, fetcher)).status).toBe(
      200,
    );
  }
  expect(
    (
      await proxyBrowserJourneys(
        new Request(`http://localhost:3000/api/v1/journeys/${id}/journal`, { method: 'DELETE' }),
        [id, 'journal'],
        config,
        fetcher,
      )
    ).status,
  ).toBe(405);
  expect(
    (
      await proxyBrowserJourneys(
        new Request(`http://localhost:3000/api/v1/journeys/${id}/journal?owner=other`),
        [id, 'journal'],
        config,
        fetcher,
      )
    ).status,
  ).toBe(400);
  expect(fetcher).toHaveBeenCalledTimes(2);
});
