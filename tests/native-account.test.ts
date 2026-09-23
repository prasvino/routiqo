import { expect, it, vi } from 'vitest';
import { createNativeAccount } from '../apps/mobile/src/auth/native-account';
import { createNativeTransport } from '../apps/mobile/src/auth/safe-transport';
import { createSessionVault } from '../apps/mobile/src/auth/session-vault';

const account = '00000000-0000-4000-8000-000000000001';
const challengeId = '00000000-0000-4000-8000-000000000002';
const nonce = 'a'.repeat(64);
const binding = 'B'.repeat(43);
const credential = 'C'.repeat(43);
const now = Date.parse('2026-09-23T10:00:00Z');
const challenge = {
  id: challengeId,
  nonce,
  binding,
  expiresAt: new Date(now + 300000).toISOString(),
};
const session = { accountId: account, credential, expiresAt: new Date(now + 900000).toISOString() };

function fixture(
  google: (nonce: string) => Promise<string | null> = async () => 'google-id-token',
) {
  let raw: string | null = null;
  const vault = createSessionVault(
    {
      read: async () => raw,
      write: async (value) => {
        raw = value;
      },
      remove: async () => {
        raw = null;
      },
    },
    () => now,
  );
  const sent: {
    path: string;
    credential: string | null;
    account: string | null;
    payload: string | null;
  }[] = [];
  const driver = {
    request: vi.fn(
      async (
        _origin: string,
        path: string,
        _method: string,
        bearer: string | null,
        actor: string | null,
        payload: string | null,
      ) => {
        sent.push({ path, credential: bearer, account: actor, payload });
        if (path.endsWith('/challenge')) return { status: 200, body: JSON.stringify(challenge) };
        if (path.endsWith('/exchange')) return { status: 200, body: JSON.stringify(session) };
        if (path.endsWith('/session/renew')) return { status: 200, body: JSON.stringify(session) };
        if (path.endsWith('/session'))
          return { status: 200, body: JSON.stringify({ accountId: account }) };
        if (path.endsWith('/logout')) return { status: 204, body: '' };
        if (path.endsWith('/account/delete')) return { status: 204, body: '' };
        throw new Error('Unexpected test route');
      },
    ),
  };
  const identity = createNativeAccount(
    vault,
    createNativeTransport(driver, 'https://staging.routiqo.example'),
    { idToken: google },
    () => now,
  );
  return { identity, vault, driver, sent, stored: () => raw };
}

it('binds fresh Google nonce, stores only Routiqo session, restores and revokes', async () => {
  const google = vi.fn(async () => 'google-id-token');
  const f = fixture(google);
  expect(await f.identity.restore()).toBeNull();
  expect(await f.identity.signIn()).toBe(account);
  expect(google).toHaveBeenCalledWith(nonce);
  expect(f.sent.find((entry) => entry.path.endsWith('/exchange'))?.payload).toBe(
    JSON.stringify({ challengeId, binding, idToken: 'google-id-token' }),
  );
  expect(f.stored()).toContain(credential);
  expect(f.stored()).not.toContain(binding);
  expect(await f.identity.restore()).toBe(account);
  expect(f.sent.find((entry) => entry.path.endsWith('/session'))?.credential).toBe(credential);
  await f.identity.logout();
  expect(f.stored()).toBeNull();
  expect(f.identity.activeAccount()).toBeNull();
});

it('a cancelled Google flow leaves no verified account or old vault credential', async () => {
  const f = fixture(async () => null);
  await f.vault.commit(f.vault.beginWrite(), {
    accountId: account,
    credential,
    expiresAt: now + 600000,
  });
  expect(await f.identity.signIn()).toBeNull();
  expect(f.stored()).toBeNull();
  expect(f.identity.activeAccount()).toBeNull();
  expect(f.sent.some((entry) => entry.path.endsWith('/exchange'))).toBe(false);
});

it('does not publish a late account after a superseding lifecycle change', async () => {
  let resolve!: (value: string | null) => void;
  const google = new Promise<string | null>((done) => {
    resolve = done;
  });
  const f = fixture(async () => google);
  const pending = f.identity.signIn().catch((error: unknown) => error);
  await vi.waitFor(() =>
    expect(f.sent.some((entry) => entry.path.endsWith('/challenge'))).toBe(true),
  );
  f.identity.invalidate();
  resolve('google-id-token');
  await expect(pending).resolves.toBeInstanceOf(Error);
  expect(f.identity.activeAccount()).toBeNull();
  expect(f.stored()).toBeNull();
});

it('deletes only after explicit server confirmation and then clears the credential', async () => {
  const f = fixture();
  await f.identity.signIn();
  const result = await f.identity.deleteAccount();
  expect(result).toEqual({ accountId: account, credentialCleared: true });
  expect(f.sent.find((entry) => entry.path.endsWith('/account/delete'))?.payload).toBe(
    JSON.stringify({ confirmation: 'DELETE', accountId: account }),
  );
  expect(f.stored()).toBeNull();
  expect(f.identity.activeAccount()).toBeNull();
});

it('keeps a failed logout retryable with the same credential', async () => {
  const f = fixture();
  await f.identity.signIn();
  const original = f.driver.request.getMockImplementation();
  expect(original).toBeDefined();
  f.driver.request.mockImplementationOnce(async () => {
    throw new Error('connection lost');
  });
  await expect(f.identity.logout()).rejects.toThrow();
  expect(f.identity.activeAccount()).toBe(account);
  expect(f.stored()).toContain(credential);
  f.driver.request.mockImplementation(original!);
  await f.identity.logout();
  expect(f.identity.activeAccount()).toBeNull();
  expect(f.stored()).toBeNull();
});

it('forces server renewal after a 401 even when local expiry is far away', async () => {
  const f = fixture();
  await f.identity.signIn();
  expect(await f.identity.renew()).toBe(account);
  expect(f.sent.filter((entry) => entry.path.endsWith('/session/renew'))).toHaveLength(0);
  expect(await f.identity.renew(true)).toBe(account);
  expect(f.sent.filter((entry) => entry.path.endsWith('/session/renew'))).toHaveLength(1);
});
