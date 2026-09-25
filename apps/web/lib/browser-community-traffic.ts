import type { components } from '@routiqo/api-client';
import { BrowserLiveError, liveRequest, readInstant, readUuid } from './browser-live-private';

export type CommunityShareRequest = components['schemas']['CommunityTrafficShareRequestV3'];
export type CommunityShare = components['schemas']['CommunityTrafficShareResponseV3'];
export type CommunityShareHandle = components['schemas']['CommunityTrafficShareHandleV3'];
export type CommunityTrafficMoment = components['schemas']['CommunityTrafficMomentV3'];
export type CommunityTrafficRead = components['schemas']['CommunityTrafficReadV3'];
export type CommunityTrafficTimedMoment = CommunityTrafficMoment & { deadlineMonotonic: number };
export type CommunityTrafficSnapshot = Omit<CommunityTrafficRead, 'moments'> & {
  moments: CommunityTrafficTimedMoment[];
};
export type CommunityReportReason =
  components['schemas']['CommunityTrafficReportRequestV3']['reason'];

const trafficValues = new Set([
  'traffic_moving',
  'traffic_slow',
  'traffic_very_slow',
  'traffic_stopped',
]);
const statuses = new Set(['accepted_for_consideration', 'stopped_for_future_sharing']);
const shareStatuses = new Set([...statuses, 'expired']);
const reasons = new Set(['INACCURATE', 'UNSAFE', 'SPAM']);

function record(value: unknown, keys: string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    throw new BrowserLiveError('unavailable');
  const item = value as Record<string, unknown>;
  if (
    Object.keys(item).length !== keys.length ||
    Object.keys(item).some((key) => !keys.includes(key))
  )
    throw new BrowserLiveError('unavailable');
  return item;
}

function moment(value: unknown): CommunityTrafficMoment {
  const item = record(value, [
    'ref',
    'areaLabel',
    'trafficValue',
    'observationPeriod',
    'expiresAt',
    'source',
    'schemaVersion',
  ]);
  const label = item.areaLabel;
  const period = item.observationPeriod;
  if (
    typeof label !== 'string' ||
    label.length < 1 ||
    label.length > 80 ||
    typeof period !== 'string' ||
    period.length < 1 ||
    period.length > 40 ||
    !trafficValues.has(String(item.trafficValue)) ||
    item.source !== 'community' ||
    item.schemaVersion !== 3
  )
    throw new BrowserLiveError('unavailable');
  return {
    ref: readUuid(item.ref),
    areaLabel: label,
    trafficValue: item.trafficValue as CommunityTrafficMoment['trafficValue'],
    observationPeriod: period,
    expiresAt: readInstant(item.expiresAt).raw,
    source: 'community',
    schemaVersion: 3,
  };
}

function readMoments(value: unknown): CommunityTrafficRead {
  const item = record(value, ['schemaVersion', 'serverTime', 'moments']);
  if (item.schemaVersion !== 3 || !Array.isArray(item.moments) || item.moments.length > 100)
    throw new BrowserLiveError('unavailable');
  const serverTime = readInstant(item.serverTime);
  const moments = item.moments
    .map(moment)
    .filter((row) => readInstant(row.expiresAt).nanoseconds > serverTime.nanoseconds);
  if (new Set(moments.map((row) => row.ref)).size !== moments.length)
    throw new BrowserLiveError('unavailable');
  return { schemaVersion: 3, serverTime: serverTime.raw, moments };
}

function shareResponse(value: unknown, commandId: string): CommunityShare {
  const item = record(value, ['candidateId', 'commandId', 'status', 'acceptedAt', 'windowEndsAt']);
  if (readUuid(item.commandId) !== commandId || !shareStatuses.has(String(item.status)))
    throw new BrowserLiveError('unavailable');
  return {
    candidateId: readUuid(item.candidateId),
    commandId,
    status: item.status as CommunityShare['status'],
    acceptedAt: readInstant(item.acceptedAt).raw,
    windowEndsAt: readInstant(item.windowEndsAt).raw,
  };
}

