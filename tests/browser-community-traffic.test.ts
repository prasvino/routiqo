import { afterEach, expect, it, vi } from 'vitest';
import {
  readBrowserCommunityShares,
  readBrowserCommunityTraffic,
  reportBrowserCommunityTraffic,
  shareBrowserCommunityTraffic,
  stopBrowserCommunityTraffic,
} from '../apps/web/lib/browser-community-traffic';

const account = '00000000-0000-4000-8000-000000000001';
const journey = '00000000-0000-4000-8000-000000000002';
const command = '00000000-0000-4000-8000-000000000003';
const request = '00000000-0000-4000-8000-000000000004';
const candidate = '00000000-0000-4000-8000-000000000005';
const ref = '00000000-0000-4000-8000-000000000006';
const acceptedAt = '2026-09-23T12:00:00.000000000Z';
const windowEndsAt = '2026-09-23T12:05:00.000000000Z';
const csrf = 'C'.repeat(32);

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('sends only V3 purpose and exact owner Stop; no V18 request or replay occurs', async () => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json({ token: csrf }))
    .mockResolvedValueOnce(
      Response.json({
        candidateId: candidate,
        commandId: command,
        status: 'accepted_for_consideration',
        acceptedAt,
        windowEndsAt,
      }),
    )
    .mockResolvedValueOnce(Response.json({ token: csrf }))
    .mockResolvedValueOnce(
      Response.json({ commandId: command, status: 'stopped_for_future_sharing' }),
    );
  vi.stubGlobal('fetch', fetcher);
  await shareBrowserCommunityTraffic(account, journey, command, request);
  await stopBrowserCommunityTraffic(account, journey, command);
  expect(fetcher.mock.calls[1]?.[0]).toBe(
    `/api/v1/journeys/${journey}/signals/${command}/community-share`,
  );
  expect(fetcher.mock.calls[1]?.[1]).toEqual(
    expect.objectContaining({
      body: JSON.stringify({ requestId: request, purpose: 'community-traffic-v3' }),
    }),
  );
  expect(fetcher.mock.calls[3]?.[0]).toBe(
    `/api/v1/journeys/${journey}/signals/${command}/community-share/stop`,
  );
  expect(fetcher).toHaveBeenCalledTimes(4);
});

it('rejects contributor fields in canonical output and accepts 202 report response', async () => {
  const moment = {
    ref,
    areaLabel: 'Coarse road area',
    trafficValue: 'traffic_slow',
    observationPeriod: '12:00–12:05 UTC',
    expiresAt: new Date(Date.now() + 300_000).toISOString(),
    source: 'community',
    schemaVersion: 3,
  };
  const serverTime = new Date(Date.now()).toISOString();
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(
      Response.json({ schemaVersion: 3, serverTime, moments: [{ ...moment, count: 12 }] }),
    )
    .mockResolvedValueOnce(Response.json({ schemaVersion: 3, serverTime, moments: [moment] }))
    .mockResolvedValueOnce(Response.json({ token: csrf }))
    .mockResolvedValueOnce(Response.json({ status: 'received' }, { status: 202 }))
    .mockResolvedValueOnce(Response.json({ token: csrf }))
    .mockResolvedValueOnce(
      Response.json(
        {
          status: 'received',
          receivedAt: '2026-09-25T10:00:00Z',
          receiptExpiresAt: '2026-10-02T10:00:00Z',
        },
        { status: 202 },
      ),
    );
  vi.stubGlobal('fetch', fetcher);
  await expect(readBrowserCommunityTraffic(account, journey)).rejects.toMatchObject({
    kind: 'unavailable',
  });
  await expect(readBrowserCommunityTraffic(account, journey)).resolves.toMatchObject({
    moments: [expect.objectContaining(moment)],
  });
  // A receipt without its times is rejected rather than trusted.
  await expect(
    reportBrowserCommunityTraffic(account, journey, ref, 'UNSAFE', request),
  ).rejects.toMatchObject({ kind: 'unavailable' });
  await expect(
    reportBrowserCommunityTraffic(account, journey, ref, 'UNSAFE', request),
  ).resolves.toEqual({
    status: 'received',
    receivedAt: '2026-09-25T10:00:00Z',
    receiptExpiresAt: '2026-10-02T10:00:00Z',
  });
  expect(fetcher.mock.calls[3]?.[0]).toBe(
    `/api/v1/journeys/${journey}/community-traffic/${ref}/reports`,
  );
  expect(fetcher.mock.calls[3]?.[1]).toEqual(
    expect.objectContaining({
      body: JSON.stringify({ reason: 'UNSAFE', clientRequestId: request }),
    }),
  );
});

it('recovers only bounded owner handles and rejects injected source identity', async () => {
  const handle = {
    candidateId: candidate,
    journeyId: journey,
    commandId: command,
    requestId: request,
    status: 'accepted_for_consideration',
    acceptedAt,
    windowEndsAt,
  };
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json([handle]))
    .mockResolvedValueOnce(Response.json([{ ...handle, accountId: account }]));
  vi.stubGlobal('fetch', fetcher);
  await expect(readBrowserCommunityShares(account)).resolves.toEqual([handle]);
  await expect(readBrowserCommunityShares(account)).rejects.toMatchObject({ kind: 'unavailable' });
  expect(fetcher.mock.calls[0]?.[0]).toBe('/api/v1/community-shares');
});

it('keeps recovery readable when a handle has expired but is not yet cleaned up', async () => {
  const handle = {
    candidateId: candidate,
    journeyId: journey,
    commandId: command,
    requestId: request,
    status: 'expired',
    acceptedAt,
    windowEndsAt,
  };
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json([handle]))
    .mockResolvedValueOnce(Response.json([{ ...handle, status: 'published' }]));
  vi.stubGlobal('fetch', fetcher);
  await expect(readBrowserCommunityShares(account)).resolves.toEqual([handle]);
  await expect(readBrowserCommunityShares(account)).rejects.toMatchObject({ kind: 'unavailable' });
});

it('derives a conservative expiry deadline from server time even with a device clock five minutes slow', async () => {
  const serverTime = '2026-09-23T12:00:00.000000000Z';
  const expiresAt = '2026-09-23T12:00:00.600000000Z';
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse(serverTime) - 300_000);
  vi.spyOn(performance, 'now').mockReturnValue(10_000);
  const moment = {
    ref,
    areaLabel: 'Coarse road area',
    trafficValue: 'traffic_slow',
    observationPeriod: '12:00–12:05 UTC',
    expiresAt,
    source: 'community',
    schemaVersion: 3,
  };
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValue(Response.json({ schemaVersion: 3, serverTime, moments: [moment] }));
  vi.stubGlobal('fetch', fetcher);
  const result = await readBrowserCommunityTraffic(account, journey);
  expect(result.moments[0]?.deadlineMonotonic).toBe(10_600);
  expect(result.serverTime).toBe(serverTime);
});
