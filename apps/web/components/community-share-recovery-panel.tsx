'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { browserAccount } from '../lib/browser-auth';
import {
  readBrowserCommunityShares,
  stopBrowserCommunityTraffic,
  type CommunityShareHandle,
} from '../lib/browser-community-traffic';

interface Props {
  accountId: string;
  online: boolean;
  identityConfirmed: boolean;
}

export function CommunityShareRecoveryPanel({ accountId, online, identityConfirmed }: Props) {
  const [handles, setHandles] = useState<CommunityShareHandle[]>([]);
  const [phase, setPhase] = useState<'idle' | 'checking' | 'ready' | 'error'>('idle');
  const [stopping, setStopping] = useState<string | null>(null);
  const [stopNotice, setStopNotice] = useState('');
  const checkRun = useRef<AbortController | null>(null);
  const stopRun = useRef<AbortController | null>(null);
  const connected =
    online && identityConfirmed && typeof navigator !== 'undefined' && navigator.onLine;

  const check = useCallback(async () => {
    if (!online || !identityConfirmed || !navigator.onLine || checkRun.current) return;
    const run = new AbortController();
    checkRun.current = run;
    setPhase('checking');
    try {
      const identity = await browserAccount();
      if (run.signal.aborted || identity?.accountId !== accountId)
        throw new Error('Account changed');
      const rows = await readBrowserCommunityShares(accountId, run.signal);
      if (!run.signal.aborted) {
        setHandles(rows);
        setPhase('ready');
      }
    } catch {
      if (!run.signal.aborted) {
        setPhase('error');
      }
    } finally {
      if (checkRun.current === run) checkRun.current = null;
    }
  }, [accountId, online, identityConfirmed]);

  useEffect(() => {
    if (online && identityConfirmed) void check();
    const update = () => {
      if (navigator.onLine) void check();
    };
    window.addEventListener('routiqo:community-share-changed', update);
    window.addEventListener('online', update);
    return () => {
      checkRun.current?.abort();
      stopRun.current?.abort();
      window.removeEventListener('routiqo:community-share-changed', update);
      window.removeEventListener('online', update);
    };
  }, [check, online, identityConfirmed]);

  useEffect(() => {
    if (!online || !identityConfirmed) {
      checkRun.current?.abort();
      stopRun.current?.abort();
      checkRun.current = null;
      stopRun.current = null;
      setHandles([]);
      setPhase('idle');
    }
  }, [online, identityConfirmed]);

  async function stop(item: CommunityShareHandle) {
    if (!connected || stopRun.current) return;
    const run = new AbortController();
    stopRun.current = run;
    setStopping(item.candidateId);
    setStopNotice('Stopping future sharing…');
    try {
      const identity = await browserAccount();
      if (run.signal.aborted || identity?.accountId !== accountId)
        throw new Error('Account changed');
      await stopBrowserCommunityTraffic(accountId, item.journeyId, item.commandId, run.signal);
      if (!run.signal.aborted) {
        setHandles((previous) =>
          previous.map((row) =>
            row.candidateId === item.candidateId
              ? { ...row, status: 'stopped_for_future_sharing' }
              : row,
          ),
        );
        setStopNotice(
          'Future sharing stopped. A summary already being prepared may still include this report.',
        );
      }
    } catch {
      if (!run.signal.aborted)
        setStopNotice('Stop is unconfirmed. Retry this same Stop when connected.');
    } finally {
      if (stopRun.current === run) stopRun.current = null;
      setStopping(null);
    }
  }

  return (
    <section
      className="live-consent-panel community-share-recovery"
      aria-labelledby="community-recovery-title"
    >
      <h3 id="community-recovery-title">Your community sharing</h3>
      <p>
        Check V3 traffic requests on this account, including from another device or a completed
        journey. Accepted means considered for a summary, not published.
      </p>
      {!connected && (
        <p role="status">Connect and confirm your account to recover or stop sharing.</p>
      )}
      {phase === 'checking' && <p role="status">Checking V3 sharing requests…</p>}
      {phase === 'error' && (
        <p role="status">
          Sharing requests could not be checked. Try again; previously checked Stop controls remain
          below. No new report will be sent automatically.
        </p>
      )}
      {phase === 'ready' && handles.length === 0 && (
        <p role="status">No recent V3 traffic sharing requests for this account.</p>
      )}
      <button
        type="button"
        className="button secondary"
        disabled={!connected || phase === 'checking'}
        onClick={() => void check()}
      >
        Check requests
      </button>
      {stopNotice && <p role="status">{stopNotice}</p>}
      {handles.length > 0 && (
        <ul>
          {handles.map((item, index) => (
            <li key={item.candidateId}>
              <strong>Traffic report {index + 1}</strong>
              <span>
                {item.status === 'accepted_for_consideration'
                  ? 'Accepted for consideration'
                  : 'Future sharing stopped'}{' '}
                · window ended{' '}
                {new Intl.DateTimeFormat('en-IN', {
                  dateStyle: 'medium',
                  timeStyle: 'short',
                }).format(new Date(item.windowEndsAt))}
              </span>
              {item.status === 'accepted_for_consideration' && (
                <button
                  type="button"
                  className="button secondary"
                  disabled={!connected || !!stopRun.current}
                  onClick={() => void stop(item)}
                >
                  {stopping === item.candidateId ? 'Stopping…' : 'Stop future sharing'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
