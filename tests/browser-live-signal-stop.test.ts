import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BrowserLiveError, stopBrowserLiveSignalCommand } from '../apps/web/lib/browser-live';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const commandId = '00000000-0000-4000-8000-000000000003';
const otherId = '00000000-0000-4000-8000-000000000004';
const csrfToken = 'C'.repeat(32);

const receipt = {
  commandId,
  status: 'withdrawn' as const,
  receivedAt: '2026-09-19T10:00:00.123456789Z',
  expiresAt: '2026-09-19T10:15:00.123456789Z',
  retainUntil: '2026-09-20T10:00:00.123456789Z',
};

function csrfResponse(): Response {
  return Response.json({ token: csrfToken });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime('2030-01-01T00:00:00Z');
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('private browser signal command stop client', () => {
  it.each([
    ['no retained receipt', null],
    ['an older withdrawn receipt', receipt],
    ['an older superseded receipt', { ...receipt, status: 'superseded' as const }],
  ])('stops once with %s and preserves the terminal result', async (_label, retained) => {
    const result = { commandId, status: 'stopped' as const, receipt: retained };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(Response.json(result));
    vi.stubGlobal('fetch', fetcher);

    await expect(stopBrowserLiveSignalCommand(accountId, journeyId, commandId)).resolves.toEqual(
      result,
    );
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[1]).toEqual([
      `/api/v1/journeys/${journeyId}/signal-commands/${commandId}/stop`,
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
        body: '{}',
        signal: expect.any(AbortSignal),
      }),
    ]);
    expect(fetcher.mock.calls.some(([url]) => String(url).includes('/signals/'))).toBe(false);
  });

  it.each([
    [
      'active receipt',
      { commandId, status: 'stopped', receipt: { ...receipt, status: 'accepted' } },
    ],
    ['wrong wrapper status', { commandId, status: 'withdrawn', receipt: null }],
    ['wrong wrapper command', { commandId: otherId, status: 'stopped', receipt: null }],
    [
      'wrong receipt command',
      { commandId, status: 'stopped', receipt: { ...receipt, commandId: otherId } },
    ],
    [
      'malformed receipt lifetime',
      { commandId, status: 'stopped', receipt: { ...receipt, expiresAt: receipt.receivedAt } },
    ],
    ['extra wrapper field', { commandId, status: 'stopped', receipt: null, accepted: false }],
    ['missing receipt', { commandId, status: 'stopped' }],
  ] as const)('rejects malformed stop response: %s', async (_label, response) => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(Response.json(response));
    vi.stubGlobal('fetch', fetcher);
    await expect(
      stopBrowserLiveSignalCommand(accountId, journeyId, commandId),
    ).rejects.toMatchObject({ kind: 'unavailable' });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('rejects malformed command identity before CSRF or stop fetch', () => {
    const fetcher = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetcher);
    for (const invalid of [
      '00000000-0000-0000-0000-000000000000',
      'A0000000-0000-4000-8000-000000000003',
      `${commandId}\n`,
    ])
      expect(() => stopBrowserLiveSignalCommand(accountId, journeyId, invalid)).toThrow(
        BrowserLiveError,
      );
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('preserves cancellation after the stop is sent and does not retry or fall back', async () => {
    const response = deferred<Response>();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockReturnValueOnce(response.promise);
    vi.stubGlobal('fetch', fetcher);
    const controller = new AbortController();
    const operation = stopBrowserLiveSignalCommand(
      accountId,
      journeyId,
      commandId,
      controller.signal,
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(fetcher).toHaveBeenCalledTimes(2);
    controller.abort();
    await expect(operation).rejects.toMatchObject({ name: 'AbortError' });
    response.resolve(Response.json({ commandId, status: 'stopped', receipt: null }));
    await Promise.resolve();
    await Promise.resolve();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls.some(([url]) => String(url).includes('/signals/'))).toBe(false);
  });

  it('uses one 12-second deadline and does not retry a stalled stop', async () => {
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetcher);
    const operation = stopBrowserLiveSignalCommand(accountId, journeyId, commandId);
    const rejection = expect(operation).rejects.toMatchObject({ kind: 'unavailable' });
    await vi.advanceTimersByTimeAsync(12_000);
    await rejection;
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('retains the standard 64 KiB response bound', async () => {
    const valid = JSON.stringify({ commandId, status: 'stopped', receipt: null });
    const response = new Response(`${valid}${' '.repeat(64 * 1024)}`, {
      headers: { 'Content-Type': 'application/json' },
    });
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(response);
    vi.stubGlobal('fetch', fetcher);
    await expect(
      stopBrowserLiveSignalCommand(accountId, journeyId, commandId),
    ).rejects.toMatchObject({ kind: 'unavailable' });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
