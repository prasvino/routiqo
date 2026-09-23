'use client';

import { useEffect, useRef, useState } from 'react';
import { browserAccount } from '../lib/browser-auth';
import {
  readBrowserCommunityShares,
  shareBrowserCommunityTraffic,
  stopBrowserCommunityTraffic,
} from '../lib/browser-community-traffic';

interface Props {
  accountId: string;
  journeyId: string;
  commandId: string;
  receiptExpiresAt: string;
  online: boolean;
  identityConfirmed: boolean;
  activeJourney: boolean;
}
type Phase =
  | 'idle'
  | 'checking'
  | 'sharing'
  | 'accepted'
  | 'uncertain'
  | 'stopping'
  | 'stop_uncertain'
  | 'stopped'
  | 'expired'
  | 'conflict';

export function CommunityShareControl(props: Props) {
  const current = useRef(props);
  current.current = props;
  const pending = useRef<AbortController | null>(null);
  const requestId = useRef<string | null>(null);
  const [phase, setPhase] = useState<Phase>('idle');
  const [consent, setConsent] = useState(false);
  const [safe, setSafe] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    const offline = () => {
      if (pending.current) {
        pending.current.abort();
        pending.current = null;
        setPhase('uncertain');
      }
    };
    const hidden = () => {
      if (document.visibilityState !== 'visible') offline();
    };
    window.addEventListener('offline', offline);
    document.addEventListener('visibilitychange', hidden);
    return () => {
      pending.current?.abort();
      window.clearInterval(timer);
      window.removeEventListener('offline', offline);
      document.removeEventListener('visibilitychange', hidden);
    };
  }, []);

  const connected =
    props.online && props.identityConfirmed && typeof navigator !== 'undefined' && navigator.onLine;
  const eligible = connected && props.activeJourney && Date.parse(props.receiptExpiresAt) > now;

  async function ownAccount(run: AbortController) {
    const identity = await browserAccount();
    return (
      !run.signal.aborted &&
      identity?.accountId === props.accountId &&
      current.current.accountId === props.accountId &&
      current.current.journeyId === props.journeyId &&
      current.current.commandId === props.commandId &&
      current.current.online &&
      navigator.onLine
    );
  }

  async function share() {
    if (!eligible || !safe || !consent || pending.current || !['idle', 'uncertain'].includes(phase))
      return;
    const run = new AbortController();
    pending.current = run;
    setPhase('checking');
    try {
      if (!(await ownAccount(run))) throw new Error('Account changed');
      const existing = await readBrowserCommunityShares(props.accountId, run.signal);
      const found = existing.find(
        (item) => item.journeyId === props.journeyId && item.commandId === props.commandId,
      );
      if (found) {
        setPhase(found.status === 'stopped_for_future_sharing' ? 'stopped' : 'accepted');
        return;
      }
      if (
        run.signal.aborted ||
        !current.current.activeJourney ||
        !current.current.online ||
        !current.current.identityConfirmed ||
        Date.parse(current.current.receiptExpiresAt) <= Date.now()
      ) {
        setPhase('conflict');
        return;
      }
      const exactRequestId = requestId.current ?? crypto.randomUUID();
      requestId.current = exactRequestId;
      setPhase('sharing');
      const result = await shareBrowserCommunityTraffic(
        props.accountId,
        props.journeyId,
        props.commandId,
        exactRequestId,
        run.signal,
      );
      if (run.signal.aborted) return;
      setPhase(
        result.status === 'stopped_for_future_sharing'
          ? 'stopped'
          : result.status === 'expired'
            ? 'expired'
            : 'accepted',
      );
      window.dispatchEvent(new Event('routiqo:community-share-changed'));
    } catch (failure) {
      if (run.signal.aborted) return;
      // A conflict cannot authorize another request id or command. Owner recovery remains available.
      setPhase(
        failure instanceof Error && 'kind' in failure && failure.kind === 'conflict'
          ? 'conflict'
          : 'uncertain',
      );
    } finally {
      if (pending.current === run) pending.current = null;
      setConsent(false);
      setSafe(false);
    }
  }

  async function stop() {
    if (
      !connected ||
      pending.current ||
      !['accepted', 'uncertain', 'conflict', 'stop_uncertain'].includes(phase)
    )
      return;
    const run = new AbortController();
    pending.current = run;
    setPhase('stopping');
    try {
      if (!(await ownAccount(run))) throw new Error('Account changed');
      await stopBrowserCommunityTraffic(
        props.accountId,
        props.journeyId,
        props.commandId,
        run.signal,
      );
      if (!run.signal.aborted) {
        setPhase('stopped');
        window.dispatchEvent(new Event('routiqo:community-share-changed'));
      }
    } catch {
      if (!run.signal.aborted) setPhase('stop_uncertain');
    } finally {
      if (pending.current === run) pending.current = null;
    }
  }

  return (
    <div className="community-share-control" aria-label="Optional community traffic sharing">
      <h5>Share this traffic report?</h5>
      <p>
        Other eligible travellers may see a recent coarse traffic summary, never your name or exact
        location. Someone with other information may still infer that you contributed. You can stop
        future sharing, but a summary already being prepared or published may still include this
        report. Published summaries expire from Routiqo; copies saved by others cannot be recalled.
      </p>
      {['idle', 'uncertain'].includes(phase) && (
        <>
          <label>
            <input
              type="checkbox"
              checked={consent}
              onChange={(event) => setConsent(event.target.checked)}
            />{' '}
            I choose community traffic sharing for this private report only.
          </label>
          <label>
            <input
              type="checkbox"
              checked={safe}
              onChange={(event) => setSafe(event.target.checked)}
            />{' '}
            I’m stopped or a passenger and can interact safely.
          </label>
          <button
            type="button"
            className="button secondary"
            disabled={!eligible || !consent || !safe || !!pending.current}
            onClick={() => void share()}
          >
            {phase === 'uncertain' ? 'Retry this exact request' : 'Share for consideration'}
          </button>
        </>
      )}
      <p role="status">
        {!connected
          ? 'Connect and confirm your account to share or stop. Nothing is queued offline.'
          : phase === 'idle'
            ? 'This report remains private until you choose Share.'
            : phase === 'checking'
              ? 'Checking your existing V3 sharing requests…'
              : phase === 'sharing'
                ? 'Sending your request. The result is not confirmed yet.'
                : phase === 'accepted'
                  ? 'Accepted for consideration. This does not mean a summary was published.'
                  : phase === 'uncertain'
                    ? 'Share result is uncertain. Recover your account’s requests or retry this exact request.'
                    : phase === 'conflict'
                      ? 'This request conflicts with current sharing authority. Check your account’s V3 requests.'
                      : phase === 'stopping'
                        ? 'Stopping future sharing…'
                        : phase === 'stop_uncertain'
                          ? 'Stop is unconfirmed. Retry Stop when connected.'
                          : phase === 'expired'
                            ? 'This Share request expired. It cannot be reactivated.'
                            : 'Future sharing stopped. A summary already being prepared may still include this report.'}
      </p>
      {!props.activeJourney && phase === 'idle' && (
        <p>An active journey is needed to start sharing.</p>
      )}
      {['accepted', 'uncertain', 'conflict', 'stop_uncertain'].includes(phase) && (
        <button
          type="button"
          className="button secondary"
          disabled={!connected || !!pending.current}
          onClick={() => void stop()}
        >
          Stop future sharing
        </button>
      )}
    </div>
  );
}
