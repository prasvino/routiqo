import { afterEach, describe, expect, it, vi } from 'vitest';
import { AdminApiError, adminSession, parseReviewPage, reviewQueue } from './admin-client';

afterEach(() => vi.unstubAllGlobals());

describe('moderator browser transport', () => {
  it('accepts a bounded investigable row and removes evidence details when unavailable', () => {
    const ref = '10000000-0000-4000-8000-000000000001';
    const counts = { INACCURATE: 2, UNSAFE: 0, SPAM: 1 };
    const result = parseReviewPage({
      items: [
        {
          ref,
          reasonCounts: counts,
          evidenceStatus: 'AVAILABLE',
          areaLabel: 'Reviewed road area',
          trafficValue: 'TRAFFIC_SLOW',
          observationPeriod: '10:00–10:05 UTC',
          expiresAt: '2026-09-23T10:15:00Z',
        },
        {
          ref: '10000000-0000-4000-8000-000000000002',
          reasonCounts: counts,
          evidenceStatus: 'EVIDENCE_UNAVAILABLE',
          areaLabel: 'should never render',
        },
      ],
      nextCursor: null,
    });
    expect(result.items).toHaveLength(2);
    expect(result.items[1]).not.toHaveProperty('areaLabel');
  });

  it('rejects source-rich malformed rows and excessive page size', () => {
    expect(() =>
      parseReviewPage({
        items: [
          {
            ref: 'bad',
            reasonCounts: { INACCURATE: 1, UNSAFE: 0, SPAM: 0 },
            evidenceStatus: 'AVAILABLE',
            areaLabel: 'Area',
          },
        ],
        nextCursor: null,
      }),
    ).toThrow(AdminApiError);
    expect(() =>
      parseReviewPage({ items: Array.from({ length: 21 }, () => ({})), nextCursor: null }),
    ).toThrow(AdminApiError);
  });

  it('uses no-store same-origin requests and rejects browser redirects', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(null, { status: 302 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(adminSession()).rejects.toMatchObject({ status: 302 });
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
    });
  });

  it('fails a stalled browser response body on the same request deadline', async () => {
    const nativeAbortSignal = AbortSignal;
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              controller.enqueue(new TextEncoder().encode('{'));
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );
    vi.stubGlobal('AbortSignal', { timeout: () => nativeAbortSignal.abort() });
    await expect(reviewQueue()).rejects.toMatchObject({ status: 503 });
  });
});
