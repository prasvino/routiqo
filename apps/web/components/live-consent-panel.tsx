'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  BrowserLiveError,
  readBrowserLiveConsent,
  submitBrowserLiveConsent,
  type LiveConsent,
} from '../lib/browser-live';

const maximumGeneration = '9223372036854775807';
const uncertainMessage =
  'A previous change was not confirmed. Use Stop private contributions to establish a fresh off setting.';

interface LiveConsentPanelProps {
  accountId: string;
  journeyId: string;
  online: boolean;
  available: boolean;
}

interface ConfirmedConsent {
  identity: string;
  value: LiveConsent;
}

interface Notice {
  identity: string;
  message: string;
  alert: boolean;
}

export function LiveConsentPanel({
  accountId,
  journeyId,
  online,
  available,
}: LiveConsentPanelProps) {
  const identity = `${accountId}:${journeyId}`;
  const identityRef = useRef(identity);
  const onlineRef = useRef(online);
  const availableRef = useRef(available);
  const foregroundRef = useRef(true);
  identityRef.current = identity;
  onlineRef.current = online;
  availableRef.current = available;
  const operation = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const busyRef = useRef(false);
  const generation = useRef('0');
  const completedIdentity = useRef<string | null>(null);
  const uncertainRef = useRef<string | null>(null);
  const pendingMutation = useRef<{ identity: string; sharing: boolean } | null>(null);
  const restoreFocusFromAllow = useRef<string | null>(null);
  const stopButton = useRef<HTMLButtonElement | null>(null);
  const [consent, setConsent] = useState<ConfirmedConsent | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [foreground, setForeground] = useState(true);
  const [uncertainIdentity, setUncertainIdentity] = useState<string | null>(null);

  const abortCurrent = useCallback(() => {
    operation.current++;
    controller.current?.abort();
    controller.current = null;
    busyRef.current = false;
  }, []);

  const clear = useCallback(
    (message?: string) => {
      if (pendingMutation.current?.identity === identityRef.current) {
        uncertainRef.current = identityRef.current;
        setUncertainIdentity(identityRef.current);
      }
      pendingMutation.current = null;
      abortCurrent();
      setBusy(false);
      setConsent(null);
      setNotice(message ? { identity: identityRef.current, message, alert: false } : null);
    },
    [abortCurrent],
  );

  useEffect(() => {
    generation.current = '0';
    completedIdentity.current = null;
    uncertainRef.current = null;
    setUncertainIdentity(null);
    clear();
    return abortCurrent;
  }, [identity, clear, abortCurrent]);

  useEffect(() => {
    if (!online) clear('Connect to check or change private LIVE contribution settings.');
    else if (uncertainRef.current === identityRef.current)
      setNotice({ identity: identityRef.current, message: uncertainMessage, alert: true });
    else setNotice(null);
  }, [online, clear]);

  useEffect(() => {
    if (!available) clear();
    else if (uncertainRef.current === identityRef.current)
      setNotice({ identity: identityRef.current, message: uncertainMessage, alert: true });
  }, [available, clear]);

  useEffect(() => {
    foregroundRef.current = document.visibilityState === 'visible';
    setForeground(foregroundRef.current);
    if (!foregroundRef.current) clear();
    const hide = () => {
      foregroundRef.current = document.visibilityState === 'visible';
      setForeground(foregroundRef.current);
      if (!foregroundRef.current) clear();
      else if (uncertainRef.current === identityRef.current)
        setNotice({ identity: identityRef.current, message: uncertainMessage, alert: true });
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
        if (uncertainRef.current === identityRef.current)
          setNotice({ identity: identityRef.current, message: uncertainMessage, alert: true });
      }
    };
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('blur', blur);
    window.addEventListener('focus', focus);
    return () => {
      document.removeEventListener('visibilitychange', hide);
      window.removeEventListener('blur', blur);
      window.removeEventListener('focus', focus);
    };
  }, [clear]);

  useEffect(() => {
    if (busy || restoreFocusFromAllow.current === null) return;
    const requestedIdentity = restoreFocusFromAllow.current;
    restoreFocusFromAllow.current = null;
    if (
      requestedIdentity === identity &&
      available &&
      online &&
      foreground &&
      document.visibilityState === 'visible' &&
      document.activeElement === document.body
    )
      stopButton.current?.focus();
  }, [available, busy, foreground, identity, online]);

  function begin(): { token: number; identity: string; controller: AbortController } | null {
    if (
      busyRef.current ||
      !onlineRef.current ||
      !availableRef.current ||
      !foregroundRef.current ||
      document.visibilityState !== 'visible'
    )
      return null;
    busyRef.current = true;
    setBusy(true);
    const nextController = new AbortController();
    controller.current?.abort();
    controller.current = nextController;
    return {
      token: ++operation.current,
      identity: identityRef.current,
      controller: nextController,
    };
  }

  function current(run: { token: number; identity: string }): boolean {
    return (
      operation.current === run.token &&
      identityRef.current === run.identity &&
      onlineRef.current &&
      availableRef.current &&
      foregroundRef.current &&
      document.visibilityState === 'visible'
    );
  }

  function finish(run: { token: number; identity: string; controller: AbortController }) {
    if (!current(run)) return;
    if (controller.current === run.controller) controller.current = null;
    busyRef.current = false;
    setBusy(false);
  }

  function accept(run: { token: number; identity: string }, value: LiveConsent) {
    if (!current(run)) return;
    generation.current = value.generation;
    if (!value.journeyActive) completedIdentity.current = run.identity;
    setConsent({ identity: run.identity, value });
  }

  function failureNotice(failure: unknown, mutation: boolean): string {
    if (failure instanceof BrowserLiveError) {
      if (failure.kind === 'conflict')
        return 'LIVE settings changed on the server. Check LIVE settings before allowing contributions.';
      if (failure.kind === 'rate_limited')
        return 'Too many LIVE settings requests. Wait, then try again manually.';
      if (failure.kind === 'authentication')
        return 'Sign-in could not be confirmed. LIVE settings were cleared.';
      if (failure.kind === 'not_found')
        return 'This journey’s LIVE settings are unavailable. Check the current journey status.';
    }
    return mutation
      ? 'The LIVE setting was not confirmed. Check LIVE settings before allowing contributions.'
      : 'LIVE settings could not be checked. Try again when you’re ready.';
  }

  async function check() {
    const run = begin();
    if (!run) return;
    setNotice({ identity: run.identity, message: 'Checking LIVE settings…', alert: false });
    try {
      const value = await readBrowserLiveConsent(accountId, journeyId, run.controller.signal);
      if (!current(run)) return;
      accept(run, value);
      setNotice({
        identity: run.identity,
        message:
          uncertainRef.current === run.identity
            ? uncertainMessage
            : value.journeyActive
              ? value.sharing
                ? 'Last checked: private LIVE contribution preparation is allowed for this journey.'
                : 'Last checked: private LIVE contribution preparation is off for this journey.'
              : 'This journey is complete. Contributions cannot be enabled.',
        alert: false,
      });
    } catch (failure) {
      if (!current(run)) return;
      setConsent(null);
      setNotice({ identity: run.identity, message: failureNotice(failure, false), alert: true });
    } finally {
      finish(run);
    }
  }

  async function change(sharing: boolean) {
    const visible = consent?.identity === identity ? consent.value : null;
    if (
      sharing &&
      (!visible ||
        visible.sharing ||
        !visible.journeyActive ||
        visible.generation === maximumGeneration ||
        completedIdentity.current === identity ||
        uncertainRef.current === identity)
    )
      return;
    const expectedGeneration = visible?.generation ?? generation.current;
    const run = begin();
    if (!run) return;
    pendingMutation.current = { identity: run.identity, sharing };
    setNotice({
      identity: run.identity,
      message: sharing ? 'Allowing contributions…' : 'Stopping contributions…',
      alert: false,
    });
    try {
      const value = await submitBrowserLiveConsent(
        accountId,
        journeyId,
        { expectedGeneration, sharing },
        run.controller.signal,
      );
      if (!current(run)) return;
      pendingMutation.current = null;
      if (!sharing) {
        uncertainRef.current = null;
        setUncertainIdentity(null);
      }
      accept(run, value);
      setNotice({
        identity: run.identity,
        message: value.journeyActive
          ? value.sharing
            ? 'Private LIVE contribution preparation is allowed for this journey. Public LIVE remains unavailable.'
            : 'Private LIVE contribution preparation is stopped for this journey.'
          : 'This journey is complete. Contributions cannot be enabled.',
        alert: false,
      });
    } catch (failure) {
      if (!current(run)) return;
      pendingMutation.current = null;
      uncertainRef.current = run.identity;
      setUncertainIdentity(run.identity);
      setConsent(null);
      setNotice({ identity: run.identity, message: failureNotice(failure, true), alert: true });
    } finally {
      finish(run);
    }
  }

  const visible = consent?.identity === identity ? consent.value : null;
  const completed = completedIdentity.current === identity;
  const visibleNotice = notice?.identity === identity ? notice : null;
  const displayedNotice = visibleNotice ?? {
    identity,
    message: 'Settings have not been checked.',
    alert: false,
  };
  const uncertain = uncertainIdentity === identity;
  const canAllow =
    online &&
    foreground &&
    !busy &&
    !completed &&
    !uncertain &&
    visible?.journeyActive === true &&
    visible.sharing === false &&
    visible.generation !== maximumGeneration;

  if (!available) return null;

  return (
    <aside className="live-consent-panel" aria-labelledby="live-consent-title">
      <span className="eyebrow">PRIVATE LIVE</span>
      <h3 id="live-consent-title">Contribution settings</h3>
      <p>
        Choose whether to allow private LIVE contributions for this journey. Public LIVE is
        unavailable. This does not publish your location or make you discoverable.
      </p>
      <p>Stopping does not delete private contributions or receipts already saved.</p>
      {completed && <p>This journey was reported complete. Contributions cannot be enabled.</p>}
      <div className="detail-actions live-consent-actions">
        <button
          className="button secondary"
          disabled={busy || !online || !foreground}
          onClick={() => void check()}
        >
          {busy ? 'Working…' : 'Check LIVE settings'}
        </button>
        {canAllow && (
          <button
            className="button primary"
            disabled={busy}
            onClick={() => {
              restoreFocusFromAllow.current = identity;
              void change(true);
            }}
          >
            Allow private contributions
          </button>
        )}
        <button
          ref={stopButton}
          className="button secondary"
          disabled={busy || !online || !foreground}
          onClick={() => void change(false)}
        >
          Stop private contributions
        </button>
      </div>
      <p
        className={displayedNotice.alert ? 'form-error live-consent-notice' : 'live-consent-notice'}
        role={displayedNotice.alert ? 'alert' : 'status'}
      >
        {displayedNotice.message}
      </p>
    </aside>
  );
}