function shareHandle(value: unknown): CommunityShareHandle {
  const item = record(value, [
    'candidateId',
    'journeyId',
    'commandId',
    'requestId',
    'status',
    'acceptedAt',
    'windowEndsAt',
  ]);
  if (!statuses.has(String(item.status))) throw new BrowserLiveError('unavailable');
  return {
    candidateId: readUuid(item.candidateId),
    journeyId: readUuid(item.journeyId),
    commandId: readUuid(item.commandId),
    requestId: readUuid(item.requestId),
    status: item.status as CommunityShareHandle['status'],
    acceptedAt: readInstant(item.acceptedAt).raw,
    windowEndsAt: readInstant(item.windowEndsAt).raw,
  };
}

export async function readBrowserCommunityTraffic(
  accountId: string,
  journeyId: string,
  signal?: AbortSignal,
): Promise<CommunityTrafficSnapshot> {
  accountId = readUuid(accountId);
  journeyId = readUuid(journeyId);
  // Starting the deadline before the request conservatively accounts for network transit.
  const startedAtMonotonic = performance.now();
  const feed = await liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/community-traffic`,
    timeoutMilliseconds: 12000,
    signal,
    validate: readMoments,
  });
  const serverTime = readInstant(feed.serverTime).nanoseconds;
  return {
    ...feed,
    moments: feed.moments.map((row) => ({
      ...row,
      deadlineMonotonic:
        startedAtMonotonic +
        Number((readInstant(row.expiresAt).nanoseconds - serverTime) / 1_000_000n),
    })),
  };
}

export function readBrowserCommunityShares(
  accountId: string,
  signal?: AbortSignal,
): Promise<CommunityShareHandle[]> {
  accountId = readUuid(accountId);
  return liveRequest({
    accountId,
    path: '/api/v1/community-shares',
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => {
      if (!Array.isArray(value) || value.length > 100) throw new BrowserLiveError('unavailable');
      const rows = value.map(shareHandle);
      if (new Set(rows.map((row) => row.candidateId)).size !== rows.length)
        throw new BrowserLiveError('unavailable');
      return rows;
    },
  });
}

export function shareBrowserCommunityTraffic(
  accountId: string,
  journeyId: string,
  commandId: string,
  requestId: string,
  signal?: AbortSignal,
) {
  accountId = readUuid(accountId);
  journeyId = readUuid(journeyId);
  commandId = readUuid(commandId);
  requestId = readUuid(requestId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signals/${commandId}/community-share`,
    body: { requestId, purpose: 'community-traffic-v3' } satisfies CommunityShareRequest,
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => shareResponse(value, commandId),
  });
}

export function stopBrowserCommunityTraffic(
  accountId: string,
  journeyId: string,
  commandId: string,
  signal?: AbortSignal,
) {
  accountId = readUuid(accountId);
  journeyId = readUuid(journeyId);
  commandId = readUuid(commandId);
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/signals/${commandId}/community-share/stop`,
    body: {},
    timeoutMilliseconds: 12000,
    signal,
    validate: (value) => {
      const item = record(value, ['commandId', 'status']);
      if (readUuid(item.commandId) !== commandId || item.status !== 'stopped_for_future_sharing')
        throw new BrowserLiveError('unavailable');
      return { commandId, status: 'stopped_for_future_sharing' as const };
    },
  });
}

export function reportBrowserCommunityTraffic(
  accountId: string,
  journeyId: string,
  ref: string,
  reason: CommunityReportReason,
  clientRequestId: string,
  signal?: AbortSignal,
) {
  accountId = readUuid(accountId);
  journeyId = readUuid(journeyId);
  ref = readUuid(ref);
  clientRequestId = readUuid(clientRequestId);
  if (!reasons.has(reason)) throw new BrowserLiveError('invalid');
  return liveRequest({
    accountId,
    path: `/api/v1/journeys/${journeyId}/community-traffic/${ref}/reports`,
    body: { reason, clientRequestId },
    timeoutMilliseconds: 12000,
    expectedStatus: 202,
    signal,
    validate: (value) => {
      const item = record(value, ['status', 'receivedAt', 'receiptExpiresAt']);
      if (item.status !== 'received') throw new BrowserLiveError('unavailable');
      return {
        status: 'received' as const,
        receivedAt: readInstant(item.receivedAt).raw,
        receiptExpiresAt: readInstant(item.receiptExpiresAt).raw,
      };
    },
  });
}
