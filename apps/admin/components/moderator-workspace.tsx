'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminGoogleSignIn } from './admin-google-sign-in';
import {
  adminLogout,
  adminSession,
  AdminApiError,
  clearPendingDecision,
  decide,
  restorePendingDecision,
  reviewQueue,
  savePendingDecision,
  type AdminSession,
  type PendingDecision,
  type ReviewAction,
  type ReviewItem,
  type ReviewReason,
} from '../lib/admin-client';

const reasons: { value: ReviewReason; label: string }[] = [
  { value: 'INACCURATE', label: 'Inaccurate condition' },
  { value: 'UNSAFE', label: 'Unsafe information' },
  { value: 'SPAM', label: 'Spam or manipulation' },
  { value: 'POLICY', label: 'Policy concern' },
];
const messageFor = (error: unknown) =>
  error instanceof Error ? error.message : 'Moderator service unavailable.';

export function ModeratorWorkspace({ clientId }: { clientId: string }) {
  const [session, setSession] = useState<AdminSession | null>(null);
  const [view, setView] = useState<'checking' | 'sign-in' | 'ready' | 'unavailable'>('checking');
  const [items, setItems] = useState<ReviewItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [pending, setPending] = useState<PendingDecision | null>(null);
  const [selected, setSelected] = useState<{ ref: string; action: ReviewAction } | null>(null);
  const [reason, setReason] = useState<ReviewReason>('INACCURATE');
  const [confirmed, setConfirmed] = useState(false);
  const account = useRef<string | null>(null);

  const load = useCallback(async (nextCursor?: string) => {
    setLoading(true);
    setMessage('');
    try {
      const page = await reviewQueue(nextCursor);
      if (
        !page ||
        !Array.isArray(page.items) ||
        (page.nextCursor !== null && typeof page.nextCursor !== 'string')
      )
        throw new AdminApiError(503);
      setItems((current) => (nextCursor ? [...current, ...page.items] : page.items));
      setCursor(page.nextCursor);
    } catch (failure) {
      if (failure instanceof AdminApiError && failure.status === 401) {
        setView('sign-in');
        setSession(null);
        setItems([]);
      }
      setMessage(messageFor(failure));
    } finally {
      setLoading(false);
    }
  }, []);

  const openSession = useCallback(async () => {
    setView('checking');
    try {
      const current = await adminSession();
      if (!current?.accountId || !current.expiresAt) throw new AdminApiError(503);
      if (account.current && account.current !== current.accountId) {
        clearPendingDecision();
        setItems([]);
      }
      account.current = current.accountId;
      setSession(current);
      setPending(restorePendingDecision(current.accountId));
      setView('ready');
      await load();
    } catch (failure) {
      if (failure instanceof AdminApiError && failure.status === 401) setView('sign-in');
      else {
        setView('unavailable');
        setMessage(messageFor(failure));
      }
    }
  }, [load]);
  useEffect(() => {
    void openSession();
  }, [openSession]);

  async function act(decision: PendingDecision) {
    try {
      savePendingDecision(decision);
    } catch {
      setMessage(
        'This browser could not save the decision identity. Free session storage, then retry. No decision was sent.',
      );
      return;
    }
    setPending(decision);
    setLoading(true);
    setMessage('');
    try {
      await decide(decision);
      clearPendingDecision();
      setPending(null);
      setSelected(null);
      setConfirmed(false);
      await load();
      setMessage(`${decision.action === 'suppress' ? 'Suppression' : 'Dismissal'} recorded.`);
    } catch (failure) {
      if (failure instanceof AdminApiError && failure.status === 401) {
        setView('sign-in');
        setSession(null);
        setItems([]);
      } else if (failure instanceof AdminApiError && [404, 409].includes(failure.status)) {
        clearPendingDecision();
        setPending(null);
        await load();
      }
      setMessage(
        `${messageFor(failure)}${failure instanceof AdminApiError && [404, 409].includes(failure.status) ? '' : ' Retry the same decision to recover its result.'}`,
      );
    } finally {
      setLoading(false);
    }
  }

  function submit(ref: string, action: ReviewAction) {
    if (!session || !confirmed || pending) return;
    void act({ accountId: session.accountId, ref, action, reason, requestId: crypto.randomUUID() });
  }

  async function signOut() {
    setLoading(true);
    setMessage('');
    try {
      await adminLogout();
      window.google?.accounts.id.disableAutoSelect();
      clearPendingDecision();
      setPending(null);
      setSession(null);
      setItems([]);
      account.current = null;
      setView('sign-in');
    } catch (failure) {
      setMessage(messageFor(failure));
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="workspace">
      <header className="workspace-header">
        <div>
          <span className="logo">routiqo.</span>
          <span className="workspace-label">Safety workspace · staging</span>
        </div>
        {view === 'ready' && (
          <button
            type="button"
            className="text-button"
            onClick={() => void signOut()}
            disabled={loading}
          >
            Sign out
          </button>
        )}
      </header>
      <div className="workspace-body">
        {view === 'checking' && (
          <section className="workspace-state" role="status">
            <h1>Checking moderator access</h1>
            <p>Connecting to the restricted workspace…</p>
          </section>
        )}
        {view === 'unavailable' && (
          <section className="workspace-state">
            <h1>Workspace unavailable</h1>
            <p role="alert">{message}</p>
            <button type="button" onClick={() => void openSession()}>
              Retry connection
            </button>
          </section>
        )}
        {view === 'sign-in' && (
          <section className="workspace-state">
            <h1>Review community traffic reports</h1>
            <p>
              Authorized moderators can review reports and record a decision. No contributor or
              reporter identities appear here.
            </p>
            <AdminGoogleSignIn clientId={clientId} onSignedIn={() => void openSession()} />
            {message && (
              <p role="alert" className="error-text">
                {message}
              </p>
            )}
          </section>
        )}
        {view === 'ready' && (
          <>
            <div className="queue-heading">
              <div>
                <h1>Community traffic reports</h1>
                <p>
                  Review each canonical summary before dismissing a report or stopping its display.
                </p>
              </div>
              <button
                type="button"
                className="secondary-button"
                onClick={() => void load()}
                disabled={loading}
              >
                Refresh queue
              </button>
            </div>
            {pending && (
              <section className="recovery" aria-labelledby="recovery-title">
                <h2 id="recovery-title">Decision outcome uncertain</h2>
                <p>
                  Retry the same {pending.action} request to recover its result. Do not create
                  another decision until this one is resolved.
                </p>
                <button type="button" onClick={() => void act(pending)} disabled={loading}>
                  Retry exact decision
                </button>
              </section>
            )}
            {message && (
              <p className="queue-message" role={message.includes('recorded') ? 'status' : 'alert'}>
                {message}
              </p>
            )}
            {loading && (
              <p role="status" className="subtle">
                Loading moderator reports…
              </p>
            )}
            {!loading && !message && items.length === 0 && (
              <section className="empty-state">
                <h2>{cursor ? 'No reports on this page' : 'No reports to review'}</h2>
                <p>
                  {cursor
                    ? 'Continue to the next page to look for open reports.'
                    : 'New reports will appear after the next refresh. An empty queue does not verify a road condition.'}
                </p>
              </section>
            )}
            <ul className="report-list">
              {items.map((item) => (
                <li key={item.ref} className="report-row">
                  <div className="report-main">
                    <div className="report-top">
                      <h2>
                        {item.evidenceStatus === 'AVAILABLE'
                          ? item.areaLabel || 'Community traffic summary'
                          : 'Evidence unavailable'}
                      </h2>
                      <span
                        className={
                          item.evidenceStatus === 'AVAILABLE' ? 'status-pill' : 'status-pill muted'
                        }
                      >
                        {item.evidenceStatus === 'AVAILABLE' ? 'Investigable' : 'Unavailable'}
                      </span>
                    </div>
                    {item.evidenceStatus === 'AVAILABLE' ? (
                      <p className="evidence">
                        {item.trafficValue
                          ?.replace(/^TRAFFIC_/, '')
                          .replaceAll('_', ' ')
                          .toLowerCase() || 'Traffic value unavailable'}{' '}
                        · {item.observationPeriod || 'Observation period unavailable'}
                      </p>
                    ) : (
                      <p className="evidence">
                        The projection has expired or been removed. Its road and value details are
                        no longer retained.
                      </p>
                    )}
                    <p className="report-counts">
                      Reports: {item.reasonCounts.INACCURATE} inaccurate ·{' '}
                      {item.reasonCounts.UNSAFE} unsafe · {item.reasonCounts.SPAM} spam
                    </p>
                  </div>
                  {item.evidenceStatus === 'AVAILABLE' && (
                    <div className="report-actions">
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={loading || !!pending}
                        onClick={() => {
                          setSelected({ ref: item.ref, action: 'dismiss' });
                          setConfirmed(false);
                        }}
                      >
                        Dismiss
                      </button>
                      <button
                        type="button"
                        className="danger-button"
                        disabled={loading || !!pending}
                        onClick={() => {
                          setSelected({ ref: item.ref, action: 'suppress' });
                          setConfirmed(false);
                        }}
                      >
                        Suppress summary
                      </button>
                    </div>
                  )}
                  {selected?.ref === item.ref && (
                    <div className="decision-form">
                      <h3>
                        {selected.action === 'suppress'
                          ? 'Confirm suppression'
                          : 'Confirm dismissal'}
                      </h3>
                      <p>
                        {selected.action === 'suppress'
                          ? 'This stops serving the summary. It does not recall copies already viewed.'
                          : 'This closes the current report group. A new report can reopen review.'}
                      </p>
                      <label htmlFor={`reason-${item.ref}`}>Reason</label>
                      <select
                        id={`reason-${item.ref}`}
                        value={reason}
                        onChange={(event) => setReason(event.target.value as ReviewReason)}
                      >
                        {reasons.map((option) => (
                          <option key={option.value} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                      <label className="confirm-line">
                        <input
                          type="checkbox"
                          checked={confirmed}
                          onChange={(event) => setConfirmed(event.target.checked)}
                        />
                        <span>
                          I reviewed the available summary and want to record this decision.
                        </span>
                      </label>
                      <div className="decision-actions">
                        <button
                          type="button"
                          className={selected.action === 'suppress' ? 'danger-button' : ''}
                          disabled={!confirmed || loading || !!pending}
                          onClick={() => submit(item.ref, selected.action)}
                        >
                          {selected.action === 'suppress'
                            ? 'Confirm suppression'
                            : 'Confirm dismissal'}
                        </button>
                        <button
                          type="button"
                          className="text-button"
                          onClick={() => setSelected(null)}
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  )}
                </li>
              ))}
            </ul>
            {cursor && (
              <div className="pagination">
                <button
                  type="button"
                  className="secondary-button"
                  disabled={loading}
                  onClick={() => void load(cursor)}
                >
                  Load more reports
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </main>
  );
}
