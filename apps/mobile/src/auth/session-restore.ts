import { readNativeAccount } from './native-auth-protocol';
import type { NativeSessionVault } from './session-vault';

export interface NativeSessionVerifier {
  verify(credential: string, signal: AbortSignal): Promise<unknown>;
}

function unavailable(): Error {
  return new Error('Native session could not be verified.');
}

/** Unmounted prerequisite: the verifier must enforce the native transport specification. */
export function createNativeSessionRestorer(
  vault: NativeSessionVault,
  verifier: NativeSessionVerifier,
  now: () => number = Date.now,
) {
  let current: AbortController | null = null;

  function invalidate(): void {
    const previous = current;
    current = null;
    previous?.abort();
  }

  function requireCurrent(attempt: AbortController): void {
    if (current !== attempt || attempt.signal.aborted) throw unavailable();
  }

  function requireUnexpired(expiresAt: number): void {
    const time = now();
    if (
      !Number.isSafeInteger(time) ||
      time < 0 ||
      time > Number.MAX_SAFE_INTEGER - 900000 ||
      expiresAt <= time ||
      expiresAt > time + 900000
    )
      throw unavailable();
  }

  async function restore(): Promise<{ accountId: string } | null> {
    invalidate();
    const attempt = new AbortController();
    current = attempt;
    try {
      const session = await vault.load();
      requireCurrent(attempt);
      if (session === null) return null;
      requireUnexpired(session.expiresAt);
      requireCurrent(attempt);
      const account = readNativeAccount(await verifier.verify(session.credential, attempt.signal));
      requireCurrent(attempt);
      requireUnexpired(session.expiresAt);
      if (account.accountId !== session.accountId) throw unavailable();
      requireCurrent(attempt);
      return account;
    } catch {
      throw unavailable();
    } finally {
      if (current === attempt) current = null;
    }
  }

  async function clearLocalSession(): Promise<void> {
    invalidate();
    try {
      await vault.clear();
    } catch {
      throw unavailable();
    }
  }

  return { restore, invalidate, clearLocalSession };
}
