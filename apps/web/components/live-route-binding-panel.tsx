'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { RouteCoordinate, RouteMode } from '@routiqo/shared';
import {
  bindBrowserLiveRouteContext,
  BrowserLiveError,
  readBrowserLiveRouteContext,
  type LiveRouteContext,
} from '../lib/browser-live';

export interface LiveConsentAuthority {
  accountId: string;
  journeyId: string;
  generation: string;
  epoch: number;
}

export interface RouteBindingSelection {
  mode: RouteMode;
  origin: RouteCoordinate;
  destination: RouteCoordinate;
  alternativeIndex: number;
}

export interface RouteBindingSelectionSnapshot {
  selection: RouteBindingSelection | null;
  epoch: number;
}

interface LiveRouteBindingPanelProps {
  accountId: string;
  journeyId: string;
  online: boolean;
  available: boolean;
  authority: LiveConsentAuthority;
  getAuthority: () => LiveConsentAuthority | null;
  selectionSnapshot: RouteBindingSelectionSnapshot;
  getSelectionSnapshot: () => RouteBindingSelectionSnapshot;
}

interface Operation {
  token: number;
  identity: string;
  authority: LiveConsentAuthority;
  selectionEpoch: number;
  controller: AbortController;
}

interface ObservedContext {
  identity: string;
  authorityEpoch: number;
  selectionEpoch: number;
  contextId: string | null;
  expiresAt: bigint | null;
}

interface Notice {
  scope: string;
  message: string;
  alert: boolean;
}

interface AcknowledgementExpiry {
  scope: string;
  expiresAt: bigint;
}

function scopeKey(identity: string, authorityEpoch: number, selectionEpoch: number): string {
  return `${identity}|${authorityEpoch}|${selectionEpoch}`;
}

function instantNanoseconds(value: string): bigint {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match) return 0n;
  const milliseconds = Date.parse(`${match[1]}Z`);
  if (!Number.isFinite(milliseconds)) return 0n;
  return BigInt(milliseconds) * 1_000_000n + BigInt((match[2] ?? '').padEnd(9, '0') || '0');
}

function copySelection(selection: RouteBindingSelection): RouteBindingSelection {
  return {
    mode: selection.mode,
    origin: [selection.origin[0], selection.origin[1]],
    destination: [selection.destination[0], selection.destination[1]],
    alternativeIndex: selection.alternativeIndex,
  };
}

