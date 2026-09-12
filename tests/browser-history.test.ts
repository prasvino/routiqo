import { afterEach, describe, expect, it, vi } from 'vitest';
import { BrowserAuthError } from '../apps/web/lib/browser-auth';
import { readBrowserJourneyPage, type JourneyHistoryCursor } from '../apps/web/lib/browser-history';

const account = '00000000-0000-4000-8000-000000000001';
const base = '2026-09-08T12:00:00.123456Z';
const id = (sequence: number) => `00000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}`;
const journey = (sequence: number, startedAt = base) => ({
  id: id(sequence),
  kind: 'trip',
  status: 'completed',
  startedAt,
  completedAt: '2026-09-08T13:00:00.123456Z',
});

afterEach(() => vi.unstubAllGlobals());

describe('browser journey history', () => {
  it('reads a bounded first page and accepts microsecond key ties in descending id order', async () => {
    const journeys = Array.from({ length: 20 }, (_, index) => journey(20 - index));
    const fetcher = vi.fn(async () =>
      Response.json({ journeys, next: { startedAt: base, id: id(1) } }),
    );
    vi.stubGlobal('fetch', fetcher);

    const page = await readBrowserJourneyPage(account);

    expect(page.journeys).toHaveLength(20);
    expect(page.next).toEqual({ startedAt: base, id: id(1) });
    expect(fetcher).toHaveBeenCalledWith('/api/v1/journeys?limit=20', {
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      headers: { 'X-Routiqo-Account': account },
      signal: expect.any(AbortSignal),
    });
  });

  it('sends a canonical cursor and rejects a page that does not progress beyond it', async () => {
    const cursor: JourneyHistoryCursor = {
      startedAt: '2026-09-08T12:00:00.123457Z',
      id: id(2),
    };
    const fetcher = vi.fn(async () => Response.json({ journeys: [journey(1)], next: null }));
    vi.stubGlobal('fetch', fetcher);
    await readBrowserJourneyPage(account, cursor);
    expect(fetcher.mock.calls[0]?.[0]).toBe(
      `/api/v1/journeys?limit=20&beforeStartedAt=2026-09-08T12%3A00%3A00.123457Z&beforeId=${id(2)}`,
    );

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ journeys: [journey(3, cursor.startedAt)], next: null })),
    );
    await expect(readBrowserJourneyPage(account, cursor)).rejects.toThrow('strictly descending');
  });

  it('rejects malformed cursors before making a request', async () => {
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    const invalid = [
      { startedAt: '2026-09-08T12:00:00Z', id: id(1) },
      { startedAt: '2026-02-30T12:00:00.000000Z', id: id(1) },
      { startedAt: base, id: 'AAAAAAAA-0000-4000-8000-000000000001' },
      { startedAt: base },
    ];
    for (const cursor of invalid)
      await expect(
        readBrowserJourneyPage(account, cursor as JourneyHistoryCursor),
      ).rejects.toThrow();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('rejects duplicate, unordered, and inconsistent continuation pages', async () => {
    const duplicate = journey(2);
    const full = Array.from({ length: 20 }, (_, index) => journey(20 - index));
    for (const body of [
      { journeys: [duplicate, duplicate], next: null },
      { journeys: [journey(1), journey(2)], next: null },
      { journeys: [journey(1)], next: { startedAt: base, id: id(1) } },
      { journeys: full, next: { startedAt: base, id: id(2) } },
    ]) {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => Response.json(body)),
      );
      await expect(readBrowserJourneyPage(account)).rejects.toThrow();
    }
  });

  it('accepts empty and terminal full pages while bounding body bytes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ journeys: [], next: null })),
    );
    await expect(readBrowserJourneyPage(account)).resolves.toEqual({ journeys: [], next: null });

    const journeys = Array.from({ length: 20 }, (_, index) => journey(20 - index));
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ journeys, next: null })),
    );
    await expect(readBrowserJourneyPage(account)).resolves.toMatchObject({ next: null });

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('x'.repeat(32 * 1024 + 1))),
    );
    await expect(readBrowserJourneyPage(account)).rejects.toThrow('exceeds its limit');
  });

  it('surfaces HTTP status and honors caller cancellation', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 401 })),
    );
    const failure = await readBrowserJourneyPage(account).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(BrowserAuthError);
    expect((failure as BrowserAuthError).status).toBe(401);

    const controller = new AbortController();
    controller.abort();
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    await expect(readBrowserJourneyPage(account, null, controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
    expect(fetcher).not.toHaveBeenCalled();
  });
});
