import type { NativeSession } from './session-vault';

export interface NativeChallenge {
  id: string;
  nonce: string;
  binding: string;
  expiresAt: number;
}

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const secret = /^[A-Za-z0-9_-]{43}$/;
function invalid(): Error {
  return new Error('Native authentication response is invalid.');
}
function fields(value: unknown, keys: string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid();
  const actual = Object.keys(value);
  if (actual.length !== keys.length || actual.some((key) => !keys.includes(key))) throw invalid();
  return value as Record<string, unknown>;
}
function text(value: unknown, pattern: RegExp): string {
  if (typeof value !== 'string' || !pattern.test(value)) throw invalid();
  return value;
}
function expiry(value: unknown, now: number, lifetime: number): number {
  if (!Number.isSafeInteger(now) || now < 0 || now > Number.MAX_SAFE_INTEGER - lifetime)
    throw invalid();
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)
  )
    throw invalid();
  const parsed = Date.parse(value);
  if (
    !Number.isFinite(parsed) ||
    parsed <= now ||
    parsed > now + lifetime ||
    new Date(parsed).toISOString().slice(0, 19) !== value.slice(0, 19)
  )
    throw invalid();
  return parsed;
}

/** Validates parsed, size-bounded transport data. Does not authenticate an account or persist secrets. */
export function readNativeChallenge(value: unknown, now: number): NativeChallenge {
  try {
    const item = fields(value, ['id', 'nonce', 'binding', 'expiresAt']);
    return {
      id: text(item.id, uuid),
      nonce: text(item.nonce, secret),
      binding: text(item.binding, secret),
      expiresAt: expiry(item.expiresAt, now, 300000),
    };
  } catch {
    throw invalid();
  }
}
export function readNativeAuthSession(value: unknown, now: number): NativeSession {
  try {
    const item = fields(value, ['accountId', 'credential', 'expiresAt']);
    return {
      accountId: text(item.accountId, uuid),
      credential: text(item.credential, secret),
      expiresAt: expiry(item.expiresAt, now, 900000),
    };
  } catch {
    throw invalid();
  }
}
export function readNativeAccount(value: unknown): { accountId: string } {
  try {
    const item = fields(value, ['accountId']);
    return { accountId: text(item.accountId, uuid) };
  } catch {
    throw invalid();
  }
}
