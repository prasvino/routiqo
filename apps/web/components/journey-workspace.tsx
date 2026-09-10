'use client';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { JourneyCommand } from '@routiqo/shared';
import { authAvailability, browserAccount } from '../lib/browser-auth';
import {
  dispatchBrowserJourneyBatch,
  restoreBrowserJourneyAuthentication,
} from '../lib/journey-dispatch';
import {
  queueBrowserJourneyAction,
  reconcileBrowserJourney,
  readBrowserJourneyPartition,
  type BrowserJourneyPartition,
} from '../lib/journey-storage';
import { Modal } from './modal';
import { readBrowserJourney } from '../lib/browser-journeys';
import { restoreRecentBrowserJourneyHistory } from '../lib/journey-restoration';

export function JourneyWorkspace() {
  const [availability, setAvailability] = useState<
    'checking' | 'disabled' | 'signed-out' | 'ready'
  >('checking');
  const [account, setAccount] = useState<string | null>(null);
  const [partition, setPartition] = useState<BrowserJourneyPartition | null>(null);
  const [error, setError] = useState('');
  const [review, setReview] = useState('');
  const [busy, setBusy] = useState(false);
  const [start, setStart] = useState(false);
  const [kind, setKind] = useState<'trip' | 'commute'>('trip');
  const revision = useRef(0);
  const locked = useRef(false);
  const visibleAccount = useRef<string | null>(null);
  const inFlight = useRef<AbortController | null>(null);
  const invalidate = useCallback(() => {
    revision.current++;
    inFlight.current?.abort();
  }, []);
  const refresh = useCallback(async () => {
    if (locked.current) return;
    const current = ++revision.current;
    try {
      const config = await authAvailability();
      const identity = config.enabled ? await browserAccount() : null;
      const saved = identity ? await readBrowserJourneyPartition(identity.accountId) : null;
      if (revision.current !== current) return;
      if (visibleAccount.current !== (identity?.accountId ?? null)) setStart(false);
      if (visibleAccount.current !== (identity?.accountId ?? null)) setReview('');
      visibleAccount.current = identity?.accountId ?? null;
      setAccount(identity?.accountId ?? null);
      setPartition(saved);
      setAvailability(!config.enabled ? 'disabled' : identity ? 'ready' : 'signed-out');
      setError('');
    } catch {
      if (revision.current === current)
        setError('Journey status is unavailable. Saved actions stay on this device.');
    }
  }, []);
  useEffect(() => {
    void refresh();
    const focus = () => {
      if (document.visibilityState === 'visible' && !locked.current) void refresh();
    };
    window.addEventListener('focus', focus);
    return () => {
      invalidate();
      window.removeEventListener('focus', focus);
    };
  }, [refresh, invalidate]);
  const perform = useCallback(
    async (command?: JourneyCommand) => {
      if (!account || locked.current) return;
      locked.current = true;
      setBusy(true);
      setError('');
      const current = ++revision.current;
      const controller = new AbortController();
      inFlight.current = controller;
      try {
        // Already verified account owns the offline partition; transport re-verifies before every send.
        if (command) {
          const saved = await queueBrowserJourneyAction(account, command, Date.now());
          if (revision.current === current) {
            setPartition(saved);
            setStart(false);
          }
        }
        if (navigator.onLine) {
          if (!(await restoreBrowserJourneyAuthentication(account))) {
            if (revision.current === current) {
              setAccount(null);
              setPartition(null);
              setAvailability('signed-out');
              setStart(false);
              visibleAccount.current = null;
            }
            return;
          }
          await dispatchBrowserJourneyBatch(account, controller.signal);
        }
        const saved = await readBrowserJourneyPartition(account);
        if (revision.current === current) setPartition(saved);
      } catch (failure) {
        if (revision.current === current)
          setError(
            failure instanceof Error
              ? failure.message
              : 'Your action could not be confirmed. Try again.',
          );
      } finally {
        locked.current = false;
        if (inFlight.current === controller) inFlight.current = null;
        if (revision.current === current) setBusy(false);
      }
    },
    [account],
  );
  const head = partition?.outbox.entries[0];
  async function restoreRecent() {
    if (!account || locked.current) return;
    locked.current = true;
    setBusy(true);
    setError('');
    const current = ++revision.current;
    try {
      const restored = await restoreRecentBrowserJourneyHistory(account);
      if (current === revision.current) {
        setPartition(restored.partition);
        setReview(
          `Checked ${restored.recentCount} recent server journeys. Pending actions are preserved.`,
        );
      }
    } catch {
      if (current === revision.current)
        setError('Recent journeys could not be restored. Saved work is unchanged.');
    } finally {
      locked.current = false;
      if (current === revision.current) setBusy(false);
    }
  }
  async function reviewConflict() {
    if (!account || !head || locked.current) return;
    locked.current = true;
    setBusy(true);
    setReview('');
    const current = ++revision.current;
    try {
      const journey = await readBrowserJourney(account, head.command.journeyId);
      if (
        journey &&
        (head.command.action === 'start'
          ? journey.kind === head.command.kind
          : journey.status === 'completed')
      ) {
        const reconciled = await reconcileBrowserJourney(account, head.command, journey);
        const saved = await readBrowserJourneyPartition(account);
        if (current === revision.current) {
          setPartition(saved);
          setReview(
            reconciled
              ? 'The server already saved this action. Your device is now up to date.'
              : 'The saved action changed while checking. Review the current status.',
          );
        }
        return;
      }
      if (current === revision.current)
        setReview(
          journey
            ? `Server status: ${journey.status}. Your saved action remains paused until reconciliation is available.`
            : 'This journey was not found for your account. Your saved action has been preserved.',
        );
    } catch {
      if (current === revision.current)
        setReview('Server status could not be checked. Your saved action has been preserved.');
    } finally {
      locked.current = false;
      if (current === revision.current) setBusy(false);
    }
  }
  useEffect(() => {
    if (!head || head.blocked || availability !== 'ready') return;
    const retry = () => {
      const due = Math.max(head.nextAttemptAt, head.lease?.expiresAt ?? 0);
      if (document.visibilityState === 'visible' && navigator.onLine && Date.now() >= due)
        void perform();
    };
    const delay = Math.max(
      1000,
      Math.min(300000, Math.max(head.nextAttemptAt, head.lease?.expiresAt ?? 0) - Date.now()),
    );
    const timer = setTimeout(retry, delay);
    const fallback = setInterval(retry, 60000);
    window.addEventListener('online', retry);
    document.addEventListener('visibilitychange', retry);
    return () => {
      clearTimeout(timer);
      clearInterval(fallback);
      window.removeEventListener('online', retry);
      document.removeEventListener('visibilitychange', retry);
    };
  }, [head, availability, perform]);
  const active = partition?.snapshots.journeys.find((journey) => journey.status === 'active');
  const completed =
    partition?.snapshots.journeys.filter((journey) => journey.status === 'completed').slice(0, 3) ??
    [];
  const pendingStart = partition?.outbox.entries.find((entry) => entry.command.action === 'start');
  const currentId = active?.id ?? pendingStart?.command.journeyId;
  const completing = partition?.outbox.entries.some(
    (entry) => entry.command.journeyId === currentId && entry.command.action === 'complete',
  );
  const status =
    head?.blocked === 'authentication'
      ? 'Sign in again to send your saved action.'
      : head?.blocked === 'conflict'
        ? 'Another journey or a changed record needs review. Your action is preserved.'
        : head?.blocked === 'rejected'
          ? 'This action needs review before it can be sent. Your saved work is preserved.'
          : completing
            ? 'Finish saved on this device. Waiting for confirmation.'
            : pendingStart
              ? 'Start saved on this device. Waiting for confirmation.'
              : active
                ? 'Your journey is active.'
                : completed.length
                  ? 'Your last journey is complete.'
                  : 'No active journey on this device.';
  return (
    <section className="journey-workspace" aria-label="Current journey">
      <div>
        <span className="eyebrow">CURRENT JOURNEY</span>
        <h2>Your current journey.</h2>
      </div>
      {availability === 'checking' && !error && <p role="status">Checking journey availability…</p>}
      {availability === 'disabled' && (
        <p>
          Journey start and finish will be available when account sign-in is configured. You can
          keep planning below.
        </p>
      )}
      {availability === 'signed-out' && (
        <p>
          <Link href="/profile">Sign in from Profile</Link> to start a journey. Your local plans
          stay here.
        </p>
      )}
      {availability === 'ready' && (
        <>
          <p role="status">{status}</p>
          <div className="detail-actions">
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => void restoreRecent()}
            >
              Restore recent journeys
            </button>
            {!currentId && !head && (
              <button className="button primary" disabled={busy} onClick={() => setStart(true)}>
                Start a journey
              </button>
            )}
            {currentId && !completing && !head?.blocked && (
              <button
                className="button primary"
                disabled={busy}
                onClick={() => void perform({ action: 'complete', journeyId: currentId })}
              >
                Finish journey
              </button>
            )}
            {head && (
              <button
                className="button secondary"
                disabled={busy || head.blocked === 'conflict' || head.blocked === 'rejected'}
                onClick={() => void perform()}
              >
                {busy ? 'Checking…' : 'Retry saved actions'}
              </button>
            )}
            {head?.blocked === 'authentication' && <Link href="/profile">Open sign-in</Link>}
            {(head?.blocked === 'conflict' || head?.blocked === 'rejected') && (
              <button
                className="button secondary"
                disabled={busy}
                onClick={() => void reviewConflict()}
              >
                Check server status
              </button>
            )}
          </div>
          {review && <p role="status">{review}</p>}
          <p className="journey-note">
            Only journey type and start/finish records are sent. Your planning notes and places stay
            on this device.
          </p>
          {completed.length > 0 && (
            <div className="journey-history">
              <h3>Recently completed</h3>
              <p className="journey-note">Recent confirmations saved on this device.</p>
              <ul>
                {completed.map((journey) => (
                  <li key={journey.id}>
                    <strong>
                      {journey.kind === 'commute' ? 'Daily commute' : 'Trip / travel'}
                    </strong>
                    <span>
                      Finished{' '}
                      {new Intl.DateTimeFormat('en-IN', {
                        dateStyle: 'medium',
                        timeStyle: 'short',
                      }).format(new Date(journey.completedAt!))}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
      {error && (
        <>
          <p className="form-error" role="alert">
            {error}
          </p>
          <button className="button secondary" disabled={busy} onClick={() => void refresh()}>
            Refresh status
          </button>
        </>
      )}
      {start && (
        <Modal
          title="Start a journey"
          onClose={() => {
            if (!busy) setStart(false);
          }}
        >
          <p className="modal-intro">
            Start a separate journey record. This won’t change your saved plans or share your
            location.
          </p>
          <label className="journey-kind">
            Journey type
            <select
              value={kind}
              disabled={busy}
              onChange={(event) => setKind(event.target.value === 'commute' ? 'commute' : 'trip')}
            >
              <option value="trip">Trip / travel</option>
              <option value="commute">Daily commute</option>
            </select>
          </label>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <div className="detail-actions">
            <button
              className="button primary"
              disabled={busy}
              onClick={() =>
                void perform({ action: 'start', kind, journeyId: crypto.randomUUID() })
              }
            >
              {busy ? 'Saving…' : 'Start journey'}
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
