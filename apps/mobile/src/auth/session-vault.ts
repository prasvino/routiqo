export interface NativeSession {
  accountId: string;
  credential: string;
  expiresAt: number;
}

export interface NativeSessionDriver {
  read(): Promise<string | null>;
  write(value: string): Promise<void>;
  remove(): Promise<void>;
}

const ticketBrand: unique symbol = Symbol('native-session-write-ticket');
export interface NativeSessionWriteTicket {
  readonly [ticketBrand]: true;
}

export interface NativeSessionVault {
  beginWrite(): NativeSessionWriteTicket;
  load(): Promise<NativeSession | null>;
  commit(ticket: NativeSessionWriteTicket, session: NativeSession): Promise<void>;
  clear(): Promise<void>;
}

const maximumRecordBytes = 1024;
const maximumLifetimeMilliseconds = 15 * 60 * 1000;
const accountIdPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const credentialPattern = /^[A-Za-z0-9_-]{43}$/;
const sessionKeys = ['accountId', 'credential', 'expiresAt'] as const;
const persistedKeys = ['version', ...sessionKeys] as const;

function invalidSession(): Error {
  return new Error('Native session is invalid.');
}

function staleOperation(): Error {
  return new Error('Native session operation is no longer current.');
}

function unavailableStorage(): Error {
  return new Error('Native session storage is unavailable. Clear it and try again.');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length && actual.every((key) => keys.includes(key));
}

function readClock(now: () => number): number {
  let value: number;
  try {
    value = now();
  } catch {
    throw invalidSession();
  }
  if (
    !Number.isSafeInteger(value) ||
    value < 0 ||
    value > Number.MAX_SAFE_INTEGER - maximumLifetimeMilliseconds
  )
    throw invalidSession();
  return value;
}

function readSession(value: unknown, persisted: boolean): NativeSession {
  try {
    if (!isRecord(value) || !hasExactKeys(value, persisted ? persistedKeys : sessionKeys))
      throw invalidSession();
    const version = persisted ? value.version : 1;
    const accountId = value.accountId;
    const credential = value.credential;
    const expiresAt = value.expiresAt;
    if (
      version !== 1 ||
      typeof accountId !== 'string' ||
      !accountIdPattern.test(accountId) ||
      typeof credential !== 'string' ||
      !credentialPattern.test(credential) ||
      typeof expiresAt !== 'number' ||
      !Number.isSafeInteger(expiresAt) ||
      expiresAt < 0
    )
      throw invalidSession();
    return { accountId, credential, expiresAt };
  } catch {
    throw invalidSession();
  }
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function requireCurrentExpiry(expiresAt: number, currentTime: number): void {
  if (expiresAt <= currentTime || expiresAt > currentTime + maximumLifetimeMilliseconds)
    throw invalidSession();
}

function serializeSession(
  value: unknown,
  currentTime: number,
): { expiresAt: number; value: string } {
  const session = readSession(value, false);
  requireCurrentExpiry(session.expiresAt, currentTime);
  const serialized = JSON.stringify({ version: 1, ...session });
  if (byteLength(serialized) > maximumRecordBytes) throw invalidSession();
  return { expiresAt: session.expiresAt, value: serialized };
}

function parseStoredSession(raw: unknown, currentTime: number): NativeSession | null {
  if (
    typeof raw !== 'string' ||
    raw.length > maximumRecordBytes ||
    byteLength(raw) > maximumRecordBytes
  )
    throw invalidSession();
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw invalidSession();
  }
  const session = readSession(value, true);
  if (session.expiresAt > currentTime + maximumLifetimeMilliseconds) throw invalidSession();
  return session.expiresAt <= currentTime ? null : session;
}

export function createSessionVault(
  driver: NativeSessionDriver,
  now: () => number = Date.now,
): NativeSessionVault {
  let generation = 0;
  let poisoned = false;
  let operations: Promise<void> = Promise.resolve();
  const tickets = new WeakMap<object, number>();

  function advanceGeneration(): number {
    generation += 1;
    return generation;
  }

  function enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = operations.then(operation);
    operations = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  function beginWrite(): NativeSessionWriteTicket {
    const ticket = Object.freeze({}) as NativeSessionWriteTicket;
    tickets.set(ticket, advanceGeneration());
    return ticket;
  }

  function load(): Promise<NativeSession | null> {
    if (poisoned) return Promise.reject(unavailableStorage());
    const loadGeneration = generation;
    return enqueue(async () => {
      if (poisoned) throw unavailableStorage();
      if (loadGeneration !== generation) throw staleOperation();
      let raw: string | null;
      try {
        raw = await driver.read();
      } catch {
        poisoned = true;
        throw unavailableStorage();
      }
      if (loadGeneration !== generation) throw staleOperation();
      if (raw === null) return null;
      let session: NativeSession | null;
      try {
        session = parseStoredSession(raw, readClock(now));
      } catch {
        poisoned = true;
        throw unavailableStorage();
      }
      if (loadGeneration !== generation) throw staleOperation();
      return session;
    });
  }

  function commit(ticket: NativeSessionWriteTicket, session: NativeSession): Promise<void> {
    if (poisoned) return Promise.reject(unavailableStorage());
    let serialized: { expiresAt: number; value: string };
    try {
      serialized = serializeSession(session, readClock(now));
    } catch {
      return Promise.reject(invalidSession());
    }
    if (typeof ticket !== 'object' || ticket === null) return Promise.reject(staleOperation());
    const ticketGeneration = tickets.get(ticket);
    if (ticketGeneration === undefined || ticketGeneration !== generation)
      return Promise.reject(staleOperation());
    tickets.delete(ticket);
    const commitGeneration = advanceGeneration();
    return enqueue(async () => {
      if (poisoned) throw unavailableStorage();
      if (commitGeneration !== generation) throw staleOperation();
      requireCurrentExpiry(serialized.expiresAt, readClock(now));
      try {
        await driver.write(serialized.value);
      } catch {
        poisoned = true;
        throw unavailableStorage();
      }
      let stillCurrent = commitGeneration === generation;
      try {
        requireCurrentExpiry(serialized.expiresAt, readClock(now));
      } catch {
        stillCurrent = false;
      }
      if (stillCurrent) return;
      try {
        await driver.remove();
      } catch {
        poisoned = true;
        throw unavailableStorage();
      }
      if (commitGeneration !== generation) throw staleOperation();
      throw invalidSession();
    });
  }

  function clear(): Promise<void> {
    advanceGeneration();
    return enqueue(async () => {
      try {
        await driver.remove();
        poisoned = false;
      } catch {
        poisoned = true;
        throw unavailableStorage();
      }
    });
  }

  return { beginWrite: beginWrite, load, commit, clear };
}
