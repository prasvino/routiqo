export type ReviewReason = 'INACCURATE' | 'UNSAFE' | 'SPAM' | 'POLICY';
export type ReviewAction = 'dismiss' | 'suppress';

export interface AdminSession {
  accountId: string;
  expiresAt: string;
}

export interface ReviewItem {
  ref: string;
  reasonCounts: { INACCURATE: number; UNSAFE: number; SPAM: number };
  evidenceStatus: 'AVAILABLE' | 'EVIDENCE_UNAVAILABLE';
  areaLabel?: string;
  trafficValue?: string;
  observationPeriod?: string;
  expiresAt?: string;
}

export interface ReviewPage {
  items: ReviewItem[];
  nextCursor: string | null;
}

export interface PendingDecision {
  accountId: string;
  ref: string;
  action: ReviewAction;
  reason: ReviewReason;
  requestId: string;
}

export class AdminApiError extends Error {
  constructor(readonly status: number) {
    super(
      status === 401
        ? 'Your moderator session ended. Sign in again.'
        : status === 403
          ? 'Your moderator access is unavailable.'
          : status === 404
            ? 'This report is no longer available. Refresh the queue.'
            : status === 409
              ? 'This decision conflicts with a newer review. Refresh the queue.'
              : status === 429
                ? 'Too many requests. Wait a moment, then retry.'
                : 'The moderator service is unavailable. Try again.',
    );
  }
}

async function limitedJson(response: Response, signal: AbortSignal): Promise<unknown> {
  const stream = response.body;
  if (!stream) throw new AdminApiError(503);
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const read = reader.read();
      let onAbort: (() => void) | undefined;
      const abort = new Promise<never>((_, reject) => {
        onAbort = () => reject(new AdminApiError(503));
        if (signal.aborted) onAbort();
        else signal.addEventListener('abort', onAbort, { once: true });
      });
      let item: ReadableStreamReadResult<Uint8Array>;
      try {
        item = await Promise.race([read, abort]);
      } finally {
        if (onAbort) signal.removeEventListener('abort', onAbort);
      }
      if (item.done) break;
      size += item.value.byteLength;
      if (size > 128 * 1024) throw new AdminApiError(503);
      chunks.push(item.value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown;
  } catch {
    throw new AdminApiError(503);
  }
}

async function api<T>(path: string, method: 'GET' | 'POST' = 'GET', body?: object, csrf?: string) {
  const headers = new Headers({ Accept: 'application/json' });
  if (method === 'POST') {
    headers.set('Content-Type', 'application/json');
    if (!csrf) throw new AdminApiError(403);
    headers.set('X-XSRF-TOKEN', csrf);
  }
  let response: Response;
  const deadline = AbortSignal.timeout(12_000);
  try {
    response = await fetch(`/api/admin/${path}`, {
      method,
      headers,
      ...(body ? { body: JSON.stringify(body) } : {}),
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal: deadline,
    });
  } catch {
    throw new AdminApiError(503);
  }
  if (!response.ok) throw new AdminApiError(response.status);
  if (response.status === 204) return undefined as T;
  try {
    return (await limitedJson(response, deadline)) as T;
  } catch {
    throw new AdminApiError(503);
  }
}

export type GrantPermission = 'traffic_review' | 'traffic_suppress';
export type GrantReason =
  'OPERATOR_TRIAL' | 'COVERAGE_CHANGE' | 'SECURITY_RESPONSE' | 'ERROR_CORRECTION';
export type GrantAction = 'issue' | 'revoke';
export interface GrantRecord {
  permission: GrantPermission;
  expiresAt: string;
}
export interface TargetGrants {
  targetId: string;
  grants: GrantRecord[];
}
export interface PendingGrantMutation {
  accountId: string;
  targetId: string;
  action: GrantAction;
  permission: GrantPermission;
  reason: GrantReason;
  durationMinutes?: number;
  requestId: string;
}
export interface GrantMutationResult {
  targetId: string;
  permission: GrantPermission;
  expiresAt: string | null;
  requestId: string;
  replayed: boolean;
}

