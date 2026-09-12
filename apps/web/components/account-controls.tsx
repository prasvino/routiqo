'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { UserRound, ShieldCheck } from 'lucide-react';
import {
  authAvailability,
  browserAccount,
  logoutBrowser,
  BrowserAuthError,
  type AuthAvailability,
} from '../lib/browser-auth';
import { GoogleLogin } from './google-login';
import { DeleteAccount } from './delete-account';
import { retireBrowserJourneyPartition } from '../lib/journey-storage';
import { retireBrowserJournalPartition } from '../lib/journal-storage';

export function AccountControls() {
  const [config, setConfig] = useState<AuthAvailability | null>(null);
  const [signedIn, setSignedIn] = useState(false);
  const [accountId, setAccountId] = useState<string | null>(null);
  const [deletingAccount, setDeletingAccount] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [checking, setChecking] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [login, setLogin] = useState(false);
  const revision = useRef(0);
  const invalidate = useCallback(() => {
    revision.current++;
  }, []);
  const refresh = useCallback(async () => {
    const current = ++revision.current;
    try {
      const availability = await authAvailability();
      const account = availability.enabled ? await browserAccount() : null;
      if (current !== revision.current) return;
      setConfig(availability);
      setSignedIn(Boolean(account));
      setAccountId(account?.accountId ?? null);
      setError('');
    } catch {
      if (current === revision.current)
        setError('Account status is unavailable. Your local plans are still here.');
    } finally {
      if (current === revision.current) setChecking(false);
    }
  }, []);
  useEffect(() => {
    void refresh();
    return invalidate;
  }, [refresh, invalidate]);
  useEffect(() => {
    if (!config?.enabled) return;
    const check = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    window.addEventListener('focus', check);
    document.addEventListener('visibilitychange', check);
    const timer = setInterval(check, 60000);
    return () => {
      clearInterval(timer);
      window.removeEventListener('focus', check);
      document.removeEventListener('visibilitychange', check);
    };
  }, [config?.enabled, refresh]);
  async function signOut() {
    revision.current++;
    setBusy(true);
    try {
      await logoutBrowser();
      revision.current++;
      window.google?.accounts.id.disableAutoSelect();
      setSignedIn(false);
      setAccountId(null);
      setError('');
    } catch (error) {
      setError(
        error instanceof BrowserAuthError && error.status === 429
          ? error.message
          : 'Sign-out could not be confirmed. Check your connection and try again.',
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <section className="profile-intro account-intro" aria-label="Your account">
        <span className="profile-avatar">
          <UserRound size={30} />
        </span>
        <div className="account-copy">
          <h2>{signedIn ? 'Signed in with Google' : 'Welcome, traveller.'}</h2>
          <p>Plans and saved places stay on this device. Cloud sync isn’t available yet.</p>
          <p className="account-availability" role="status">
            {checking
              ? 'Checking account status…'
              : !config?.enabled && !error
                ? 'Google sign-in isn’t available in this preview yet.'
                : signedIn
                  ? 'Your Google account is connected.'
                  : 'You can keep exploring without an account.'}
          </p>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          {notice && <p role="status">{notice}</p>}
          {signedIn && accountId && (
            <button
              className="account-delete-link"
              disabled={busy}
              onClick={() => setDeletingAccount(accountId)}
            >
              Delete Routiqo account
            </button>
          )}
        </div>
        {config?.enabled && !checking ? (
          <button
            className="button secondary"
            disabled={busy}
            onClick={() => (signedIn ? void signOut() : setLogin(true))}
          >
            {busy ? 'Signing out…' : signedIn ? 'Sign out' : 'Sign in'}
          </button>
        ) : error ? (
          <button className="button secondary" onClick={() => void refresh()}>
            Retry
          </button>
        ) : (
          <ShieldCheck size={26} />
        )}
      </section>
      {login && config?.clientId && (
        <GoogleLogin
          clientId={config.clientId}
          onClose={() => setLogin(false)}
          onSignedIn={() => {
            setLogin(false);
            void refresh();
          }}
        />
      )}
      {deletingAccount && (
        <DeleteAccount
          accountId={deletingAccount}
          onClose={() => setDeletingAccount(null)}
          onSignIn={() => {
            setDeletingAccount(null);
            setLogin(true);
          }}
          onDeleted={() => {
            revision.current++;
            setDeletingAccount(null);
            setSignedIn(false);
            setAccountId(null);
            setError('');
            setNotice(
              'Your Routiqo account was deleted. Plans and saved places on this device are still here.',
            );
            window.google?.accounts.id.disableAutoSelect();
            void Promise.allSettled([
              retireBrowserJourneyPartition(deletingAccount),
              retireBrowserJournalPartition(deletingAccount),
            ]).then((results) => {
              if (results.some((result) => result.status === 'rejected'))
                setNotice(
                  'Your account was deleted, but some local journey or journal data could not be cleared. Clear this site’s browser data to remove it. Download any local plans you want to keep first.',
                );
            });
          }}
        />
      )}
    </>
  );
}
