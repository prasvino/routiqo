import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AdminApiError,
  adminSession,
  exactTargetId,
  mutateGrant,
  parseReviewPage,
  parseTargetGrants,
  reviewQueue,
} from './admin-client';
import { formatGrantExpiry } from './grant-display';

afterEach(() => vi.unstubAllGlobals());

describe('moderator browser transport', () => {
  it('accepts only exact UUID targets and a bounded permission set', () => {
    const target = '10000000-0000-4000-8000-000000000001';
    expect(exactTargetId(` ${target.toUpperCase()} `)).toBe(target);
    expect(exactTargetId('alice@example.test')).toBeNull();
    const active = parseTargetGrants(
      {
        targetId: target,
        grants: [{ permission: 'traffic_review', expiresAt: '2026-09-23T10:15:00Z' }],
      },
      target,
    ).grants;
    expect(active).toHaveLength(1);
    expect(formatGrantExpiry(active[0]!.expiresAt, 'en-US', 'UTC')).toMatch(
      /Sep 23, 2026.*10:15.*UTC/,
    );
    expect(() =>
      parseTargetGrants(
        {
          targetId: target,
          grants: [{ permission: 'traffic_grant_admin', expiresAt: '2026-09-23T10:15:00Z' }],
        },
        target,
      ),
    ).toThrow(AdminApiError);
    expect(() =>
      parseTargetGrants(
        {
          targetId: target,
          grants: [
            { permission: 'traffic_review', expiresAt: '2026-09-23T10:15:00Z' },
            { permission: 'traffic_review', expiresAt: '2026-09-23T10:15:00Z' },
          ],
        },
        target,
      ),
    ).toThrow(AdminApiError);
  });

  it('keeps an exact mutation identity and sends only closed grant fields', async () => {
    const targetId = '10000000-0000-4000-8000-000000000001';
    const requestId = '20000000-0000-4000-8000-000000000002';
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response('{"token":"csrf"}', { headers: { 'Content-Type': 'application/json' } }),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            targetId,
            permission: 'traffic_review',
            expiresAt: '2026-09-23T10:15:00Z',
            requestId,
            replayed: true,
          }),
          { headers: { 'Content-Type': 'application/json' } },
        ),
      );
    vi.stubGlobal('fetch', fetcher);
    const result = await mutateGrant({
      accountId: targetId,
      targetId,
      action: 'issue',
      permission: 'traffic_review',
      durationMinutes: 15,
      reason: 'OPERATOR_TRIAL',
      requestId,
    });
    expect(result.replayed).toBe(true);
    expect(formatGrantExpiry(result.expiresAt!, 'en-US', 'UTC')).toMatch(
      /Sep 23, 2026.*10:15.*UTC/,
    );
    expect(fetcher.mock.calls[1]?.[0]).toBe(`/api/admin/traffic-grants/${targetId}/issue`);
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body)).toEqual({
      requestId,
      permission: 'traffic_review',
      durationMinutes: 15,
      reason: 'OPERATOR_TRIAL',
    });
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('csrf');
  });
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