const grantPermissions = new Set<unknown>(['traffic_review', 'traffic_suppress']);
const grantUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const validExpiry = (value: unknown) =>
  typeof value === 'string' && value.length <= 40 && Number.isFinite(Date.parse(value));

export function exactTargetId(value: string): string | null {
  const trimmed = value.trim().toLowerCase();
  return grantUuid.test(trimmed) ? trimmed : null;
}

export async function grantCapability(): Promise<boolean> {
  const value = await api<unknown>('traffic-grants/me');
  if (
    !value ||
    typeof value !== 'object' ||
    typeof (value as { canManageGrants?: unknown }).canManageGrants !== 'boolean'
  )
    throw new AdminApiError(503);
  return (value as { canManageGrants: boolean }).canManageGrants;
}

export function parseTargetGrants(value: unknown, targetId: string): TargetGrants {
  if (!value || typeof value !== 'object') throw new AdminApiError(503);
  const data = value as Record<string, unknown>;
  if (data.targetId !== targetId || !Array.isArray(data.grants) || data.grants.length > 2)
    throw new AdminApiError(503);
  const seen = new Set<GrantPermission>();
  const grants = data.grants.map((raw): GrantRecord => {
    if (!raw || typeof raw !== 'object') throw new AdminApiError(503);
    const record = raw as Record<string, unknown>;
    if (!grantPermissions.has(record.permission) || !validExpiry(record.expiresAt))
      throw new AdminApiError(503);
    const permission = record.permission as GrantPermission;
    if (seen.has(permission)) throw new AdminApiError(503);
    seen.add(permission);
    return { permission, expiresAt: record.expiresAt as string };
  });
  return { targetId, grants };
}

export async function readTargetGrants(targetId: string): Promise<TargetGrants> {
  if (!exactTargetId(targetId) || exactTargetId(targetId) !== targetId)
    throw new AdminApiError(400);
  return parseTargetGrants(await api<unknown>(`traffic-grants/${targetId}`), targetId);
}

export async function mutateGrant(pending: PendingGrantMutation): Promise<GrantMutationResult> {
  if (
    exactTargetId(pending.targetId) !== pending.targetId ||
    !grantUuid.test(pending.requestId) ||
    !grantPermissions.has(pending.permission) ||
    !['OPERATOR_TRIAL', 'COVERAGE_CHANGE', 'SECURITY_RESPONSE', 'ERROR_CORRECTION'].includes(
      pending.reason,
    ) ||
    (pending.action === 'issue' &&
      (!Number.isInteger(pending.durationMinutes) ||
        (pending.durationMinutes ?? 0) < 15 ||
        (pending.durationMinutes ?? 0) > 240))
  )
    throw new AdminApiError(400);
  const csrf = await adminCsrf();
  const body =
    pending.action === 'issue'
      ? {
          requestId: pending.requestId,
          permission: pending.permission,
          durationMinutes: pending.durationMinutes,
          reason: pending.reason,
        }
      : { requestId: pending.requestId, permission: pending.permission, reason: pending.reason };
  const value = await api<unknown>(
    `traffic-grants/${pending.targetId}/${pending.action}`,
    'POST',
    body,
    csrf,
  );
  if (!value || typeof value !== 'object') throw new AdminApiError(503);
  const result = value as Record<string, unknown>;
  if (
    result.targetId !== pending.targetId ||
    result.permission !== pending.permission ||
    result.requestId !== pending.requestId ||
    typeof result.replayed !== 'boolean' ||
    (pending.action === 'issue' ? !validExpiry(result.expiresAt) : result.expiresAt !== null)
  )
    throw new AdminApiError(503);
  return result as unknown as GrantMutationResult;
}

export async function adminCsrf() {
  const result = await api<{ token: string }>('auth/csrf');
  if (!result || typeof result.token !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(result.token))
    throw new AdminApiError(503);
  return result.token;
}

export async function adminSession() {
  return api<AdminSession>('auth/session');
}

export async function beginAdminGoogle(csrf: string) {
  return api<{ id: string; nonce: string; expiresAt: string }>(
    'auth/google/challenge',
    'POST',
    {},
    csrf,
  );
}

