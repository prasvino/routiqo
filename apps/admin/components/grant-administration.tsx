'use client';

import { useEffect, useState } from 'react';
import {
  AdminApiError,
  exactTargetId,
  grantCapability,
  mutateGrant,
  readTargetGrants,
  type GrantAction,
  type GrantPermission,
  type GrantReason,
  type PendingGrantMutation,
  type TargetGrants,
} from '../lib/admin-client';
import { formatGrantExpiry } from '../lib/grant-display';

const permissions: { value: GrantPermission; label: string }[] = [
  { value: 'traffic_review', label: 'Review traffic reports' },
  { value: 'traffic_suppress', label: 'Suppress traffic summaries' },
];
const reasons: { value: GrantReason; label: string }[] = [
  { value: 'OPERATOR_TRIAL', label: 'Operator trial' },
  { value: 'COVERAGE_CHANGE', label: 'Coverage change' },
  { value: 'SECURITY_RESPONSE', label: 'Security response' },
  { value: 'ERROR_CORRECTION', label: 'Error correction' },
];
const durations = [15, 30, 60, 120, 240];

function grantError(error: unknown): string {
  if (!(error instanceof AdminApiError))
    return 'Grant service unavailable. Check your connection and retry.';
  if (error.status === 401) return 'Your admin session ended. Sign in again.';
  if (error.status === 403) return 'Grant administrator access is unavailable.';
  if (error.status === 404)
    return 'Account unavailable. Check the exact ID with the controlled roster.';
  if (error.status === 409)
    return 'This grant changed or the request identity conflicts. Refresh the account grants.';
  if (error.status === 429) return 'Too many requests. Wait a moment and retry.';
  return 'The grant service or network is unavailable. The write outcome may be uncertain.';
}

