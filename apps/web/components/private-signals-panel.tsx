'use client';

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  acceptBrowserLiveSignalCommand,
  issueBrowserLiveExpectedSignalCommand,
  readBrowserLiveSignalChoices,
  stopBrowserLiveSignalCommand,
  type LiveSignalAcceptance,
  type LiveSignalChoiceSnapshot,
} from '../lib/browser-live';
import { readInstant } from '../lib/browser-live-private';
import { browserAccount } from '../lib/browser-auth';
import {
  LiveSignalRecoveryCoordinator,
  type LiveSignalRecoveryTicket,
} from '../lib/live-signal-recovery';
import { privateSignalOptions } from '../lib/private-signal-options';
import { usePrivateSignalNavigation } from '../lib/use-private-signal-navigation';
import type {
  RoutePlannerContributionAuthority,
  RoutePlannerContributionSource,
} from './route-planner';
import { Modal } from './modal';
import { PublicSignalIntentControl } from './public-signal-intent-control';
import { CommunityShareControl } from './community-share-control';

interface Props {
  coordinator: LiveSignalRecoveryCoordinator;
  accountId: string;
  journeyId: string | null;
  identityConfirmed: boolean;
  online: boolean;
  available: boolean;
  source: RoutePlannerContributionSource | null;
  publicIntentControls?: boolean;
  publicIntentShareEnabled?: boolean;
  communityTrafficV3Enabled?: boolean;
}
interface ChoiceState {
  authority: RoutePlannerContributionAuthority;
  snapshot: LiveSignalChoiceSnapshot;
}
interface Operation {
  controller: AbortController;
  authority: RoutePlannerContributionAuthority;
  kind: 'checking' | 'submitting';
  ticket?: LiveSignalRecoveryTicket;
}
function sameAuthority(
  a: RoutePlannerContributionAuthority | null,
  b: RoutePlannerContributionAuthority | null,
) {
  return (
    !!a &&
    !!b &&
    a.accountId === b.accountId &&
    a.journeyId === b.journeyId &&
    a.contextId === b.contextId &&
    a.routeRevision === b.routeRevision &&
    a.consentGeneration === b.consentGeneration &&
    a.consentEpoch === b.consentEpoch &&
    a.selectionEpoch === b.selectionEpoch &&
    a.expiresAt === b.expiresAt
  );
}
function fresh(value: string) {
  return readInstant(value).nanoseconds > BigInt(Date.now()) * 1_000_000n;
}
const phases = {
  accepting: 'Sending privately. The result is not yet confirmed.',
  acceptance_uncertain:
    'Submission is unconfirmed. Stop this command before sending another observation.',
  accepted: 'Saved privately. This is not a public LIVE post.',
  accepted_metadata_expired:
    'Receipt details expired on this page. This does not confirm server deletion or stopping.',
  stopping: 'Stopping this command…',
  stop_uncertain: 'Stopping is unconfirmed. Retry stopping the same command when connected.',
  stopped: 'This command cannot create a new acceptance. It may have been accepted earlier.',
} as const;

