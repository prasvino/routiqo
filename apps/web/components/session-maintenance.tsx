'use client';
import { useEffect } from 'react';
import { authAvailability, renewBrowserSession } from '../lib/browser-auth';

/** Visible pages renew only valid sessions; the server enforces inactivity and absolute expiry. */
export function SessionMaintenance() {
  useEffect(() => {
    let disposed = false;
    let busy = false;
    let enabled = false;
    async function renew() {
      if (disposed || busy || !enabled || document.visibilityState !== 'visible') return;
      busy = true;
      try {
        // Serialize rotation across tabs where Web Locks is available. On older browsers,
        // the server still permits one winner and the next status check reconciles cookies.
        if (navigator.locks)
          await navigator.locks.request('routiqo-session-renewal', async () => {
            if (!disposed && document.visibilityState === 'visible') await renewBrowserSession();
          });
        else await renewBrowserSession();
      } catch {
        // Do not clear cookies or local data on a network error. Profile checks server status.
      } finally {
        busy = false;
      }
    }
    void authAvailability()
      .then((config) => {
        enabled = config.enabled;
        void renew();
      })
      .catch(() => {});
    const check = () => {
      void renew();
    };
    const timer = setInterval(check, 60000);
    document.addEventListener('visibilitychange', check);
    return () => {
      disposed = true;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', check);
    };
  }, []);
  return null;
}
