'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { browserAccount } from '../lib/browser-auth';
import {
  readBrowserLivePublicIntents,
  stopBrowserLiveSignalPublicIntent,
  type LivePublicIntent,
} from '../lib/browser-live';

interface Props {
  accountId: string;
  identityConfirmed: boolean;
  online: boolean;
  onReady: (ready: boolean) => void;
}

export function PublicIntentRecoveryPanel(props: Props) {
  const current = useRef(props);
  current.current = props;
  const mounted = useRef(true);
  const checking = useRef<AbortController | null>(null);
  const stopping = useRef<AbortController | null>(null);
  const [items, setItems] = useState<readonly LivePublicIntent[]>([]);
  const [state, setState] = useState<'idle' | 'checking' | 'ready' | 'error'>('idle');
  const [stopState, setStopState] = useState<'idle' | 'stopping' | 'uncertain'>('idle');
  const [stopKey, setStopKey] = useState('');
  const notifyReady = useCallback((ready: boolean) => current.current.onReady(ready), []);

  const connected =
    props.identityConfirmed && props.online && typeof navigator !== 'undefined' && navigator.onLine;

  const check = useCallback(async () => {
    const p = current.current;
    if (!p.identityConfirmed || !p.online || !navigator.onLine || checking.current) return;
    const controller = new AbortController();
    checking.current = controller;
    setState('checking');
    notifyReady(false);
    try {
      const identity = await browserAccount();
      if (controller.signal.aborted || identity?.accountId !== p.accountId)
        throw new Error('Account changed.');
      const found = new Map<string, LivePublicIntent>();
      const seenCursors = new Set<string>();
      let cursor: string | null = null;
      for (let page = 0; page < 100; page++) {
        const result = await readBrowserLivePublicIntents(
          p.accountId,
          cursor ?? undefined,
          controller.signal,
        );
        if (
          controller.signal.aborted ||
          !mounted.current ||
          current.current.accountId !== p.accountId ||
          !current.current.identityConfirmed ||
          !current.current.online ||
          !navigator.onLine
        )
          return;
        for (const item of result.intents) found.set(`${item.journeyId}:${item.commandId}`, item);
        // Stop remains available for owner handles already checked if a later page fails.
        setItems((previous) => {
          const merged = new Map(
            previous.map((item) => [`${item.journeyId}:${item.commandId}`, item]),
          );
          for (const [key, item] of found) merged.set(key, item);
          return Array.from(merged.values());
        });
        if (result.nextCursor === null) {
          setItems(Array.from(found.values()));
          setState('ready');
          notifyReady(true);
          return;
        }
        if (seenCursors.has(result.nextCursor)) throw new Error('Repeated cursor.');
        seenCursors.add(result.nextCursor);
        cursor = result.nextCursor;
      }
      throw new Error('Intent listing exceeded page limit.');
    } catch {
      if (
        mounted.current &&
        !controller.signal.aborted &&
        current.current.accountId === p.accountId
      ) {
        setState('error');
        notifyReady(false);
      }
    } finally {
      if (checking.current === controller) checking.current = null;
    }
  }, [notifyReady]);

  useEffect(() => {
    mounted.current = true;
    if (props.identityConfirmed && props.online) void check();
    return () => {
      mounted.current = false;
      checking.current?.abort();
      stopping.current?.abort();
      checking.current = null;
      stopping.current = null;
      notifyReady(false);
    };
  }, [props.accountId, props.identityConfirmed, props.online, check, notifyReady]);

  useEffect(() => {
    if (props.identityConfirmed && props.online) return;
    checking.current?.abort();
    stopping.current?.abort();
    checking.current = null;
    stopping.current = null;
    setState('idle');
    if (stopState === 'stopping') setStopState('uncertain');
    notifyReady(false);
  }, [props.identityConfirmed, props.online, notifyReady, stopState]);

  async function stop(item: LivePublicIntent) {
    if (!connected || stopping.current) return;
    const accountId = props.accountId;
    const controller = new AbortController();
    stopping.current = controller;
    setStopKey(`${item.journeyId}:${item.commandId}`);
    setStopState('stopping');
    try {
      const identity = await browserAccount();
      if (
        controller.signal.aborted ||
        identity?.accountId !== accountId ||
        current.current.accountId !== accountId
      )
        throw new Error('Account changed.');
      await stopBrowserLiveSignalPublicIntent(
        accountId,
        item.journeyId,
        item.commandId,
        controller.signal,
      );
      if (!mounted.current || stopping.current !== controller || controller.signal.aborted) return;
      setItems((previous) =>
        previous.map((value) =>
          value.journeyId === item.journeyId && value.commandId === item.commandId
            ? { ...value, status: 'stopped' as const }
            : value,
        ),
      );
      setStopState('idle');
    } catch {
      if (mounted.current && stopping.current === controller) setStopState('uncertain');
    } finally {
      if (stopping.current === controller) stopping.current = null;
    }
  }

  return (
    <section
      className="live-consent-panel public-intent-recovery"
      aria-labelledby="public-intent-recovery-title"
    >
      <h3 id="public-intent-recovery-title">Public consideration requests</h3>
      <p>
        Review and stop your requests from this account, including after a journey ends. No public
        Live Moments are shown here.
      </p>
      {state === 'checking' && <p role="status">Checking your requests…</p>}
      {state === 'error' && (
        <p role="status">
          Requests could not be checked. Try again when connected. Previously checked Stop handles
          remain below.
        </p>
      )}
      {!connected && (
        <p role="status">Connect and confirm your account to check or stop requests.</p>
      )}
      <button
        className="button secondary"
        disabled={!connected || state === 'checking'}
        onClick={() => void check()}
      >
        Check requests
      </button>
      {state === 'ready' && items.length === 0 && (
        <p role="status">No retained requests were found for this account.</p>
      )}
      {items.some((item) => item.status === 'shared') && (
        <ul>
          {items
            .filter((item) => item.status === 'shared')
            .map((item, index) => {
              const key = `${item.journeyId}:${item.commandId}`;
              return (
                <li key={key}>
                  <p>
                    <strong>Request {index + 1}</strong> · Public consideration requested
                  </p>
                  <button
                    className="button secondary"
                    disabled={!connected || stopState === 'stopping'}
                    onClick={() => void stop(item)}
                  >
                    Stop request {index + 1}
                  </button>
                  {stopKey === key && stopState === 'uncertain' && (
                    <p role="status">Stop is unconfirmed. Retry Stop for this request.</p>
                  )}
                </li>
              );
            })}
        </ul>
      )}
      {items.length > 0 && items.every((item) => item.status === 'stopped') && (
        <p role="status">No active public consideration requests remain in the retained history.</p>
      )}
    </section>
  );
}
