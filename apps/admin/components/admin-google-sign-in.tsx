'use client';

import Script from 'next/script';
import { useEffect, useRef, useState } from 'react';
import {
  adminCsrf,
  AdminApiError,
  beginAdminGoogle,
  exchangeAdminGoogle,
} from '../lib/admin-client';

interface GoogleIdentityApi {
  initialize(options: {
    client_id: string;
    nonce: string;
    auto_select: boolean;
    callback: (response: { credential: string }) => void;
  }): void;
  renderButton(
    element: HTMLElement,
    options: { type: string; theme: string; size: string; text: string; width: number },
  ): void;
  disableAutoSelect(): void;
}
declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdentityApi } };
  }
}

export function AdminGoogleSignIn({
  clientId,
  onSignedIn,
}: {
  clientId: string;
  onSignedIn: () => void;
}) {
  const button = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState('Preparing moderator sign-in…');
  const [error, setError] = useState('');
  const callback = useRef(onSignedIn);
  useEffect(() => {
    callback.current = onSignedIn;
  }, [onSignedIn]);

  useEffect(() => {
    if (!ready) return;
    let active = true;
    let exchanging = false;
    const element = button.current;
    async function prepare() {
      try {
        const csrf = await adminCsrf();
        if (!active) return;
        const challenge = await beginAdminGoogle(csrf);
        if (!active || !element || !window.google) throw new AdminApiError(503);
        if (Date.parse(challenge.expiresAt) <= Date.now()) throw new AdminApiError(401);
        window.google.accounts.id.initialize({
          client_id: clientId,
          nonce: challenge.nonce,
          auto_select: false,
          callback: (response) => {
            if (!active || exchanging) return;
            exchanging = true;
            element.replaceChildren();
            setStatus('Finishing sign-in…');
            exchangeAdminGoogle(challenge.id, response.credential, csrf)
              .then(() => {
                if (active) callback.current();
              })
              .catch((failure) => {
                if (active) {
                  setError(
                    failure instanceof Error ? failure.message : 'Sign-in failed. Try again.',
                  );
                  setStatus('');
                }
              });
          },
        });
        element.replaceChildren();
        window.google.accounts.id.renderButton(element, {
          type: 'standard',
          theme: 'outline',
          size: 'large',
          text: 'continue_with',
          width: 280,
        });
        setStatus('Choose your authorized Google account.');
      } catch (failure) {
        if (active) {
          setError(failure instanceof Error ? failure.message : 'Sign-in is unavailable.');
          setStatus('');
        }
      }
    }
    void prepare();
    return () => {
      active = false;
      element?.replaceChildren();
    };
  }, [ready, attempt, clientId]);

  return (
    <div className="sign-in-panel">
      <h2>Moderator sign-in</h2>
      <p>Use the Google account granted access to this staging workspace.</p>
      <Script
        src="https://accounts.google.com/gsi/client"
        strategy="afterInteractive"
        onReady={() => setReady(true)}
        onError={() => {
          setError('Google sign-in could not load. Check your connection.');
          setStatus('');
        }}
      />
      <div ref={button} className="google-button" aria-label="Google sign-in button" />
      {status && (
        <p role="status" className="subtle">
          {status}
        </p>
      )}
      {error && (
        <p role="alert" className="error-text">
          {error}
        </p>
      )}
      <button
        type="button"
        className="text-button"
        onClick={() => {
          setError('');
          setAttempt((value) => value + 1);
        }}
      >
        Retry sign-in
      </button>
    </div>
  );
}
