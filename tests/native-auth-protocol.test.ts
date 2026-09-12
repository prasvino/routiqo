import { expect, it } from 'vitest';
import {
  readNativeAccount,
  readNativeAuthSession,
  readNativeChallenge,
} from '../apps/mobile/src/auth/native-auth-protocol';
const now = Date.parse('2026-09-12T00:00:00Z');
const accountId = '00000000-0000-4000-8000-000000000001';
const credential = 'A'.repeat(43);
const session = { accountId, credential, expiresAt: '2026-09-12T00:15:00.000000Z' };
const challenge = {
  id: accountId,
  nonce: credential,
  binding: 'B'.repeat(43),
  expiresAt: '2026-09-12T00:05:00Z',
};
it('normalizes bounded credential responses for the secure session vault', () => {
  expect(readNativeAuthSession(session, now)).toEqual({
    accountId,
    credential,
    expiresAt: now + 900000,
  });
  expect(readNativeChallenge(challenge, now)).toEqual({ ...challenge, expiresAt: now + 300000 });
  expect(readNativeAccount({ accountId })).toEqual({ accountId });
});
it('rejects wrong shape, extra data and malformed identities or secrets without exposing input', () => {
  for (const value of [
    null,
    [],
    { ...session, extra: 'private' },
    { ...session, credential: 'private-secret' },
    { ...session, accountId: 'wrong' },
  ]) {
    expect(() => readNativeAuthSession(value, now)).toThrow(
      'Native authentication response is invalid.',
    );
  }
  expect(() => readNativeChallenge({ ...challenge, binding: 'private-binding' }, now)).toThrow(
    'Native authentication response is invalid.',
  );
  expect(() => readNativeAccount({ accountId, credential })).toThrow(
    'Native authentication response is invalid.',
  );
});
it('rejects expired, excessive, noncanonical and invalid-clock expirations', () => {
  for (const expiresAt of [
    '2026-09-12T00:00:00Z',
    '2026-09-12T00:15:01Z',
    '2026-09-12',
    '2026-09-12T00:01:00+00:00',
    123,
  ]) {
    expect(() => readNativeAuthSession({ ...session, expiresAt }, now)).toThrow();
  }
  expect(() => readNativeChallenge({ ...challenge, expiresAt: session.expiresAt }, now)).toThrow();
  for (const clock of [NaN, Infinity, -1, 1.5, Number.MAX_SAFE_INTEGER])
    expect(() => readNativeAuthSession(session, clock)).toThrow();
  expect(() =>
    readNativeAuthSession(
      { ...session, expiresAt: '2026-02-30T00:01:00Z' },
      Date.parse('2026-03-02T00:00:00Z'),
    ),
  ).toThrow();
});