export function PrivateSignalsPanel(props: Props) {
  const coordinator = props.coordinator;
  const currentProps = useRef(props);
  currentProps.current = props;
  const mounted = useRef(true);
  const panel = useRef<HTMLElement>(null);
  const restoreFocus = useRef(false);
  const foreground = useRef(true);
  const pending = useRef<Operation | null>(null);
  const stopPending = useRef<{
    controller: AbortController;
    ticket: LiveSignalRecoveryTicket;
  } | null>(null);
  const [choice, setChoice] = useState<ChoiceState | null>(null);
  const choiceRef = useRef(choice);
  choiceRef.current = choice;
  const [anchorId, setAnchorId] = useState('');
  const [value, setValue] = useState<LiveSignalAcceptance['value'] | ''>('');
  const [safe, setSafe] = useState(false);
  const [notice, setNotice] = useState(
    'Prepare your route, then check the private signals available for it.',
  );
  const [busy, setBusy] = useState(false);
  const [, refresh] = useState(0);
  const [forget, setForget] = useState<{ journeyId: string; commandId: string } | null>(null);
  const trafficReceipts = useRef(new Set<string>());
  const redraw = useCallback(() => {
    if (mounted.current) refresh((n) => n + 1);
  }, []);
  const invalidateNew = useCallback(() => {
    const active = document.activeElement;
    if (
      active instanceof HTMLElement &&
      panel.current?.contains(active) &&
      active.closest('.private-signal-fields')
    )
      restoreFocus.current = true;
    const run = pending.current;
    pending.current = null;
    run?.controller.abort();
    if (run?.ticket) coordinator.markAcceptanceUncertain(run.ticket);
    setChoice(null);
    setAnchorId('');
    setValue('');
    setSafe(false);
    setBusy(false);
    setNotice(
      coordinator.snapshot(currentProps.current.accountId).length
        ? 'Check the current prepared route and contribution settings before sending again. Known commands remain below.'
        : 'Prepare your route with current contribution settings, then check available signals.',
    );
    redraw();
  }, [coordinator, redraw]);
  useLayoutEffect(() => {
    if (!restoreFocus.current) return;
    restoreFocus.current = false;
    if (document.activeElement === document.body)
      panel.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
  });
  function canRecover() {
    const p = currentProps.current;
    return (
      mounted.current && p.identityConfirmed && p.online && navigator.onLine && foreground.current
    );
  }
  function authority() {
    const p = currentProps.current;
    if (!canRecover() || !p.available || !p.journeyId) return null;
    const a = p.source?.read() ?? null;
    return a && a.accountId === p.accountId && a.journeyId === p.journeyId && fresh(a.expiresAt)
      ? a
      : null;
  }
  function current(run: Operation) {
    return (
      pending.current === run &&
      !run.controller.signal.aborted &&
      sameAuthority(authority(), run.authority)
    );
  }
  useLayoutEffect(() => {
    invalidateNew();
    return props.source?.subscribe(invalidateNew);
  }, [props.source, props.journeyId, props.available, invalidateNew]);
  useLayoutEffect(() => {
    if (!props.online || !props.identityConfirmed) {
      invalidateNew();
      const stopping = stopPending.current;
      stopping?.controller.abort();
      if (stopping) coordinator.markStopUncertain(stopping.ticket);
      stopPending.current = null;
      setForget(null);
    }
  }, [props.online, props.identityConfirmed, coordinator, invalidateNew]);
  useEffect(() => {
    mounted.current = true;
    const hide = () => {
      foreground.current = document.visibilityState === 'visible';
      if (!foreground.current) invalidateNew();
      redraw();
    };
    const blur = () => {
      foreground.current = false;
      invalidateNew();
    };
    const focus = () => {
      foreground.current = document.visibilityState === 'visible';
      redraw();
    };
    const offline = () => {
      invalidateNew();
      const stopping = stopPending.current;
      stopping?.controller.abort();
      if (stopping) coordinator.markStopUncertain(stopping.ticket);
      stopPending.current = null;
      redraw();
    };
    const expire = () => {
      if (
        (pending.current && !sameAuthority(authority(), pending.current.authority)) ||
        (choiceRef.current && !fresh(choiceRef.current.snapshot.expiresAt))
      )
        invalidateNew();
      // Refresh only local expiry/availability, never send requests.
      redraw();
    };
    hide();
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('blur', blur);
    window.addEventListener('focus', focus);
    window.addEventListener('offline', offline);
    const timer = window.setInterval(expire, 1000);
    return () => {
      mounted.current = false;
      pending.current?.controller.abort();
      stopPending.current?.controller.abort();
      if (pending.current?.ticket) coordinator.markAcceptanceUncertain(pending.current.ticket);
      if (stopPending.current) coordinator.markStopUncertain(stopPending.current.ticket);
      pending.current = null;
      stopPending.current = null;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', hide);
      window.removeEventListener('blur', blur);
      window.removeEventListener('focus', focus);
      window.removeEventListener('offline', offline);
    };
    // Event handlers read current props through the ref.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [coordinator, invalidateNew, redraw]);

  async function check() {
    if (pending.current || stopPending.current) return;
    const a = authority();
    if (!a) {
      setNotice(
        'Prepare this route again with current contribution settings before checking signals.',
      );
      return;
    }
    const run: Operation = { controller: new AbortController(), authority: a, kind: 'checking' };
    pending.current = run;
    setBusy(true);
    setChoice(null);
    setSafe(false);
    setValue('');
    setAnchorId('');
    setNotice('Checking private signal choices…');
    try {
      const snapshot = await readBrowserLiveSignalChoices(
        a.accountId,
        a.journeyId,
        run.controller.signal,
      );
      if (!current(run)) return;
      if (
        snapshot.contextId !== a.contextId ||
        snapshot.routeRevision !== a.routeRevision ||
        snapshot.consentGeneration !== a.consentGeneration ||
        !fresh(snapshot.expiresAt)
      ) {
        setNotice('The prepared route changed. Prepare it again before choosing a signal.');
        return;
      }
      setChoice({ authority: a, snapshot });
      setNotice('Choose a route area and an observation. Nothing is published publicly.');
    } catch {
      if (current(run))
        setNotice('Private signal choices could not be checked. Try again when connected.');
    } finally {
      if (pending.current === run) {
        pending.current = null;
        setBusy(false);
      }
    }
  }

  async function submit() {
    const a = authority();
    if (
      !a ||
      !choice ||
      !sameAuthority(a, choice.authority) ||
      !fresh(choice.snapshot.expiresAt) ||
      !safe ||
      !value ||
      pending.current ||
      stopPending.current ||
      !coordinator.canBeginIssuance(a.accountId)
    )
      return;
    const selected = choice.snapshot.choices.find((item) => item.anchorId === anchorId);
    if (!selected || !selected.categories.includes(privateSignalOptions[value][0])) return;
    const input: LiveSignalAcceptance = {
      anchorId,
      value,
      contextId: a.contextId,
      routeRevision: a.routeRevision,
      consentGeneration: a.consentGeneration,
    };
    const run: Operation = { controller: new AbortController(), authority: a, kind: 'submitting' };
    pending.current = run;
    setBusy(true);
    setSafe(false);
    setNotice('Sending one private observation…');
    try {
      const grant = await issueBrowserLiveExpectedSignalCommand(
        a.accountId,
        a.journeyId,
        {
          anchorId,
          contextId: a.contextId,
          routeRevision: a.routeRevision,
          consentGeneration: a.consentGeneration,
        },
        run.controller.signal,
      );
      if (!current(run)) return;
      run.ticket = coordinator.beginAcceptance(a.accountId, a.journeyId, grant.commandId);
      redraw();
      if (
        !grant.categories.includes(privateSignalOptions[input.value][0]) ||
        !fresh(grant.expiresAt)
      ) {
        coordinator.markAcceptanceUncertain(run.ticket);
        setNotice('This command cannot be submitted. Use Stop to finish recovery.');
        return;
      }
      const receipt = await acceptBrowserLiveSignalCommand(
        a.accountId,
        a.journeyId,
        grant.commandId,
        input,
        run.controller.signal,
      );
      if (!current(run)) {
        coordinator.markAcceptanceUncertain(run.ticket);
        redraw();
        return;
      }
      coordinator.confirmAcceptance(run.ticket, receipt);
      if (input.value.startsWith('traffic_')) trafficReceipts.current.add(grant.commandId);
      restoreFocus.current = panel.current?.contains(document.activeElement) ?? false;
      setChoice(null);
      setValue('');
      setAnchorId('');
      setNotice('The private command returned a receipt. Public LIVE remains unavailable.');
      redraw();
    } catch {
      if (pending.current !== run) return;
      if (run.ticket) coordinator.markAcceptanceUncertain(run.ticket);
      setNotice(
        run.ticket
          ? 'The submission result is uncertain. Use Stop below; nothing will retry automatically.'
          : 'Command issuance could not be confirmed. No acceptance was started; nothing will retry automatically.',
      );
      redraw();
    } finally {
      if (pending.current === run) {
        pending.current = null;
        setBusy(false);
      }
    }
  }

  async function stop(journeyId: string, commandId: string) {
    if (!canRecover() || stopPending.current) return;
    invalidateNew();
    const accountId = currentProps.current.accountId;
    const ticket = coordinator.beginStop(accountId, journeyId, commandId);
    const run = { ticket, controller: new AbortController() };
    stopPending.current = run;
    redraw();
    try {
      const identity = await browserAccount();
      if (!mounted.current || stopPending.current !== run || run.controller.signal.aborted) return;
      if (
        !canRecover() ||
        currentProps.current.accountId !== accountId ||
        identity?.accountId !== accountId
      ) {
        coordinator.markStopUncertain(ticket);
        setNotice('Confirm your account before retrying Stop.');
        return;
      }
      const result = await stopBrowserLiveSignalCommand(
        accountId,
        journeyId,
        commandId,
        run.controller.signal,
      );
      if (!mounted.current || stopPending.current !== run || run.controller.signal.aborted) return;
      coordinator.confirmStop(ticket, result);
      restoreFocus.current = panel.current?.contains(document.activeElement) ?? false;
      setNotice('Command stopped. This does not delete retained private history.');
    } catch {
      if (mounted.current && stopPending.current === run) {
        coordinator.markStopUncertain(ticket);
        setNotice('Stopping could not be confirmed. Retry Stop for this command.');
      }
    } finally {
      if (stopPending.current === run) {
        stopPending.current = null;
        redraw();
      }
    }
  }

  const activeAuthority = authority();
  const visibleChoice =
    choice && sameAuthority(activeAuthority, choice.authority) && fresh(choice.snapshot.expiresAt)
      ? choice
      : null;
  const selected = visibleChoice?.snapshot.choices.find((item) => item.anchorId === anchorId);
  const records = coordinator.snapshot(props.accountId);
  usePrivateSignalNavigation(
    records.some((record) => record.phase !== 'stopped') || pending.current?.kind === 'submitting',
  );
  const options = Object.entries(privateSignalOptions).filter(([, [category]]) =>
    selected?.categories.includes(category),
  );
  const visible = props.identityConfirmed;
  const recoveryAllowed = canRecover();
  if ((!props.journeyId || !props.available) && records.length === 0) return null;
  if (!visible)
    return records.length ? (
      <p role="status">Checking your account before showing private command recovery…</p>
    ) : null;
  return (
    <section
      ref={panel}
      className="live-consent-panel private-signals-panel"
      aria-labelledby="private-signals-title"
    >
      <span className="eyebrow">PRIVATE LIVE</span>
      <h3 id="private-signals-title">Quick Signals</h3>
      <p>
        Share a structured observation privately for your prepared route. Public LIVE is not
        available.
      </p>
      {props.journeyId && props.available && (
        <>
          <button
            className="button secondary"
            disabled={!recoveryAllowed || busy || !!stopPending.current}
            onClick={() => void check()}
          >
            {busy ? 'Working…' : 'Check available signals'}
          </button>
          {visibleChoice && (
            <div className="private-signal-fields">
              <label>
                Route area
                <select
                  value={anchorId}
                  disabled={busy}
                  onChange={(event) => {
                    setAnchorId(event.target.value);
                    setValue('');
                    setSafe(false);
                  }}
                >
                  <option value="">Choose a route area</option>
                  {visibleChoice.snapshot.choices.map((item) => (
                    <option key={item.anchorId} value={item.anchorId}>
                      {item.displayLabel}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Observation
                <select
                  value={value}
                  disabled={busy || !selected}
                  onChange={(event) => {
                    setValue(event.target.value as LiveSignalAcceptance['value'] | '');
                    setSafe(false);
                  }}
                >
                  <option value="">Choose an observation</option>
                  {options.map(([id, [, label]]) => (
                    <option key={id} value={id}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="private-signal-safe">
                <input
                  type="checkbox"
                  checked={safe}
                  disabled={busy || !recoveryAllowed}
                  onChange={(event) => setSafe(event.target.checked)}
                />
                I’m stopped or a passenger and can interact safely.
              </label>
              <button
                className="button primary"
                disabled={
                  !safe ||
                  !value ||
                  busy ||
                  !recoveryAllowed ||
                  !coordinator.canBeginIssuance(props.accountId)
                }
                onClick={() => void submit()}
              >
                Send privately
              </button>
            </div>
          )}
        </>
      )}
      <p className="live-consent-notice" role="status">
        {!props.online
          ? 'Connect to check or send signals and stop commands. Nothing is queued offline.'
          : notice}
      </p>
      {records.length > 0 && (
        <div className="private-signal-recovery">
          <h4>Your private commands</h4>
          <p>
            These recovery controls stay only while this page is open. Leaving or reloading loses
            them; it does not stop server work.
          </p>
          <ul>
            {records.map((record, index) => (
              <li key={record.commandId}>
                <p>
                  <strong>Private command {index + 1}</strong>
                </p>
                <p role="status">{phases[record.phase]}</p>
                {props.publicIntentControls && record.receipt && (
                  <PublicSignalIntentControl
                    key={`${record.accountId}:${record.journeyId}:${record.commandId}`}
                    accountId={props.accountId}
                    journeyId={record.journeyId}
                    commandId={record.commandId}
                    receiptExpiresAt={record.receipt.expiresAt}
                    eligible={record.receipt.status === 'accepted'}
                    identityConfirmed={props.identityConfirmed}
                    online={props.online}
                    shareEnabled={props.publicIntentShareEnabled === true}
                    activeJourney={
                      props.available &&
                      props.journeyId === record.journeyId &&
                      record.phase === 'accepted' &&
                      !!activeAuthority
                    }
                  />
                )}
                {props.communityTrafficV3Enabled &&
                  record.receipt?.status === 'accepted' &&
                  trafficReceipts.current.has(record.commandId) && (
                    <CommunityShareControl
                      key={`v3:${record.accountId}:${record.journeyId}:${record.commandId}`}
                      accountId={props.accountId}
                      journeyId={record.journeyId}
                      commandId={record.commandId}
                      receiptExpiresAt={record.receipt.expiresAt}
                      identityConfirmed={props.identityConfirmed}
                      online={props.online}
                      activeJourney={
                        props.available &&
                        props.journeyId === record.journeyId &&
                        record.phase === 'accepted' &&
                        !!activeAuthority
                      }
                    />
                  )}
                <div className="detail-actions live-consent-actions">
                  {record.phase !== 'stopped' && (
                    <button
                      className="button secondary"
                      disabled={!recoveryAllowed || !!stopPending.current}
                      onClick={() => void stop(record.journeyId, record.commandId)}
                    >
                      Stop command {index + 1}
                    </button>
                  )}
                  <button
                    className="button secondary"
                    disabled={record.phase === 'accepting' || record.phase === 'stopping'}
                    onClick={() => {
                      if (record.phase === 'stopped') {
                        coordinator.dismissTerminal(
                          props.accountId,
                          record.journeyId,
                          record.commandId,
                        );
                        redraw();
                      } else
                        setForget({ journeyId: record.journeyId, commandId: record.commandId });
                    }}
                  >
                    {record.phase === 'stopped' ? 'Dismiss' : 'Remove from this page'}
                  </button>
                </div>
              </li>
            ))}
          </ul>
          {!coordinator.canBeginIssuance(props.accountId) && (
            <p>
              Resolve uncertain commands or dismiss a notice before sending another observation.
            </p>
          )}
        </div>
      )}
      {forget && (
        <Modal title="Remove local recovery controls?" onClose={() => setForget(null)}>
          <p>
            This only removes the command from this page. It does not stop the command or delete
            server history. Use Stop first if you want to stop further acceptance.
          </p>
          <div className="detail-actions">
            <button className="button secondary" onClick={() => setForget(null)}>
              Keep controls
            </button>
            <button
              className="button primary"
              onClick={() => {
                coordinator.forgetRecovery(props.accountId, forget.journeyId, forget.commandId, {
                  acknowledgeServerMayContinue: true,
                });
                setForget(null);
                redraw();
              }}
            >
              Remove local controls
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
