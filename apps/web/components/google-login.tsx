'use client';
import Script from 'next/script';
import { useEffect, useRef, useState } from 'react';
import { Modal } from './modal';
import {
  beginGoogleLogin,
  browserCsrf,
  exchangeGoogleLogin,
  BrowserAuthError,
} from '../lib/browser-auth';

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
export function GoogleLogin({
  clientId,
  onClose,
  onSignedIn,
}: {
  clientId: string;
  onClose: () => void;
  onSignedIn: () => void;
}) {
  const button = useRef<HTMLDivElement>(null);
  const [scriptReady, setScriptReady] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState('Preparing secure sign-in…');
  const [error, setError] = useState('');
  const callback = useRef(onSignedIn);
  useEffect(() => {
    callback.current = onSignedIn;
  }, [onSignedIn]);
  useEffect(() => {
    if (scriptReady) return;
    const timer = setTimeout(() => {
      setError('Google sign-in is taking too long to load. Reload this page to try again.');
      setStatus('');
    }, 15000);
    return () => clearTimeout(timer);
  }, [scriptReady]);
  useEffect(() => {
    if (!scriptReady) return;
    let active = true;
    let exchanging = false;
    let expiry: ReturnType<typeof setTimeout> | undefined;
    const container = button.current;
    async function prepare() {
      try {
        const csrf = await browserCsrf();
        if (!active) return;
        const challenge = await beginGoogleLogin(csrf);
        if (!active || !container) return;
        if (!window.google) throw new BrowserAuthError(503);
        const remaining = Date.parse(challenge.expiresAt) - Date.now();
        if (remaining <= 0) throw new BrowserAuthError(401);
        expiry = setTimeout(
          () => {
            if (active && !exchanging) {
              active = false;
              container.replaceChildren();
              setError('This sign-in attempt expired. Start again when you’re ready.');
              setStatus('');
            }
          },
          Math.min(remaining, 300000),
        );
        window.google.accounts.id.initialize({
          client_id: clientId,
          nonce: challenge.nonce,
          auto_select: false,
          callback: (response) => {
            if (!active || exchanging) return;
            exchanging = true;
            container.replaceChildren();
            setStatus('Finishing sign-in…');
            exchangeGoogleLogin(challenge.id, response.credential, csrf)
              .then(() => {
                if (active) callback.current();
              })
              .catch((error) => {
                if (active) {
                  setError(
                    error instanceof BrowserAuthError
                      ? error.message
                      : 'Sign-in could not be completed. Try again.',
                  );
                  setStatus('');
                }
              });
          },
        });
        container.replaceChildren();
        window.google.accounts.id.renderButton(container, {
          type: 'standard',
          theme: 'outline',
          size: 'large',
          text: 'continue_with',
          width: 260,
        });
        setStatus('Choose your Google account to continue.');
      } catch (error) {
        if (active) {
          setError(
            error instanceof BrowserAuthError
              ? error.message
              : 'Sign-in could not be prepared. Try again.',
          );
          setStatus('');
        }
      }
    }
    void prepare();
    return () => {
      active = false;
      clearTimeout(expiry);
      container?.replaceChildren();
    };
  }, [scriptReady, attempt, clientId]);
  return (
    <Modal title="Sign in to Routiqo" onClose={onClose}>
      <p className="modal-intro">
        Use your Google account. Your plans and saved places will stay on this device.
      </p>
      <Script
        src="https://accounts.google.com/gsi/client"
        strategy="afterInteractive"
        onReady={() => {
          setError('');
          setScriptReady(true);
        }}
        onError={() => {
          setError('Google sign-in could not load. Check your connection and reload this page.');
          setStatus('');
        }}
      />
      <div className="google-sign-in" ref={button} />
      <p className="auth-status" role="status">
        {status}
      </p>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="detail-actions">
        <button className="button secondary" onClick={onClose}>
          Keep browsing
        </button>
        {error && scriptReady && (
          <button
            className="button"
            onClick={() => {
              setError('');
              setStatus('Preparing secure sign-in…');
              setAttempt((value) => value + 1);
            }}
          >
            Start again
          </button>
        )}
        {error && !scriptReady && (
          <button className="button" onClick={() => window.location.reload()}>
            Reload page
          </button>
        )}
      </div>
    </Modal>
  );
}
