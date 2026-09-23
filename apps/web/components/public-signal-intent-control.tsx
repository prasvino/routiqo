'use client';

import { useEffect, useRef, useState } from 'react';
import { browserAccount } from '../lib/browser-auth';
import {
  shareBrowserLiveSignalPublicIntent,
  stopBrowserLiveSignalPublicIntent,
} from '../lib/browser-live';

interface Props {
  accountId: string;
  journeyId: string;
  commandId: string;
  receiptExpiresAt: string;
  eligible: boolean;
  identityConfirmed: boolean;
  online: boolean;
  shareEnabled: boolean;
  activeJourney: boolean;
}

type Phase =
  'idle' | 'sharing' | 'share_uncertain' | 'shared' | 'stopping' | 'stop_uncertain' | 'stopped';

const messages: Record<Phase, string> = {
  idle: 'This private observation has not been shared for public consideration.',
  sharing: 'Requesting public consideration… The result is not yet confirmed.',
  share_uncertain: 'The request result is uncertain. Use Stop; nothing will retry automatically.',
  shared: 'Public consideration was requested. This does not mean a Live Moment was published.',
  stopping: 'Stopping public consideration…',
  stop_uncertain: 'Stop is unconfirmed. Retry Stop for this receipt when connected.',
  stopped: 'Public consideration stopped for this receipt.',
};

export function PublicSignalIntentControl(props: Props) {
  const current = useRef(props);
  current.current = props;
  const mounted = useRef(true);
  const inFlight = useRef<{ controller: AbortController; kind: 'share' | 'stop' } | null>(null);
  const [phase, setPhase] = useState<Phase>('idle');
  const [understood, setUnderstood] = useState(false);
  const [safe, setSafe] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const run = inFlight.current;
    run?.controller.abort();
    inFlight.current = null;
    setPhase('idle');
    setUnderstood(false);
    setSafe(false);
  }, [props.accountId, props.journeyId, props.commandId]);

  useEffect(() => {
    mounted.current = true;
    const cancel = () => {
      const run = inFlight.current;
      if (!run) return;
      run.controller.abort();
      inFlight.current = null;
      setPhase(run.kind === 'share' ? 'share_uncertain' : 'stop_uncertain');
    };
    const offline = () => cancel();
    const hidden = () => {
      if (document.visibilityState !== 'visible') cancel();
    };
    window.addEventListener('offline', offline);
    document.addEventListener('visibilitychange', hidden);
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => {
      mounted.current = false;
      inFlight.current?.controller.abort();
      inFlight.current = null;
      window.clearInterval(timer);
      window.removeEventListener('offline', offline);
      document.removeEventListener('visibilitychange', hidden);
    };
  }, []);

  useEffect(() => {
    if (!props.online || !props.identityConfirmed) {
      const run = inFlight.current;
      if (run) {
        run.controller.abort();
        inFlight.current = null;
        setPhase(run.kind === 'share' ? 'share_uncertain' : 'stop_uncertain');
      }
    }
  }, [props.online, props.identityConfirmed]);

  const connected =
    props.identityConfirmed && props.online && typeof navigator !== 'undefined' && navigator.onLine;
  const canShare =
    connected &&
    props.eligible &&
    props.shareEnabled &&
    props.activeJourney &&
    Date.parse(props.receiptExpiresAt) > now &&
    phase === 'idle';

  async function verifyAccount(accountId: string, journeyId: string, commandId: string) {
    const identity = await browserAccount();
    return (
      mounted.current &&
      current.current.accountId === accountId &&
      current.current.journeyId === journeyId &&
      current.current.commandId === commandId &&
      current.current.identityConfirmed &&
      current.current.online &&
      navigator.onLine &&
      identity?.accountId === accountId
    );
  }

  async function share() {
    if (!canShare || !understood || !safe || inFlight.current) return;
    const { accountId, journeyId, commandId } = props;
    const run = { controller: new AbortController(), kind: 'share' as const };
    inFlight.current = run;
    setPhase('sharing');
    setUnderstood(false);
    setSafe(false);
    try {
      if (!(await verifyAccount(accountId, journeyId, commandId)))
        throw new Error('Account verification failed.');
      if (
        inFlight.current !== run ||
        run.controller.signal.aborted ||
        !current.current.eligible ||
        !current.current.activeJourney ||
        !current.current.shareEnabled ||
        Date.parse(current.current.receiptExpiresAt) <= Date.now()
      )
        return;
      await shareBrowserLiveSignalPublicIntent(
        accountId,
        journeyId,
        commandId,
        { requestId: crypto.randomUUID(), purpose: 'public-live-moment-v1' },
        run.controller.signal,
      );
      if (mounted.current && inFlight.current === run && !run.controller.signal.aborted)
        setPhase('shared');
    } catch {
      if (mounted.current && inFlight.current === run) setPhase('share_uncertain');
    } finally {
      if (inFlight.current === run) inFlight.current = null;
    }
  }

  async function stop() {
    if (
      !connected ||
      inFlight.current ||
      !['shared', 'share_uncertain', 'stop_uncertain'].includes(phase)
    )
      return;
    const { accountId, journeyId, commandId } = props;
    const run = { controller: new AbortController(), kind: 'stop' as const };
    inFlight.current = run;
    setPhase('stopping');
    try {
      if (!(await verifyAccount(accountId, journeyId, commandId)))
        throw new Error('Account verification failed.');
      if (inFlight.current !== run || run.controller.signal.aborted) return;
      await stopBrowserLiveSignalPublicIntent(
        accountId,
        journeyId,
        commandId,
        run.controller.signal,
      );
      if (mounted.current && inFlight.current === run && !run.controller.signal.aborted)
        setPhase('stopped');
    } catch {
      if (mounted.current && inFlight.current === run) setPhase('stop_uncertain');
    } finally {
      if (inFlight.current === run) inFlight.current = null;
    }
  }

  if ((!props.eligible || !props.shareEnabled) && phase === 'idle') return null;
  return (
    <div
      className="public-signal-intent-control"
      aria-label="Public consideration for this receipt"
    >
      <p>
        <strong>Optional public consideration</strong>
      </p>
      <p>
        You can request that this one private observation help form a coarse, delayed Live Moment.
        Routiqo may combine it with independent reports at a reviewed route area. Your account,
        exact location and individual report are not shown. A request may be rejected or never
        produce a public moment.
      </p>
      {phase === 'idle' && props.shareEnabled && (
        <>
          <label className="private-signal-safe">
            <input
              type="checkbox"
              checked={understood}
              onChange={(event) => setUnderstood(event.target.checked)}
            />
            I choose public consideration for this receipt only.
          </label>
          <label className="private-signal-safe">
            <input
              type="checkbox"
              checked={safe}
              onChange={(event) => setSafe(event.target.checked)}
            />
            I’m stopped or a passenger and can interact safely.
          </label>
          <button
            className="button secondary"
            disabled={!canShare || !understood || !safe}
            onClick={() => void share()}
          >
            Request public consideration
          </button>
        </>
      )}
      <p role="status">
        {!props.online
          ? 'Connect to request or stop public consideration. Nothing is queued offline.'
          : messages[phase]}
      </p>
      {phase === 'idle' && props.shareEnabled && !props.activeJourney && (
        <p>Only a current active journey can request public consideration.</p>
      )}
      {['shared', 'share_uncertain', 'stop_uncertain', 'stopping'].includes(phase) && (
        <button
          className="button secondary"
          disabled={!connected || phase === 'stopping'}
          onClick={() => void stop()}
        >
          Stop public consideration
        </button>
      )}
    </div>
  );
}
