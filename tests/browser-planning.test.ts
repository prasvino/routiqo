import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAccountPlanningWrite, type JourneyPlan } from '../packages/shared/src';
import {
  BrowserPlanningError,
  deleteBrowserAccountPlanning,
  readBrowserAccountPlanning,
  saveBrowserAccountPlanning,
} from '../apps/web/lib/browser-planning';

const account = '00000000-0000-4000-8000-000000000001';
const mutation = '0f8fad5b-d9cb-469f-a165-70867728950e';
const csrf = 'synthetic-planning-csrf-for-tests';
const plan: JourneyPlan = {
  id: 'p1',
  kind: 'commute',
  origin: 'Navalur',
  destination: 'DLF Chennai',
  date: '2026-09-28',
  time: '08:15',
  days: [1, 2, 3, 4, 5],
  notes: 'Leave by eight',
  createdAt: '2026-09-25T06:00:00.000Z',
};
const write = createAccountPlanningWrite(
  { version: 1, plans: [plan], saved: ['ooty'] },
  2,
  mutation,
);
const stored = {
  present: true,
  version: 3,
  updatedAt: '2026-09-25T07:00:00.123456Z',
  plans: [plan],
  saved: ['ooty'],
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

function server(planning: (init: RequestInit) => Response | Promise<Response>) {
  const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/csrf') return json({ token: csrf });
    return planning(init);
  });
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('browser account planning transport', () => {
  it('reads with the account header, no cookies beyond same-origin and no redirects', async () => {
    const fetcher = server(() => json(stored));
    const copy = await readBrowserAccountPlanning(account);
    expect(copy.version).toBe(3);
    expect(copy.state.plans).toEqual([plan]);
    expect(fetcher).toHaveBeenCalledWith(
      '/api/v1/planning',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: { 'X-Routiqo-Account': account },
      }),
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('treats malformed, oversized or wrong-type read responses as unavailable', async () => {
    for (const response of [
      () => json({ ...stored, plans: [{ id: 'bad' }] }),
      () => json({ ...stored, present: false }),
      () => json({ ...stored, version: 0 }),
      () => new Response(JSON.stringify(stored), { headers: { 'Content-Type': 'text/html' } }),
      () => new Response('{', { headers: { 'Content-Type': 'application/json' } }),
      () =>
        new Response(`{"x":"${'a'.repeat(330 * 1024)}"}`, {
          headers: { 'Content-Type': 'application/json' },
        }),
      () => new Response(null, { status: 503 }),
    ]) {
      server(response);
      await expect(readBrowserAccountPlanning(account)).rejects.toMatchObject({
        kind: 'unavailable',
      });
    }
  });

  it.each([
    [401, 'session'],
    [403, 'session'],
    [404, 'unavailable'],
    [409, 'conflict'],
    [413, 'too-large'],
    [400, 'invalid'],
    [429, 'rate'],
    [500, 'uncertain'],
    [503, 'uncertain'],
  ])('maps write status %s to %s without reading the body', async (status, kind) => {
    server(() => new Response('ignored', { status }));
    await expect(saveBrowserAccountPlanning(account, write)).rejects.toMatchObject({ kind });
  });

  it('sends one exact CSRF-protected write and accepts only the matching acknowledgement', async () => {
    const fetcher = server(() => json(stored));
    const copy = await saveBrowserAccountPlanning(account, write);
    expect(copy.version).toBe(3);
    const call = fetcher.mock.calls.find(([url]) => url === '/api/v1/planning')!;
    expect(call[1]).toMatchObject({
      method: 'POST',
      headers: {
        'X-Routiqo-Account': account,
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrf,
      },
    });
    expect(JSON.parse(call[1]!.body as string)).toEqual(write);

    for (const mismatch of [
      { ...stored, version: 4 },
      { ...stored, plans: [{ ...plan, notes: 'changed' }] },
      { ...stored, saved: [] },
      { ...stored, plans: [{ id: 'bad' }] },
      { ...stored, present: false, updatedAt: null, plans: [], saved: [] },
    ]) {
      server(() => json(mismatch));
      await expect(saveBrowserAccountPlanning(account, write)).rejects.toMatchObject({
        kind: 'uncertain',
      });
    }
  });

  it('reports network failures after sending as uncertain and before sending as unavailable', async () => {
    server(() => Promise.reject(new TypeError('network')));
    await expect(saveBrowserAccountPlanning(account, write)).rejects.toMatchObject({
      kind: 'uncertain',
    });
    await expect(readBrowserAccountPlanning(account)).rejects.toMatchObject({
      kind: 'unavailable',
    });
    const fetcher = vi.fn(async (url: string) =>
      url === '/api/v1/auth/csrf' ? new Response(null, { status: 503 }) : json(stored),
    );
    vi.stubGlobal('fetch', fetcher);
    await expect(saveBrowserAccountPlanning(account, write)).rejects.toMatchObject({
      kind: 'unavailable',
    });
    expect(fetcher.mock.calls.every(([url]) => url === '/api/v1/auth/csrf')).toBe(true);
  });

  it('refuses oversized writes before any request', async () => {
    const fetcher = server(() => json(stored));
    // Over the 256 KiB document limit but under the 264 KiB request limit, then over both.
    for (const size of [257 * 1024, 270 * 1024]) {
      const huge = { ...write, plans: [{ ...plan, notes: 'a'.repeat(size) }] };
      await expect(saveBrowserAccountPlanning(account, huge)).rejects.toMatchObject({
        kind: 'too-large',
      });
    }
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('turns caller aborts into AbortError and deadline expiry into an uncertain write', async () => {
    const controller = new AbortController();
    server(
      (init) =>
        new Promise<Response>((_, reject) =>
          init.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          ),
        ),
    );
    const pending = saveBrowserAccountPlanning(account, write, controller.signal);
    await vi.waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalledTimes(2));
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });

    vi.useFakeTimers();
    const expiring = saveBrowserAccountPlanning(account, write);
    const settled = expect(expiring).rejects.toMatchObject({ kind: 'uncertain' });
    await vi.advanceTimersByTimeAsync(20_001);
    await settled;
  });

  it('removes with the checked version and returns the resulting absent state', async () => {
    const removed = { present: false, version: 4, updatedAt: null, plans: [], saved: [] };
    const fetcher = server(() => json(removed));
    expect(await deleteBrowserAccountPlanning(account, 3)).toMatchObject({
      present: false,
      version: 4,
    });
    const call = fetcher.mock.calls.find(([url]) => url === '/api/v1/planning/delete')!;
    expect(JSON.parse(call[1]!.body as string)).toEqual({ expectedVersion: 3 });
    server(() => new Response(null, { status: 409 }));
    await expect(deleteBrowserAccountPlanning(account, 3)).rejects.toMatchObject({
      kind: 'conflict',
    });
    for (const response of [
      () => json({}),
      () => new Response(null, { status: 204 }),
      () => json(stored),
      () => json({ ...removed, version: 2 }),
    ]) {
      server(response);
      await expect(deleteBrowserAccountPlanning(account, 3)).rejects.toMatchObject({
        kind: 'uncertain',
      });
    }
    await expect(deleteBrowserAccountPlanning(account, 0)).rejects.toThrow();
  });

  it('rejects invalid account identities without a request', async () => {
    const fetcher = server(() => json(stored));
    await expect(readBrowserAccountPlanning('not-an-account')).rejects.toBeInstanceOf(Error);
    await expect(readBrowserAccountPlanning('not-an-account')).rejects.not.toBeInstanceOf(
      BrowserPlanningError,
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
});
