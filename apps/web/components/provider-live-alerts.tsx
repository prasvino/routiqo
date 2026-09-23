'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ProviderAlertsError,
  readBrowserProviderAlerts,
  type ProviderAlert,
} from '../lib/browser-provider-alerts';

interface Props {
  accountId: string;
  journeyId: string;
  online: boolean;
}

function current(alerts: ProviderAlert[]) {
  return alerts.filter((alert) => Date.parse(alert.expiresAt) > Date.now());
}

function time(value: string) {
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}

export function ProviderLiveAlerts({ accountId, journeyId, online }: Props) {
  const [alerts, setAlerts] = useState<ProviderAlert[]>([]);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'unavailable' | 'disabled'>('loading');
  const [lastChecked, setLastChecked] = useState<number | null>(null);
  const pending = useRef<AbortController | null>(null);
  const lastStarted = useRef(0);
  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    if (!online || document.visibilityState !== 'visible' || pending.current) return;
    if (Date.now() - lastStarted.current < 60_000) return;
    const controller = new AbortController();
    pending.current = controller;
    lastStarted.current = Date.now();
    try {
      const response = await readBrowserProviderAlerts(accountId, journeyId, controller.signal);
      if (!mounted.current || controller.signal.aborted) return;
      setAlerts(current(response.alerts));
      setLastChecked(Date.now());
      setPhase('ready');
    } catch (failure) {
      if (!mounted.current || controller.signal.aborted) return;
      if (failure instanceof ProviderAlertsError && failure.status === 404) {
        setAlerts([]);
        setLastChecked(null);
      }
      setPhase(
        failure instanceof ProviderAlertsError && failure.status === 404
          ? 'disabled'
          : 'unavailable',
      );
    } finally {
      if (pending.current === controller) pending.current = null;
    }
  }, [accountId, journeyId, online]);

  useEffect(() => {
    mounted.current = true;
    if (online) void refresh();
    const onFocus = () => {
      if (document.visibilityState !== 'visible') {
        pending.current?.abort();
        pending.current = null;
        lastStarted.current = 0;
        return;
      }
      void refresh();
    };
    const interval = window.setInterval(() => {
      setAlerts((prior) => current(prior));
      void refresh();
    }, 60_000);
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    return () => {
      mounted.current = false;
      pending.current?.abort();
      pending.current = null;
      window.clearInterval(interval);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onFocus);
    };
  }, [online, refresh]);

  const visible = current(alerts);
  const stale = !online || phase === 'unavailable';
  if (phase === 'disabled') return null;
  return (
    <section className="provider-live" aria-labelledby="provider-live-heading">
      <div className="provider-live-head">
        <div>
          <h3 id="provider-live-heading">Official alerts</h3>
          <p>Chennai district area · district-wide warnings, not road conditions</p>
        </div>
        <button
          className="button secondary"
          type="button"
          disabled={
            !online || pending.current !== null || Date.now() - lastStarted.current < 60_000
          }
          onClick={() => void refresh()}
        >
          Check alerts
        </button>
      </div>
      {phase === 'loading' && online && <p role="status">Checking official alerts…</p>}
      {phase === 'unavailable' && (
        <p role="status">Official alerts could not be checked. Try again in a minute.</p>
      )}
      {!online && <p role="status">Offline. Previously checked alerts may be out of date.</p>}
      {phase === 'ready' && visible.length === 0 && (
        <p role="status">
          No current official alerts were returned for this area. This does not mean roads are safe.
        </p>
      )}
      {visible.length > 0 && (
        <ul className="provider-live-list">
          {visible.map((alert) => (
            <li key={alert.id}>
              <strong>{alert.event}</strong>
              <span>
                {alert.area} · {alert.severity}
              </span>
              <span>
                Issued by {alert.issuer} · valid until {time(alert.expiresAt)}
              </span>
              {stale && <span>Last checked copy — may be out of date</span>}
              <a href={alert.sourceUrl} target="_blank" rel="noopener noreferrer">
                View official alert
              </a>
            </li>
          ))}
        </ul>
      )}
      {lastChecked !== null && (
        <p className="provider-live-checked">
          Checked {time(new Date(lastChecked).toISOString())} · Source: NDMA SACHET
        </p>
      )}
      <p className="provider-live-disclosure">
        Traveller Quick Signals are private and are not used in these alerts.
      </p>
    </section>
  );
}
