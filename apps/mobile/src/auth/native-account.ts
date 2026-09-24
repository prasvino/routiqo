import { readNativeAuthSession, readNativeChallenge } from './native-auth-protocol';
import { createNativeSessionRestorer } from './session-restore';
import type { NativeSessionVault } from './session-vault';
import { NativeHttpStatus, type createNativeTransport } from './safe-transport';
import { nativeAbortError } from './abort-error';

type Transport = ReturnType<typeof createNativeTransport>;
export interface GoogleIdentityPort {
  idToken(nonce: string): Promise<string | null>;
}
export class NativeSessionRequired extends Error {
  constructor() {
    super('Sign in to continue.');
  }
}

export function createNativeAccount(
  vault: NativeSessionVault,
  transport: Transport,
  google: GoogleIdentityPort,
  now: () => number = Date.now,
) {
  let accountId: string | null = null;
  let generation = 0;
  const restorer = createNativeSessionRestorer(
    vault,
    {
      verify: (credential) =>
        transport.request('/api/v1/native/auth/session', 'GET', { credential }),
    },
    now,
  );
  const changed = () => {
    generation++;
    restorer.invalidate();
    accountId = null;
    return generation;
  };
  const current = (attempt: number) => {
    if (attempt !== generation) throw new Error('Native account flow changed.');
  };
  async function restore(): Promise<string | null> {
    const attempt = changed();
    const account = await restorer.restore();
    current(attempt);
    accountId = account?.accountId ?? null;
    return accountId;
  }
  async function signIn(): Promise<string | null> {
    const attempt = changed();
    await vault.clear();
    current(attempt);
    const ticket = vault.beginWrite();
    const challenge = readNativeChallenge(
      await transport.request('/api/v1/native/auth/google/challenge', 'POST'),
      now(),
    );
    current(attempt);
    const idToken = await google.idToken(challenge.nonce);
    current(attempt);
    if (idToken === null) return null;
    if (typeof idToken !== 'string' || idToken.length < 1 || idToken.length > 16384)
      throw new Error('Google sign-in could not be verified.');
    const session = readNativeAuthSession(
      await transport.request('/api/v1/native/auth/google/exchange', 'POST', {
        body: { challengeId: challenge.id, binding: challenge.binding, idToken },
      }),
      now(),
    );
    current(attempt);
    await vault.commit(ticket, session);
    current(attempt);
    accountId = session.accountId;
    return accountId;
  }
  async function reauthenticate(): Promise<string> {
    const actor = accountId;
    if (!actor) throw new Error('Sign in to continue.');
    const attempt = changed();
    const ticket = vault.beginWrite();
    const challenge = readNativeChallenge(
      await transport.request('/api/v1/native/auth/google/challenge', 'POST'),
      now(),
    );
    current(attempt);
    const idToken = await google.idToken(challenge.nonce);
    current(attempt);
    if (!idToken || idToken.length > 16384) throw new Error('Google verification did not finish.');
    const session = readNativeAuthSession(
      await transport.request('/api/v1/native/auth/google/exchange', 'POST', {
        body: { challengeId: challenge.id, binding: challenge.binding, idToken },
      }),
      now(),
    );
    current(attempt);
    if (session.accountId !== actor) {
      await transport
        .request('/api/v1/native/auth/logout', 'POST', {
          credential: session.credential,
        })
        .catch(() => undefined);
      throw new Error('Google account does not match.');
    }
    await vault.commit(ticket, session);
    current(attempt);
    accountId = actor;
    return actor;
  }
  async function renew(force = false): Promise<string> {
    const actor = accountId;
    if (!actor) throw new Error('Sign in to continue.');
    const previous = await vault.load();
    if (accountId !== actor) throw new Error('Native account flow changed.');
    if (!previous || previous.accountId !== actor) throw new Error('Sign in to continue.');
    if (!force && previous.expiresAt > now() + 5 * 60 * 1000) return actor;
    const attempt = changed();
    const ticket = vault.beginWrite();
    const session = readNativeAuthSession(
      await transport.request('/api/v1/native/auth/session/renew', 'POST', {
        credential: previous.credential,
      }),
      now(),
    );
    current(attempt);
    if (session.accountId !== actor) throw new Error('Native account changed.');
    await vault.commit(ticket, session);
    current(attempt);
    accountId = actor;
    return actor;
  }
  async function logout(): Promise<void> {
    const actor = accountId;
    const attempt = changed();
    let previous;
    try {
      previous = await vault.load();
    } catch (error) {
      current(attempt);
      accountId = actor;
      throw error;
    }
    current(attempt);
    if (previous) {
      try {
        await transport.request('/api/v1/native/auth/logout', 'POST', {
          credential: previous.credential,
        });
      } catch (error) {
        current(attempt);
        accountId = actor;
        throw error;
      }
    }
    current(attempt);
    await vault.clear();
  }
  async function deleteAccount(): Promise<{ accountId: string; credentialCleared: boolean }> {
    const actor = accountId;
    if (!actor) throw new Error('Sign in to continue.');
    const previous = await vault.load();
    if (!previous || previous.accountId !== actor || accountId !== actor)
      throw new Error('Sign in to continue.');
    await transport.request('/api/v1/native/auth/account/delete', 'POST', {
      credential: previous.credential,
      body: { confirmation: 'DELETE', accountId: actor },
    });
    changed();
    try {
      await vault.clear();
      return { accountId: actor, credentialCleared: true };
    } catch {
      return { accountId: actor, credentialCleared: false };
    }
  }
  async function clearLocalSession(): Promise<void> {
    changed();
    await vault.clear();
  }
  async function credentialFor(expectedAccount: string): Promise<string> {
    const attempt = generation;
    if (accountId !== expectedAccount) throw new NativeSessionRequired();
    const session = await vault.load();
    current(attempt);
    if (!session || session.accountId !== expectedAccount || session.expiresAt <= now())
      throw new NativeSessionRequired();
    return session.credential;
  }
  async function verifiedRequest(
    path: string,
    method: 'GET' | 'POST',
    options: { accountId: string; body?: unknown; signal?: AbortSignal },
  ): Promise<unknown> {
    const attempt = generation;
    const credential = await credentialFor(options.accountId);
    current(attempt);
    if (options.signal?.aborted) throw nativeAbortError('Native request cancelled.');
    let result: unknown;
    try {
      result = await transport.request(path, method, {
        credential,
        accountId: options.accountId,
        body: options.body,
      });
    } catch (error) {
      current(attempt);
      throw error;
    }
    current(attempt);
    return result;
  }
  return {
    restore,
    signIn,
    reauthenticate,
    renew,
    logout,
    deleteAccount,
    clearLocalSession,
    credentialFor,
    verifiedRequest,
    activeAccount: () => accountId,
    revision: () => generation,
    invalidate: changed,
    configured: transport.configured,
    isAuthenticationError: (error: unknown) =>
      error instanceof NativeHttpStatus && error.status === 401,
  };
}
