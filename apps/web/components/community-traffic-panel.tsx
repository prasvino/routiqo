'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { browserAccount } from '../lib/browser-auth';
import { BrowserLiveError } from '../lib/browser-live-private';
import {
  readBrowserCommunityTraffic,
  reportBrowserCommunityTraffic,
  type CommunityReportReason,
  type CommunityTrafficMoment,
  type CommunityTrafficTimedMoment,
} from '../lib/browser-community-traffic';

interface Props {
  accountId: string;
  journeyId: string;
  online: boolean;
  available?: boolean;
}

const labels: Record<CommunityTrafficMoment['trafficValue'], string> = {
  traffic_moving: 'Traffic reported moving',
  traffic_slow: 'Slow traffic reported',
  traffic_very_slow: 'Very slow traffic reported',
  traffic_stopped: 'Stopped traffic reported',
};

export function CommunityTrafficPanel({ accountId, journeyId, online, available = true }: Props) {
  const [rows, setRows] = useState<CommunityTrafficTimedMoment[]>([]);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'unavailable' | 'expired'>('loading');
  const [now, setNow] = useState(() => performance.now());
  const [reason, setReason] = useState<Record<string, CommunityReportReason>>({});
  const [reported, setReported] = useState<Record<string, string>>({});
  const pending = useRef<AbortController | null>(null);
  const reportPending = useRef<AbortController | null>(null);
  const reportAttempts = useRef(
    new Map<string, { reason: CommunityReportReason; requestId: string; confirmed: boolean }>(),
  );
  const lastStarted = useRef(0);

  const refresh = useCallback(
    async (force = false) => {
      if (
        !online ||
        !available ||
        !navigator.onLine ||
        document.visibilityState !== 'visible' ||
        pending.current
      )
        return;
      if (!force && performance.now() - lastStarted.current < 60_000) return;
      const run = new AbortController();
      pending.current = run;
      lastStarted.current = performance.now();
      setPhase('loading');
      setRows([]);
      try {
        const identity = await browserAccount();
        if (run.signal.aborted || identity?.accountId !== accountId) return;
        const result = await readBrowserCommunityTraffic(accountId, journeyId, run.signal);
        if (!run.signal.aborted) {
          setRows(result.moments);
          setNow(performance.now());
          setPhase('ready');
        }
      } catch {
        if (!run.signal.aborted) setPhase('unavailable');
      } finally {
        if (pending.current === run) pending.current = null;
      }
    },
    [accountId, journeyId, online, available],
  );

  useEffect(() => {
    if (online && available) void refresh(true);
    const focus = () => {
      if (document.visibilityState !== 'visible') {
        pending.current?.abort();
        pending.current = null;
        setRows([]);
        return;
      }
      void refresh(true);
    };
    const timer = window.setInterval(() => {
      setNow(performance.now());
      void refresh();
    }, 30_000);
    window.addEventListener('focus', focus);
    document.addEventListener('visibilitychange', focus);
    return () => {
      pending.current?.abort();
      reportPending.current?.abort();
      window.clearInterval(timer);
      window.removeEventListener('focus', focus);
      document.removeEventListener('visibilitychange', focus);
    };
  }, [refresh, online, available]);

  useEffect(() => {
    if (!online || !available) {
      pending.current?.abort();
      reportPending.current?.abort();
      pending.current = null;
      reportPending.current = null;
      setRows([]);
    }
  }, [online, available]);

  const visible = online && available ? rows.filter((row) => row.deadlineMonotonic > now) : [];
  useEffect(() => {
    if (!online || !available || rows.length === 0) return;
    const nextExpiry = Math.min(
      ...rows.map((row) => row.deadlineMonotonic).filter((time) => time > performance.now()),
    );
    if (!Number.isFinite(nextExpiry)) return;
    const timer = window.setTimeout(
      () => setNow(performance.now()),
      Math.max(0, nextExpiry - performance.now()) + 1,
    );
    return () => window.clearTimeout(timer);
  }, [online, available, rows, now]);
  useEffect(() => {
    if (online && available && rows.length > 0 && visible.length === 0) setPhase('expired');
  }, [online, available, rows, visible.length]);

  async function report(row: CommunityTrafficTimedMoment) {
    if (
      !online ||
      !available ||
      !navigator.onLine ||
      reportPending.current ||
      row.deadlineMonotonic <= performance.now()
    )
      return;
    const attempt = reportAttempts.current.get(row.ref) ?? {
      reason: reason[row.ref] ?? 'INACCURATE',
      requestId: crypto.randomUUID(),
      confirmed: false,
    };
    if (attempt.confirmed) return;
    const run = new AbortController();
    reportPending.current = run;
    reportAttempts.current.set(row.ref, attempt);
    setReported((previous) => ({ ...previous, [row.ref]: 'Sending report…' }));
    try {
      const identity = await browserAccount();
      if (
        run.signal.aborted ||
        identity?.accountId !== accountId ||
        row.deadlineMonotonic <= performance.now()
      )
        throw new Error('Account changed');
      await reportBrowserCommunityTraffic(
        accountId,
        journeyId,
        row.ref,
        attempt.reason,
        attempt.requestId,
        run.signal,
      );
      if (!run.signal.aborted) attempt.confirmed = true;
      if (!run.signal.aborted)
        setReported((previous) => ({
          ...previous,
          [row.ref]:
            'Report received for review. This summary remains visible unless a moderator suppresses it.',
        }));
    } catch (failure) {
      if (!run.signal.aborted)
        setReported((previous) => ({
          ...previous,
          [row.ref]:
            failure instanceof BrowserLiveError && failure.kind === 'rate_limited'
              ? 'Report not sent. You can send up to 10 new reports in 24 hours; try again later.'
              : 'Report not confirmed. Try again if this summary is still visible.',
        }));
    } finally {
      if (reportPending.current === run) reportPending.current = null;
    }
  }

  return (
    <section className="community-traffic" aria-labelledby="community-traffic-title">
      <div className="community-traffic-head">
        <div>
          <h3 id="community-traffic-title">Community traffic</h3>
          <p>Recent reports from eligible travellers · separate from official alerts</p>
        </div>
        <button
          type="button"
          className="button secondary"
          disabled={!online || !available || phase === 'loading'}
          onClick={() => void refresh(true)}
        >
          Check traffic
        </button>
      </div>
      {!online && (
        <p role="status">
          Offline. Connect to check current community traffic. No saved community summaries are
          shown.
        </p>
      )}
      {online && !available && (
        <p role="status">
          Community traffic is paused until the current route and contribution settings are
          confirmed.
        </p>
      )}
      {online && available && phase === 'loading' && (
        <p role="status">Checking recent community reports…</p>
      )}
      {online && available && phase === 'unavailable' && (
        <p role="status">Community traffic could not be checked. Try again.</p>
      )}
      {online && available && phase === 'expired' && (
        <p role="status">Previous community summaries expired. Check again for recent reports.</p>
      )}
      {online && available && phase === 'ready' && visible.length === 0 && (
        <p role="status">
          No recent community update for this journey. This does not mean the road is clear.
        </p>
      )}
      {visible.length > 0 && (
        <ul className="community-traffic-list">
          {visible.map((row) => (
            <li key={row.ref}>
              <strong>{labels[row.trafficValue]}</strong>
              <span>
                {row.areaLabel} · observed {row.observationPeriod}
              </span>
              <span>
                Community report, unverified · Routiqo expiry{' '}
                {new Intl.DateTimeFormat('en-IN', { timeStyle: 'short' }).format(
                  new Date(row.expiresAt),
                )}{' '}
                (device time; check again to confirm availability)
              </span>
              <div className="community-traffic-report">
                <label htmlFor={`reason-${row.ref}`}>Report this summary</label>
                <select
                  id={`reason-${row.ref}`}
                  value={reason[row.ref] ?? 'INACCURATE'}
                  disabled={reportAttempts.current.has(row.ref)}
                  onChange={(event) =>
                    setReason((previous) => ({
                      ...previous,
                      [row.ref]: event.target.value as CommunityReportReason,
                    }))
                  }
                >
                  <option value="INACCURATE">Inaccurate</option>
                  <option value="UNSAFE">Unsafe</option>
                  <option value="SPAM">Spam</option>
                </select>
                <button
                  type="button"
                  className="button secondary"
                  disabled={
                    !online ||
                    !available ||
                    !!reportPending.current ||
                    reportAttempts.current.get(row.ref)?.confirmed
                  }
                  onClick={() => void report(row)}
                >
                  Send report
                </button>
              </div>
              {reported[row.ref] && <p role="status">{reported[row.ref]}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