export function GrantAdministration({ accountId }: { accountId: string }) {
  const [access, setAccess] = useState<'checking' | 'allowed' | 'denied' | 'expired' | 'error'>(
    'checking',
  );
  const [input, setInput] = useState('');
  const [target, setTarget] = useState<TargetGrants | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [messageKind, setMessageKind] = useState<'status' | 'alert'>('status');
  const [action, setAction] = useState<GrantAction>('issue');
  const [permission, setPermission] = useState<GrantPermission>('traffic_review');
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [reason, setReason] = useState<GrantReason>('OPERATOR_TRIAL');
  const [confirmed, setConfirmed] = useState(false);
  const [pending, setPending] = useState<PendingGrantMutation | null>(null);

  useEffect(() => {
    let active = true;
    grantCapability()
      .then((allowed) => {
        if (active) setAccess(allowed ? 'allowed' : 'denied');
      })
      .catch((error: unknown) => {
        if (!active) return;
        if (error instanceof AdminApiError && error.status === 401) setAccess('expired');
        else if (error instanceof AdminApiError && error.status === 403) setAccess('denied');
        else setAccess('error');
      });
    return () => {
      active = false;
    };
  }, [accountId]);

  async function lookup(id = exactTargetId(input)) {
    if (!id || pending) {
      if (!id) {
        setMessageKind('alert');
        setMessage('Enter an exact account UUID from the controlled roster.');
      }
      return;
    }
    setLoading(true);
    setMessage('');
    setTarget(null);
    setConfirmed(false);
    try {
      setTarget(await readTargetGrants(id));
      setInput(id);
    } catch (error) {
      setMessageKind('alert');
      setMessage(grantError(error));
      if (error instanceof AdminApiError && error.status === 401) setAccess('expired');
      if (error instanceof AdminApiError && error.status === 403) setAccess('denied');
    } finally {
      setLoading(false);
    }
  }

  async function submit(request: PendingGrantMutation) {
    setPending(request);
    setLoading(true);
    setMessage('');
    try {
      const result = await mutateGrant(request);
      setPending(null);
      setConfirmed(false);
      setMessageKind('status');
      setMessage(
        result.expiresAt
          ? `${permissions.find((item) => item.value === result.permission)?.label} granted until ${formatGrantExpiry(result.expiresAt)}${result.replayed ? ' (exact retry).' : '.'}`
          : `${permissions.find((item) => item.value === result.permission)?.label} revoked${result.replayed ? ' (exact retry).' : '.'}`,
      );
      try {
        setTarget(await readTargetGrants(request.targetId));
      } catch {
        setTarget(null);
      }
    } catch (error) {
      setMessageKind('alert');
      if (error instanceof AdminApiError && [401, 403, 404, 409].includes(error.status)) {
        setPending(null);
        if (error.status === 401) setAccess('expired');
        if (error.status === 403) setAccess('denied');
        if (error.status === 404 || error.status === 409) setTarget(null);
        setMessage(grantError(error));
      } else {
        setMessage(
          `${grantError(error)} Retry this exact request while this page remains open. Do not issue a different write.`,
        );
      }
    } finally {
      setLoading(false);
    }
  }

  if (access === 'checking' || access === 'denied') return null;
  if (access === 'expired')
    return (
      <p className="grant-access" role="alert">
        Grant administration session expired. Sign in again.
      </p>
    );
  if (access === 'error')
    return (
      <section className="grant-access" aria-label="Grant administration unavailable">
        <p role="alert">
          Grant administrator access could not be checked. Check your connection and reload the
          workspace.
        </p>
      </section>
    );

  const livePermission = target?.grants.find((grant) => grant.permission === permission);
  const canSubmit =
    !!target &&
    confirmed &&
    !loading &&
    !pending &&
    (action === 'revoke' ? !!livePermission : !livePermission);
  return (
    <section className="grant-panel" aria-labelledby="grant-title">
      <div className="grant-heading">
        <div>
          <h2 id="grant-title">Operator grants</h2>
          <p>Use an exact account ID from the controlled roster. Access expires automatically.</p>
        </div>
      </div>
      <form
        className="grant-lookup"
        onSubmit={(event) => {
          event.preventDefault();
          void lookup();
        }}
      >
        <label htmlFor="grant-target">Target account UUID</label>
        <div className="grant-lookup-line">
          <input
            id="grant-target"
            type="text"
            value={input}
            onChange={(event) => {
              setInput(event.target.value);
              setTarget(null);
              setConfirmed(false);
            }}
            placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            disabled={loading || !!pending}
            aria-describedby="grant-target-hint"
          />
          <button type="submit" className="secondary-button" disabled={loading || !!pending}>
            Review grants
          </button>
        </div>
        <p id="grant-target-hint" className="subtle">
          No account search or identity details are available here.
        </p>
      </form>
      {loading && (
        <p role="status" className="subtle">
          Checking grant state…
        </p>
      )}
      {message && (
        <p role={messageKind} className={messageKind === 'alert' ? 'error-text' : 'grant-success'}>
          {message}
        </p>
      )}
      {pending && (
        <div className="recovery" role="alert">
          <h3>Write outcome uncertain</h3>
          <p>
            Keep this page open. Retry the same {pending.action} request with its original request
            ID.
          </p>
          <button type="button" disabled={loading} onClick={() => void submit(pending)}>
            Retry exact request
          </button>
        </div>
      )}
      {target && (
        <div className="grant-content">
          <div className="grant-current">
            <h3>Current permissions</h3>
            {target.grants.length === 0 ? (
              <p>No active V3 permissions for this account.</p>
            ) : (
              <ul>
                {target.grants.map((grant) => (
                  <li key={grant.permission}>
                    <strong>
                      {permissions.find((item) => item.value === grant.permission)?.label}
                    </strong>
                    <span>Expires {formatGrantExpiry(grant.expiresAt)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="grant-form">
            <h3>Change permission</h3>
            <div className="grant-fields">
              <label htmlFor="grant-action">Action</label>
              <select
                id="grant-action"
                value={action}
                onChange={(event) => {
                  setAction(event.target.value as GrantAction);
                  setConfirmed(false);
                }}
                disabled={loading || !!pending}
              >
                <option value="issue">Issue</option>
                <option value="revoke">Revoke</option>
              </select>
              <label htmlFor="grant-permission">Permission</label>
              <select
                id="grant-permission"
                value={permission}
                onChange={(event) => {
                  setPermission(event.target.value as GrantPermission);
                  setConfirmed(false);
                }}
                disabled={loading || !!pending}
              >
                {permissions.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
              {action === 'issue' && (
                <>
                  <label htmlFor="grant-duration">Duration</label>
                  <select
                    id="grant-duration"
                    value={durationMinutes}
                    onChange={(event) => {
                      setDurationMinutes(Number(event.target.value));
                      setConfirmed(false);
                    }}
                    disabled={loading || !!pending}
                  >
                    {durations.map((minutes) => (
                      <option key={minutes} value={minutes}>
                        {minutes < 60
                          ? `${minutes} minutes`
                          : `${minutes / 60} ${minutes === 60 ? 'hour' : 'hours'}`}
                      </option>
                    ))}
                  </select>
                </>
              )}
              <label htmlFor="grant-reason">Reason</label>
              <select
                id="grant-reason"
                value={reason}
                onChange={(event) => {
                  setReason(event.target.value as GrantReason);
                  setConfirmed(false);
                }}
                disabled={loading || !!pending}
              >
                {reasons.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </div>
            <p className="grant-rule">
              {action === 'issue' && livePermission
                ? 'This permission is active. Revoke it before issuing another grant.'
                : action === 'revoke' && !livePermission
                  ? 'There is no active grant to revoke for this permission.'
                  : action === 'issue'
                    ? 'The duration starts from server time after the request commits.'
                    : 'Revocation applies at the next permission check.'}
            </p>
            <label className="confirm-line">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(event) => setConfirmed(event.target.checked)}
                disabled={loading || !!pending}
              />
              <span>
                I checked the account ID, permission and reason, and want to {action} this grant.
              </span>
            </label>
            <button
              type="button"
              className={action === 'revoke' ? 'danger-button' : ''}
              disabled={!canSubmit}
              onClick={() => {
                if (!canSubmit || !target) return;
                void submit({
                  accountId,
                  targetId: target.targetId,
                  action,
                  permission,
                  reason,
                  ...(action === 'issue' ? { durationMinutes } : {}),
                  requestId: crypto.randomUUID(),
                });
              }}
            >
              Confirm {action}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
