'use client';

import { useEffect, useRef, useState } from 'react';
import type { ServerJourney } from '@routiqo/shared';
import {
  readBrowserJourneyPage,
  type JourneyHistoryCursor,
  type JourneyHistoryPage,
} from '../lib/browser-history';

interface JourneyHistoryProps {
  account: string;
  onOpenJournal: (id: string) => void;
}

interface DisplayedPage {
  cursor: JourneyHistoryCursor | null;
  page: JourneyHistoryPage;
}

interface ReadFailure {
  cursor: JourneyHistoryCursor | null;
}

function failureStatus(failure: unknown): number | null {
  if (typeof failure !== 'object' || failure === null || !('status' in failure)) return null;
  const status = (failure as { status?: unknown }).status;
  return typeof status === 'number' ? status : null;
}

function formatInstant(value: string): string {
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}

function JourneyRow({
  journey,
  onOpenJournal,
}: {
  journey: ServerJourney;
  onOpenJournal: (id: string) => void;
}) {
  return (
    <li>
      <h3>
        {journey.kind === 'trip' ? 'Trip' : 'Commute'} ·{' '}
        {journey.status === 'completed' ? 'Completed' : 'Active'}
      </h3>
      <p>
        Started <time dateTime={journey.startedAt}>{formatInstant(journey.startedAt)}</time>
      </p>
      {journey.completedAt !== null && (
        <p>
          Completed <time dateTime={journey.completedAt}>{formatInstant(journey.completedAt)}</time>
        </p>
      )}
      {journey.kind === 'trip' && journey.status === 'completed' && (
        <button
          className="button secondary"
          type="button"
          onClick={() => onOpenJournal(journey.id)}
        >
          Open journal
        </button>
      )}
    </li>
  );
}

function AccountJourneyHistory({ account, onOpenJournal }: JourneyHistoryProps) {
  const [displayed, setDisplayed] = useState<DisplayedPage | null>(null);
  const [failure, setFailure] = useState<ReadFailure | null>(null);
  const [authenticationFailed, setAuthenticationFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const mounted = useRef(true);
  const revision = useRef(0);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      revision.current += 1;
      controller.current?.abort();
    };
  }, []);

  async function load(cursor: JourneyHistoryCursor | null) {
    if (busy) return;
    const requestRevision = ++revision.current;
    controller.current?.abort();
    const requestController = new AbortController();
    controller.current = requestController;
    setBusy(true);
    setFailure(null);
    setAuthenticationFailed(false);
    try {
      const page = await readBrowserJourneyPage(account, cursor, requestController.signal);
      if (!mounted.current || requestRevision !== revision.current) return;
      setDisplayed({
        cursor,
        page: { ...page, journeys: page.journeys.slice(0, 20) },
      });
    } catch (requestFailure) {
      if (
        requestController.signal.aborted ||
        !mounted.current ||
        requestRevision !== revision.current
      )
        return;
      const status = failureStatus(requestFailure);
      if (status === 401 || status === 403) {
        setDisplayed(null);
        setFailure(null);
        setAuthenticationFailed(true);
      } else {
        setFailure({ cursor });
      }
    } finally {
      if (mounted.current && requestRevision === revision.current) {
        setBusy(false);
        controller.current = null;
      }
    }
  }

  return (
    <details className="commute-summaries">
      <summary>Account journey history</summary>
      <p className="fine-print">
        Load journeys saved to your account when you have a connection. Summaries and queued actions
        saved on this device are separate.
      </p>

      {authenticationFailed ? (
        <>
          <p role="alert">Sign in again to load account history.</p>
          <button
            className="button secondary"
            type="button"
            disabled={busy}
            onClick={() => void load(null)}
          >
            Try again after signing in
          </button>
        </>
      ) : displayed === null ? (
        <button
          className="button secondary"
          type="button"
          disabled={busy}
          onClick={() => void load(null)}
        >
          Load account history
        </button>
      ) : displayed.page.journeys.length === 0 ? (
        <p>No journeys are saved in your account history.</p>
      ) : (
        <ul>
          {displayed.page.journeys.map((journey) => (
            <JourneyRow key={journey.id} journey={journey} onOpenJournal={onOpenJournal} />
          ))}
        </ul>
      )}

      {busy && <p role="status">Loading account history…</p>}
      {failure !== null && (
        <>
          <p role="alert">Account history is unavailable. Check your connection and try again.</p>
          <button
            className="button secondary"
            type="button"
            disabled={busy}
            onClick={() => void load(failure.cursor)}
          >
            Retry account history
          </button>
        </>
      )}
      {displayed !== null && (
        <p>
          {displayed.cursor !== null && (
            <button
              className="button secondary"
              type="button"
              disabled={busy}
              onClick={() => void load(null)}
            >
              Latest journeys
            </button>
          )}{' '}
          {displayed.page.next !== null && (
            <button
              className="button secondary"
              type="button"
              disabled={busy}
              onClick={() => void load(displayed.page.next)}
            >
              Earlier journeys
            </button>
          )}
        </p>
      )}
    </details>
  );
}

export function JourneyHistory(props: JourneyHistoryProps) {
  return <AccountJourneyHistory key={props.account} {...props} />;
}