export async function exchangeAdminGoogle(challengeId: string, idToken: string, csrf: string) {
  return api<AdminSession>('auth/google/exchange', 'POST', { challengeId, idToken }, csrf);
}

export async function adminLogout() {
  const csrf = await adminCsrf();
  await api<void>('auth/logout', 'POST', {}, csrf);
}

export async function reviewQueue(cursor?: string) {
  const query = new URLSearchParams({ limit: '20' });
  if (cursor) query.set('cursor', cursor);
  return parseReviewPage(await api<unknown>(`community-traffic/reports?${query.toString()}`));
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const short = (value: unknown, max: number) =>
  typeof value === 'string' && value.length > 0 && value.length <= max;
const count = (value: unknown) =>
  Number.isSafeInteger(value) && (value as number) >= 0 && (value as number) <= 10_000;

/** Reject malformed or source-rich queue rows before they reach the moderator view. */
export function parseReviewPage(value: unknown): ReviewPage {
  if (!value || typeof value !== 'object') throw new AdminApiError(503);
  const page = value as Record<string, unknown>;
  if (
    !Array.isArray(page.items) ||
    page.items.length > 20 ||
    !(
      page.nextCursor === null ||
      (typeof page.nextCursor === 'string' && /^[A-Za-z0-9_-]{1,256}$/.test(page.nextCursor))
    )
  )
    throw new AdminApiError(503);
  const items = page.items.map((raw): ReviewItem => {
    if (!raw || typeof raw !== 'object') throw new AdminApiError(503);
    const item = raw as Record<string, unknown>;
    const counts = item.reasonCounts as Record<string, unknown> | null;
    if (
      !uuid.test(String(item.ref)) ||
      !counts ||
      !count(counts.INACCURATE) ||
      !count(counts.UNSAFE) ||
      !count(counts.SPAM)
    )
      throw new AdminApiError(503);
    const base = {
      ref: item.ref as string,
      reasonCounts: counts as ReviewItem['reasonCounts'],
    };
    if (item.evidenceStatus === 'EVIDENCE_UNAVAILABLE')
      return { ...base, evidenceStatus: 'EVIDENCE_UNAVAILABLE' };
    if (
      item.evidenceStatus !== 'AVAILABLE' ||
      !short(item.areaLabel, 80) ||
      !short(item.trafficValue, 40) ||
      !short(item.observationPeriod, 64) ||
      !short(item.expiresAt, 40) ||
      !Number.isFinite(Date.parse(item.expiresAt as string))
    )
      throw new AdminApiError(503);
    return {
      ...base,
      evidenceStatus: 'AVAILABLE',
      areaLabel: item.areaLabel as string,
      trafficValue: item.trafficValue as string,
      observationPeriod: item.observationPeriod as string,
      expiresAt: item.expiresAt as string,
    };
  });
  return { items, nextCursor: page.nextCursor as string | null };
}

export async function decide(pending: PendingDecision) {
  const csrf = await adminCsrf();
  return api<{ status: 'dismissed' | 'suppressed' }>(
    `community-traffic/reports/${encodeURIComponent(pending.ref)}/${pending.action}`,
    'POST',
    { requestId: pending.requestId, reason: pending.reason },
    csrf,
  );
}

const pendingKey = 'routiqo_admin_v3_pending_decision';

export function savePendingDecision(pending: PendingDecision) {
  sessionStorage.setItem(pendingKey, JSON.stringify(pending));
}

export function clearPendingDecision() {
  sessionStorage.removeItem(pendingKey);
}

export function restorePendingDecision(accountId: string): PendingDecision | null {
  const raw = sessionStorage.getItem(pendingKey);
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as PendingDecision;
    if (
      value.accountId !== accountId ||
      !/^[0-9a-f-]{36}$/.test(value.ref) ||
      !/^[0-9a-f-]{36}$/.test(value.requestId) ||
      !['dismiss', 'suppress'].includes(value.action) ||
      !['INACCURATE', 'UNSAFE', 'SPAM', 'POLICY'].includes(value.reason)
    ) {
      clearPendingDecision();
      return null;
    }
    return value;
  } catch {
    clearPendingDecision();
    return null;
  }
}