export function LiveRouteBindingPanel({
  accountId,
  journeyId,
  online,
  available,
  authority,
  getAuthority,
  selectionSnapshot,
  getSelectionSnapshot,
}: LiveRouteBindingPanelProps) {
  const identity = `${accountId}:${journeyId}`;
  const renderedScope = scopeKey(identity, authority.epoch, selectionSnapshot.epoch);
  const identityRef = useRef(identity);
  const scopeRef = useRef(renderedScope);
  const onlineRef = useRef(online);
  const availableRef = useRef(available);
  const foregroundRef = useRef(true);
  const authorityGetter = useRef(getAuthority);
  const selectionGetter = useRef(getSelectionSnapshot);
  identityRef.current = identity;
  scopeRef.current = renderedScope;
  onlineRef.current = online;
  availableRef.current = available;
  authorityGetter.current = getAuthority;
  selectionGetter.current = getSelectionSnapshot;
  const operation = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const busyRef = useRef(false);
  const restoreFocus = useRef(false);
  const checkButton = useRef<HTMLButtonElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [foreground, setForeground] = useState(true);
  const [observed, setObserved] = useState<ObservedContext | null>(null);
  const [acknowledgementExpiry, setAcknowledgementExpiry] = useState<AcknowledgementExpiry | null>(
    null,
  );
  const [notice, setNotice] = useState<Notice | null>(null);

  const abortCurrent = useCallback(() => {
    operation.current++;
    controller.current?.abort();
    controller.current = null;
    busyRef.current = false;
  }, []);

  const clear = useCallback(
    (message?: string) => {
      abortCurrent();
      setBusy(false);
      setObserved(null);
      setAcknowledgementExpiry(null);
      setNotice(message ? { scope: scopeRef.current, message, alert: false } : null);
    },
    [abortCurrent],
  );

  useEffect(() => {
    clear();
    return abortCurrent;
  }, [identity, authority.epoch, selectionSnapshot.epoch, clear, abortCurrent]);

  useEffect(() => {
    if (!online) clear('Connect to check or prepare private route context.');
    else setNotice(null);
  }, [online, clear]);

  useEffect(() => {
    if (!available) clear();
  }, [available, clear]);

  useEffect(() => {
    foregroundRef.current = document.visibilityState === 'visible';
    setForeground(foregroundRef.current);
    if (!foregroundRef.current) clear();
    const visibility = () => {
      foregroundRef.current = document.visibilityState === 'visible';
      setForeground(foregroundRef.current);
      if (!foregroundRef.current) clear();
    };
    const blur = () => {
      foregroundRef.current = false;
      setForeground(false);
      clear();
    };
    const focus = () => {
      if (document.visibilityState === 'visible') {
        foregroundRef.current = true;
        setForeground(true);
      }
    };
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('blur', blur);
    window.addEventListener('focus', focus);
    return () => {
      document.removeEventListener('visibilitychange', visibility);
      window.removeEventListener('blur', blur);
      window.removeEventListener('focus', focus);
    };
  }, [clear]);

  useEffect(() => {
    if (!observed?.expiresAt) return;
    const observedScope = scopeKey(
      observed.identity,
      observed.authorityEpoch,
      observed.selectionEpoch,
    );
    const remaining = observed.expiresAt - BigInt(Date.now()) * 1_000_000n;
    if (remaining <= 0n) {
      setObserved(null);
      setNotice({
        scope: observedScope,
        message: 'The last observation expired. Check route preparation again.',
        alert: false,
      });
      return;
    }
    const timer = window.setTimeout(
      () => {
        setObserved(null);
        setNotice({
          scope: observedScope,
          message: 'The last observation expired. Check route preparation again.',
          alert: false,
        });
      },
      Number((remaining + 999_999n) / 1_000_000n),
    );
    return () => window.clearTimeout(timer);
  }, [observed]);

  useEffect(() => {
    if (acknowledgementExpiry === null) return;
    if (acknowledgementExpiry.scope !== renderedScope) {
      setAcknowledgementExpiry(null);
      return;
    }
    const remaining = acknowledgementExpiry.expiresAt - BigInt(Date.now()) * 1_000_000n;
    if (remaining <= 0n) {
      setAcknowledgementExpiry(null);
      setNotice({
        scope: acknowledgementExpiry.scope,
        message: 'The confirmed preparation expired. Check again.',
        alert: false,
      });
      return;
    }
    const timer = window.setTimeout(
      () => {
        setAcknowledgementExpiry(null);
        setNotice({
          scope: acknowledgementExpiry.scope,
          message: 'The confirmed preparation expired. Check again.',
          alert: false,
        });
      },
      Number((remaining + 999_999n) / 1_000_000n),
    );
    return () => window.clearTimeout(timer);
  }, [acknowledgementExpiry, renderedScope]);

  useEffect(() => {
    if (busy || !restoreFocus.current) return;
    restoreFocus.current = false;
    if (
      available &&
      online &&
      foreground &&
      document.visibilityState === 'visible' &&
      document.activeElement === document.body
    )
      checkButton.current?.focus();
  }, [available, busy, foreground, online]);

  function sameAuthority(expected: LiveConsentAuthority): boolean {
    const current = authorityGetter.current();
    return (
      current !== null &&
      current.accountId === expected.accountId &&
      current.journeyId === expected.journeyId &&
      current.generation === expected.generation &&
      current.epoch === expected.epoch
    );
  }

  function begin(): Operation | null {
    const currentAuthority = authorityGetter.current();
    const currentSelection = selectionGetter.current();
    if (
      busyRef.current ||
      !currentAuthority ||
      currentAuthority.accountId !== accountId ||
      currentAuthority.journeyId !== journeyId ||
      currentAuthority.epoch !== authority.epoch ||
      !onlineRef.current ||
      !availableRef.current ||
      !foregroundRef.current ||
      document.visibilityState !== 'visible'
    )
      return null;
    busyRef.current = true;
    setBusy(true);
    setObserved(null);
    setAcknowledgementExpiry(null);
    const nextController = new AbortController();
    controller.current?.abort();
    controller.current = nextController;
    return {
      token: ++operation.current,
      identity: identityRef.current,
      authority: currentAuthority,
      selectionEpoch: currentSelection.epoch,
      controller: nextController,
    };
  }

  function current(run: Operation): boolean {
    return (
      operation.current === run.token &&
      identityRef.current === run.identity &&
      onlineRef.current &&
      availableRef.current &&
      foregroundRef.current &&
      document.visibilityState === 'visible' &&
      sameAuthority(run.authority) &&
      selectionGetter.current().epoch === run.selectionEpoch
    );
  }

  function finish(run: Operation) {
    if (!current(run)) return;
    if (controller.current === run.controller) controller.current = null;
    busyRef.current = false;
    setBusy(false);
  }

  function failureMessage(failure: unknown): string {
    if (failure instanceof BrowserLiveError) {
      if (failure.kind === 'conflict')
        return 'Route preparation changed or is no longer allowed. Check route preparation before trying again.';
      if (failure.kind === 'rate_limited')
        return 'Too many route preparation requests. Wait, then try again manually.';
      if (failure.kind === 'authentication')
        return 'Sign-in could not be confirmed. Route preparation state was cleared.';
      if (failure.kind === 'not_found')
        return 'This journey’s private route preparation is unavailable.';
    }
    return 'Private route preparation could not be confirmed. Check again before retrying.';
  }

  async function check() {
    const run = begin();
    if (!run) return;
    const runScope = scopeKey(run.identity, run.authority.epoch, run.selectionEpoch);
    setNotice({ scope: runScope, message: 'Checking private route preparation…', alert: false });
    try {
      const result = await readBrowserLiveRouteContext(accountId, journeyId, run.controller.signal);
      if (!current(run)) return;
      const now = BigInt(Date.now()) * 1_000_000n;
      const expiresAt = result.context ? instantNanoseconds(result.context.expiresAt) : null;
      if (result.context) {
        const issuedAt = instantNanoseconds(result.context.issuedAt);
        if (issuedAt > now || expiresAt === null || expiresAt <= now) {
          setNotice({
            scope: runScope,
            message: 'The returned observation had invalid timing. Check again.',
            alert: true,
          });
          return;
        }
      }
      setObserved({
        identity: run.identity,
        authorityEpoch: run.authority.epoch,
        selectionEpoch: run.selectionEpoch,
        contextId: result.context?.contextId ?? null,
        expiresAt,
      });
      setNotice({
        scope: runScope,
        message: result.context
          ? 'Last checked: private route preparation exists for this journey. This is only an observed snapshot.'
          : 'Last checked: no private route preparation was returned. This is only an observed snapshot.',
        alert: false,
      });
    } catch (failure) {
      if (!current(run)) return;
      setNotice({ scope: runScope, message: failureMessage(failure), alert: true });
    } finally {
      finish(run);
    }
  }

  async function prepare() {
    const observation = observed;
    const currentAuthority = authorityGetter.current();
    const currentSelection = selectionGetter.current();
    if (
      !observation ||
      observation.identity !== identity ||
      !currentAuthority ||
      observation.authorityEpoch !== currentAuthority.epoch ||
      observation.selectionEpoch !== currentSelection.epoch ||
      !currentSelection.selection ||
      (observation.expiresAt !== null && observation.expiresAt <= BigInt(Date.now()) * 1_000_000n)
    ) {
      setObserved(null);
      setNotice({
        scope: renderedScope,
        message: 'Check route preparation before preparing this route.',
        alert: true,
      });
      return;
    }
    const input = copySelection(currentSelection.selection);
    const expectedContextId = observation.contextId;
    const run = begin();
    if (!run || run.selectionEpoch !== currentSelection.epoch) return;
    const runScope = scopeKey(run.identity, run.authority.epoch, run.selectionEpoch);
    restoreFocus.current = true;
    setNotice({
      scope: runScope,
      message: 'Preparing a private route context…',
      alert: false,
    });
    try {
      const result = await bindBrowserLiveRouteContext(
        accountId,
        journeyId,
        { ...input, expectedContextId },
        run.controller.signal,
      );
      if (!current(run)) return;
      if (result.status === 'bound') {
        const context = result.context as LiveRouteContext;
        const now = BigInt(Date.now()) * 1_000_000n;
        const issuedAt = instantNanoseconds(context.issuedAt);
        const expiry = instantNanoseconds(context.expiresAt);
        if (issuedAt > now || expiry <= now) {
          setNotice({
            scope: runScope,
            message: 'The confirmed route preparation had invalid timing. Check again.',
            alert: true,
          });
          return;
        }
        setAcknowledgementExpiry({ scope: runScope, expiresAt: expiry });
        setNotice({
          scope: runScope,
          message:
            'Private route preparation was confirmed for this request. It is unpublished and does not prove physical presence.',
          alert: false,
        });
      } else {
        setNotice({
          scope: runScope,
          message:
            result.status === 'no_route'
              ? 'No route was prepared. An earlier private context may still exist; check again before retrying.'
              : 'No eligible route areas were prepared. An earlier private context may still exist; check again before retrying.',
          alert: false,
        });
      }
    } catch (failure) {
      if (!current(run)) return;
      setNotice({ scope: runScope, message: failureMessage(failure), alert: true });
    } finally {
      finish(run);
    }
  }

  const observationCurrent =
    observed?.identity === identity &&
    observed.authorityEpoch === authority.epoch &&
    observed.selectionEpoch === selectionSnapshot.epoch &&
    (observed.expiresAt === null || observed.expiresAt > BigInt(Date.now()) * 1_000_000n);
  const canPrepare = observationCurrent && selectionSnapshot.selection !== null;
  const displayedNotice =
    notice?.scope === renderedScope
      ? notice
      : {
          scope: renderedScope,
          message: 'Route preparation has not been checked.',
          alert: false,
        };

  if (!available) return null;
  return (
    <aside className="live-route-binding-panel" aria-labelledby="live-route-binding-title">
      <span className="eyebrow">PRIVATE LIVE ROUTE</span>
      <h3 id="live-route-binding-title">Prepare this route privately</h3>
      <p>
        Preparing sends the selected endpoints and travel mode to Routiqo’s routing service again.
        It uses a fresh calculation, which may differ from the estimate shown here, and does not
        publish your route or location.
      </p>
      <div className="detail-actions live-route-binding-actions">
        <button
          ref={checkButton}
          type="button"
          className="button secondary"
          disabled={busy || !online || !foreground}
          onClick={() => void check()}
        >
          {busy ? 'Working…' : 'Check route preparation'}
        </button>
        {canPrepare && (
          <button
            type="button"
            className="button primary"
            disabled={busy || !online || !foreground}
            onClick={() => void prepare()}
          >
            Prepare selected route
          </button>
        )}
      </div>
      {!selectionSnapshot.selection && (
        <p>Calculate and select a route alternative before preparing it.</p>
      )}
      <p
        className={
          displayedNotice.alert
            ? 'form-error live-route-binding-notice'
            : 'live-route-binding-notice'
        }
        role={displayedNotice.alert ? 'alert' : 'status'}
      >
        {displayedNotice.message}
      </p>
    </aside>
  );
}
