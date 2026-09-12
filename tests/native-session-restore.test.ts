import { expect, it, vi } from 'vitest';
import { createNativeSessionRestorer } from '../apps/mobile/src/auth/session-restore';
import {
  createSessionVault,
  type NativeSessionDriver,
} from '../apps/mobile/src/auth/session-vault';

const accountId = '00000000-0000-4000-8000-000000000001';
const otherId = '00000000-0000-4000-8000-000000000002';
const credential = 'A'.repeat(43);
const now = 1000000;
const message = 'Native session could not be verified.';
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function fixture() {
  let raw: string | null = null;
  let time = now;
  const driver: NativeSessionDriver = {
    read: vi.fn(async () => raw),
    write: vi.fn(async (value) => {
      raw = value;
    }),
    remove: vi.fn(async () => {
      raw = null;
    }),
  };
  const vault = createSessionVault(driver, () => time);
  await vault.commit(vault.beginWrite(), { accountId, credential, expiresAt: now + 60000 });
  return {
    vault,
    driver,
    clock: () => time,
    setTime: (value: number) => {
      time = value;
    },
  };
}

it('returns only the server-matched account and never verifies an empty or expired vault', async () => {
  const f = await fixture();
  const verify = vi.fn(async () => ({ accountId }));
  const restorer = createNativeSessionRestorer(f.vault, { verify }, f.clock);
  expect(await restorer.restore()).toEqual({ accountId });
  expect(verify).toHaveBeenCalledWith(credential, expect.any(AbortSignal));
  f.setTime(now + 60000);
  expect(await restorer.restore()).toBeNull();
  await restorer.clearLocalSession();
  expect(await restorer.restore()).toBeNull();
  expect(verify).toHaveBeenCalledTimes(1);
});

it('rejects mismatched, malformed and extra-field responses with fixed errors', async () => {
  const f = await fixture();
  for (const response of [
    null,
    { accountId: otherId },
    { accountId, credential },
    { accountId: 'private' },
  ]) {
    const restorer = createNativeSessionRestorer(
      f.vault,
      { verify: async () => response },
      f.clock,
    );
    const error = await restorer.restore().catch((value: unknown) => value);
    expect(error).toEqual(new Error(message));
    expect((error as Error).cause).toBeUndefined();
  }
});

it('supersedes a pending verification even when its adapter ignores abort', async () => {
  const f = await fixture();
  const first = deferred<unknown>();
  const verify = vi
    .fn()
    .mockImplementationOnce(() => first.promise)
    .mockResolvedValue({ accountId });
  const restorer = createNativeSessionRestorer(f.vault, { verify }, f.clock);
  const old = restorer.restore().catch((error: unknown) => error);
  await vi.waitFor(() => expect(verify).toHaveBeenCalledTimes(1));
  const signal = verify.mock.calls[0]![1] as AbortSignal;
  expect(await restorer.restore()).toEqual({ accountId });
  expect(signal.aborted).toBe(true);
  first.resolve({ accountId });
  expect(await old).toEqual(new Error(message));
});

it('invalidates delayed loads before any credential reaches the verifier', async () => {
  const f = await fixture();
  const read = deferred<string | null>();
  vi.mocked(f.driver.read).mockReturnValueOnce(read.promise);
  const verify = vi.fn(async () => ({ accountId }));
  const restorer = createNativeSessionRestorer(f.vault, { verify }, f.clock);
  const old = restorer.restore().catch((error: unknown) => error);
  await vi.waitFor(() => expect(f.driver.read).toHaveBeenCalled());
  restorer.invalidate();
  read.resolve(JSON.stringify({ version: 1, accountId, credential, expiresAt: now + 60000 }));
  expect(await old).toEqual(new Error(message));
  expect(verify).not.toHaveBeenCalled();
});

it('clear invalidates verification even if removal fails and supports explicit recovery', async () => {
  const f = await fixture();
  const response = deferred<unknown>();
  const verify = vi.fn(() => response.promise);
  const restorer = createNativeSessionRestorer(f.vault, { verify }, f.clock);
  const old = restorer.restore().catch((error: unknown) => error);
  await vi.waitFor(() => expect(verify).toHaveBeenCalled());
  vi.mocked(f.driver.remove).mockRejectedValueOnce(new Error(credential));
  await expect(restorer.clearLocalSession()).rejects.toThrow(message);
  response.resolve({ accountId });
  expect(await old).toEqual(new Error(message));
  await expect(restorer.restore()).rejects.toThrow(message);
  await restorer.clearLocalSession();
  expect(await restorer.restore()).toBeNull();
});

it('rechecks expiry and clock validity after verification and redacts provider failures', async () => {
  for (const time of [now + 60000, Number.NaN, -1, Number.MAX_SAFE_INTEGER, now - 900000]) {
    const f = await fixture();
    const restorer = createNativeSessionRestorer(
      f.vault,
      {
        verify: async () => {
          f.setTime(time);
          return { accountId };
        },
      },
      f.clock,
    );
    await expect(restorer.restore()).rejects.toThrow(message);
  }
  const f = await fixture();
  const restorer = createNativeSessionRestorer(
    f.vault,
    {
      verify: async () => {
        throw new Error(credential);
      },
    },
    f.clock,
  );
  expect(await restorer.restore().catch((error: unknown) => error)).toEqual(new Error(message));
});

it('invalidating before an account switch prevents the old account from returning', async () => {
  const f = await fixture();
  const response = deferred<unknown>();
  const verify = vi
    .fn()
    .mockImplementationOnce(() => response.promise)
    .mockResolvedValue({ accountId: otherId });
  const restorer = createNativeSessionRestorer(f.vault, { verify }, f.clock);
  const old = restorer.restore().catch((error: unknown) => error);
  await vi.waitFor(() => expect(verify).toHaveBeenCalled());
  restorer.invalidate();
  await f.vault.commit(f.vault.beginWrite(), {
    accountId: otherId,
    credential: 'B'.repeat(43),
    expiresAt: now + 60000,
  });
  expect(await restorer.restore()).toEqual({ accountId: otherId });
  response.reject(new Error(credential));
  expect(await old).toEqual(new Error(message));
});

it('never sends credentials when the clock or secure read fails', async () => {
  const f = await fixture();
  const verify = vi.fn(async () => ({ accountId }));
  const invalidClock = createNativeSessionRestorer(f.vault, { verify }, () => {
    throw new Error(credential);
  });
  expect(await invalidClock.restore().catch((error: unknown) => error)).toEqual(new Error(message));
  vi.mocked(f.driver.read).mockRejectedValueOnce(new Error(credential));
  const failedRead = createNativeSessionRestorer(f.vault, { verify }, f.clock);
  expect(await failedRead.restore().catch((error: unknown) => error)).toEqual(new Error(message));
  expect(verify).not.toHaveBeenCalled();
});
