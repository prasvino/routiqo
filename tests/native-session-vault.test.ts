import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createSessionVault,
  type NativeSession,
  type NativeSessionDriver,
  type NativeSessionWriteTicket,
} from '../apps/mobile/src/auth/session-vault';

const currentTime = 1_800_000_000_000;
const accountId = 'a0000000-0000-4000-8000-000000000001';
const credential = 'A'.repeat(43);
const genericStorageError = 'Native session storage is unavailable. Clear it and try again.';

function session(overrides: Partial<NativeSession> = {}): NativeSession {
  return {
    accountId,
    credential,
    expiresAt: currentTime + 60_000,
    ...overrides,
  };
}

function persisted(value: NativeSession = session()): string {
  return JSON.stringify({ version: 1, ...value });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (failure: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

function memoryDriver(initial: string | null = null) {
  let value = initial;
  const read = vi.fn(async () => value);
  const write = vi.fn(async (next: string) => {
    value = next;
  });
  const remove = vi.fn(async () => {
    value = null;
  });
  const driver: NativeSessionDriver = { read, write, remove };
  return {
    driver,
    read,
    write,
    remove,
    value: () => value,
    replace: (next: string | null) => {
      value = next;
    },
  };
}

async function rejectedError(operation: Promise<unknown>): Promise<Error> {
  try {
    await operation;
  } catch (failure) {
    expect(failure).toBeInstanceOf(Error);
    return failure as Error;
  }
  throw new Error('Expected operation to reject.');
}

afterEach(() => vi.restoreAllMocks());

describe('native session vault', () => {
  it('serializes an exact record and loads it after reopening the vault', async () => {
    const memory = memoryDriver();
    const vault = createSessionVault(memory.driver, () => currentTime);
    const expected = session();
    const ticket = vault.beginWrite();

    const commit = vault.commit(ticket, expected);
    const load = vault.load();
    await commit;
    await expect(load).resolves.toEqual(expected);
    expect(memory.value()).toBe(persisted(expected));
    expect(new TextEncoder().encode(memory.value()!).byteLength).toBeLessThanOrEqual(1024);

    const reopened = createSessionVault(memory.driver, () => currentTime);
    await expect(reopened.load()).resolves.toEqual(expected);
  });

  it('rejects malformed commit input without poisoning or consuming its ticket', async () => {
    const memory = memoryDriver();
    const vault = createSessionVault(memory.driver, () => currentTime);
    const ticket = vault.beginWrite();

    await expect(
      vault.commit(ticket, session({ accountId: accountId.toUpperCase() })),
    ).rejects.toThrow('invalid');
    await expect(vault.commit(ticket, session({ credential: 'short' }))).rejects.toThrow('invalid');
    await expect(vault.commit(ticket, session({ expiresAt: currentTime }))).rejects.toThrow(
      'invalid',
    );
    await expect(
      vault.commit(ticket, session({ expiresAt: currentTime + 900_001 })),
    ).rejects.toThrow('invalid');
    await expect(vault.commit(ticket, session({ expiresAt: currentTime + 1.5 }))).rejects.toThrow(
      'invalid',
    );
    await expect(
      vault.commit(ticket, { ...session(), extra: 'unexpected' } as NativeSession),
    ).rejects.toThrow('invalid');
    expect(memory.write).not.toHaveBeenCalled();

    await expect(vault.commit(ticket, session())).resolves.toBeUndefined();
    expect(memory.write).toHaveBeenCalledOnce();
  });

  it('returns null for expired stored data without deleting or authorizing it', async () => {
    const raw = persisted(session({ expiresAt: currentTime }));
    const memory = memoryDriver(raw);
    const vault = createSessionVault(memory.driver, () => currentTime);

    await expect(vault.load()).resolves.toBeNull();
    expect(memory.value()).toBe(raw);
    expect(memory.remove).not.toHaveBeenCalled();
  });

  it.each([
    ['malformed JSON', `{ "credential": "${credential}"`],
    ['unknown fields', JSON.stringify({ ...JSON.parse(persisted()), extra: true })],
    ['oversize data', 'x'.repeat(1025)],
    ['unrealistic future expiry', persisted(session({ expiresAt: currentTime + 900_001 }))],
  ])('rejects and poisons %s loaded from storage', async (_case, raw) => {
    const memory = memoryDriver(raw);
    const vault = createSessionVault(memory.driver, () => currentTime);

    const failure = await rejectedError(vault.load());
    expect(failure.message).toBe(genericStorageError);
    expect(failure).not.toHaveProperty('cause');
    memory.replace(persisted());
    await expect(vault.load()).rejects.toThrow(genericStorageError);
    expect(memory.read).toHaveBeenCalledOnce();
    await vault.clear();
    await expect(vault.load()).resolves.toBeNull();
  });

  it('binds one-use tickets to one vault and invalidates older or copied tickets', async () => {
    const memory = memoryDriver();
    const vault = createSessionVault(memory.driver, () => currentTime);
    const otherVault = createSessionVault(memory.driver, () => currentTime);
    const stale = vault.beginWrite();
    const current = vault.beginWrite();
    const copied = { ...current } as NativeSessionWriteTicket;

    await expect(vault.commit(stale, session())).rejects.toThrow('no longer current');
    await expect(vault.commit(copied, session())).rejects.toThrow('no longer current');
    await expect(otherVault.commit(current, session())).rejects.toThrow('no longer current');
    await vault.commit(current, session());
    await expect(vault.commit(current, session())).rejects.toThrow('no longer current');
    expect(memory.write).toHaveBeenCalledOnce();

    const accepted = vault.beginWrite();
    const obsoleteCommit = vault.commit(accepted, session());
    const replacement = vault.beginWrite();
    await expect(obsoleteCommit).rejects.toThrow('no longer current');
    expect(memory.write).toHaveBeenCalledOnce();
    await vault.commit(replacement, session());
    expect(memory.write).toHaveBeenCalledTimes(2);
  });

  it('waits for a delayed write, removes its stale result, then completes clear', async () => {
    const writeGate = deferred<void>();
    const events: string[] = [];
    let stored: string | null = null;
    const driver: NativeSessionDriver = {
      read: vi.fn(async () => stored),
      write: vi.fn(async (value) => {
        events.push('write started');
        await writeGate.promise;
        stored = value;
        events.push('write finished');
      }),
      remove: vi.fn(async () => {
        events.push('removed');
        stored = null;
      }),
    };
    const vault = createSessionVault(driver, () => currentTime);
    const commit = vault.commit(vault.beginWrite(), session());
    await vi.waitFor(() => expect(driver.write).toHaveBeenCalledOnce());

    const clear = vault.clear();
    expect(driver.remove).not.toHaveBeenCalled();
    writeGate.resolve();

    await expect(commit).rejects.toThrow('no longer current');
    await expect(clear).resolves.toBeUndefined();
    expect(stored).toBeNull();
    expect(events).toEqual(['write started', 'write finished', 'removed', 'removed']);
  });

  it('rechecks expiry before a queued native write starts', async () => {
    const readGate = deferred<void>();
    let observedTime = currentTime;
    const driver: NativeSessionDriver = {
      read: vi.fn(async () => {
        await readGate.promise;
        return null;
      }),
      write: vi.fn(async () => undefined),
      remove: vi.fn(async () => undefined),
    };
    const vault = createSessionVault(driver, () => observedTime);
    const load = vault.load();
    await vi.waitFor(() => expect(driver.read).toHaveBeenCalledOnce());
    const commit = vault.commit(vault.beginWrite(), session({ expiresAt: currentTime + 1 }));
    observedTime = currentTime + 2;
    readGate.resolve();

    await expect(load).rejects.toThrow('no longer current');
    await expect(commit).rejects.toThrow('invalid');
    expect(driver.write).not.toHaveBeenCalled();
  });

  it('removes a native write that expires before the driver finishes', async () => {
    const writeGate = deferred<void>();
    let observedTime = currentTime;
    let stored: string | null = null;
    const driver: NativeSessionDriver = {
      read: vi.fn(async () => stored),
      write: vi.fn(async (value) => {
        await writeGate.promise;
        stored = value;
      }),
      remove: vi.fn(async () => {
        stored = null;
      }),
    };
    const vault = createSessionVault(driver, () => observedTime);
    const commit = vault.commit(vault.beginWrite(), session({ expiresAt: currentTime + 1 }));
    await vi.waitFor(() => expect(driver.write).toHaveBeenCalledOnce());
    observedTime = currentTime + 2;
    writeGate.resolve();

    await expect(commit).rejects.toThrow('invalid');
    expect(stored).toBeNull();
    expect(driver.remove).toHaveBeenCalledOnce();
  });

  it('does not return a delayed load after clear invalidates it', async () => {
    const readGate = deferred<void>();
    let stored: string | null = persisted();
    const driver: NativeSessionDriver = {
      read: vi.fn(async () => {
        await readGate.promise;
        return stored;
      }),
      write: vi.fn(async (value) => {
        stored = value;
      }),
      remove: vi.fn(async () => {
        stored = null;
      }),
    };
    const vault = createSessionVault(driver, () => currentTime);
    const load = vault.load();
    await vi.waitFor(() => expect(driver.read).toHaveBeenCalledOnce());

    const clear = vault.clear();
    readGate.resolve();

    await expect(load).rejects.toThrow('no longer current');
    await clear;
    expect(stored).toBeNull();
    expect(driver.remove).toHaveBeenCalledOnce();
  });

  it('poisons after an ambiguous write failure and recovers only through clear', async () => {
    const memory = memoryDriver();
    memory.write.mockImplementationOnce(async (value) => {
      memory.replace(value);
      throw new Error(`native write leaked ${credential}`);
    });
    const vault = createSessionVault(memory.driver, () => currentTime);

    const failure = await rejectedError(vault.commit(vault.beginWrite(), session()));
    expect(failure.message).toBe(genericStorageError);
    expect(failure.message).not.toContain(credential);
    expect(failure).not.toHaveProperty('cause');
    await expect(vault.load()).rejects.toThrow(genericStorageError);
    await expect(vault.commit(vault.beginWrite(), session())).rejects.toThrow(genericStorageError);
    expect(memory.read).not.toHaveBeenCalled();
    expect(memory.write).toHaveBeenCalledOnce();

    await vault.clear();
    expect(memory.value()).toBeNull();
    await expect(vault.commit(vault.beginWrite(), session())).resolves.toBeUndefined();
  });

  it('poisons after a read failure and redacts the driver error until clear succeeds', async () => {
    const memory = memoryDriver(persisted());
    memory.read.mockRejectedValueOnce(new Error(`native read leaked ${credential}`));
    const vault = createSessionVault(memory.driver, () => currentTime);

    const failure = await rejectedError(vault.load());
    expect(failure.message).toBe(genericStorageError);
    expect(failure.message).not.toContain(credential);
    expect(failure).not.toHaveProperty('cause');
    await expect(vault.load()).rejects.toThrow(genericStorageError);
    expect(memory.read).toHaveBeenCalledOnce();

    await vault.clear();
    await expect(vault.load()).resolves.toBeNull();
  });

  it('poisons after a delete failure and allows clear to retry recovery', async () => {
    const memory = memoryDriver(persisted());
    memory.remove.mockRejectedValueOnce(new Error(`native delete leaked ${credential}`));
    const vault = createSessionVault(memory.driver, () => currentTime);

    const failure = await rejectedError(vault.clear());
    expect(failure.message).toBe(genericStorageError);
    expect(failure.message).not.toContain(credential);
    expect(failure).not.toHaveProperty('cause');
    await expect(vault.load()).rejects.toThrow(genericStorageError);
    await expect(vault.commit(vault.beginWrite(), session())).rejects.toThrow(genericStorageError);
    expect(memory.read).not.toHaveBeenCalled();
    expect(memory.write).not.toHaveBeenCalled();

    await expect(vault.clear()).resolves.toBeUndefined();
    expect(memory.value()).toBeNull();
    await expect(vault.load()).resolves.toBeNull();
  });
});
