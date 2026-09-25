'use client';
import { useEffect, useRef, useState } from 'react';
import { CloudUpload, CloudDownload, RefreshCw } from 'lucide-react';
import {
  createAccountPlanningWrite,
  mergePlanningBackup,
  samePlanningContent,
  type AccountPlanningCopy,
  type AccountPlanningWrite,
  type PlanningState,
} from '@routiqo/shared';
import {
  BrowserPlanningError,
  deleteBrowserAccountPlanning,
  readBrowserAccountPlanning,
  saveBrowserAccountPlanning,
} from '../lib/browser-planning';
import { usePlanning } from './planning-provider';
import { Modal } from './modal';

type Action = 'check' | 'save' | 'add' | 'remove';
type Pending = { kind: 'save'; write: AccountPlanningWrite } | { kind: 'remove'; version: number };

const count = (value: number, one: string, many: string) => `${value} ${value === 1 ? one : many}`;
const describe = (state: PlanningState) =>
  `${count(state.plans.length, 'plan', 'plans')} · ${count(state.saved.length, 'saved place', 'saved places')}`;
function savedAt(value: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  } catch {
    return value;
  }
}
function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    update();
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    return () => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
    };
  }, []);
  return online;
}

/**
 * Explicit, owner-only account copy of this device's plans and saved places (ADR 0062).
 * Mount with `key={accountId}` so an account change discards every result and pending retry.
 */
export function AccountPlanningCopyPanel({ accountId }: { accountId: string }) {
  const planning = usePlanning();
  const online = useOnline();
  const [copy, setCopy] = useState<AccountPlanningCopy | null>(null);
  const [busy, setBusy] = useState<Action | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [confirm, setConfirm] = useState<'replace' | 'remove' | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const controller = useRef<AbortController | null>(null);
  const revision = useRef(0);

  useEffect(
    () => () => {
      revision.current++;
      controller.current?.abort();
    },
    [],
  );

  async function run<T>(
    action: Action,
    work: (signal: AbortSignal) => Promise<T>,
    done: (result: T) => void,
  ) {
    controller.current?.abort();
    const current = ++revision.current;
    const next = new AbortController();
    controller.current = next;
    setBusy(action);
    setError('');
    setNotice('');
    try {
      const result = await work(next.signal);
      if (current === revision.current) done(result);
    } catch (failure) {
      if (current !== revision.current || next.signal.aborted) return;
      if (failure instanceof BrowserPlanningError) {
        setError(failure.message);
        // A stale or unauthorized view must be checked again before any further change.
        if (failure.kind === 'conflict' || failure.kind === 'session') setCopy(null);
        if (failure.kind !== 'uncertain') setPending(null);
      } else {
        setError(failure instanceof Error ? failure.message : 'Something went wrong.');
      }
    } finally {
      if (current === revision.current) setBusy(null);
    }
  }

  function check() {
    setPending(null);
    void run('check', (signal) => readBrowserAccountPlanning(accountId, signal), setCopy);
  }

  function save(write: AccountPlanningWrite) {
    setConfirm(null);
    setPending({ kind: 'save', write });
    void run(
      'save',
      (signal) => saveBrowserAccountPlanning(accountId, write, signal),
      (saved) => {
        setCopy(saved);
        setPending(null);
        setNotice(`Saved ${describe(saved.state)} to your account.`);
      },
    );
  }

  function startSave() {
    if (!copy) return;
    let write: AccountPlanningWrite;
    try {
      write = createAccountPlanningWrite(planning.state, copy.version, crypto.randomUUID());
    } catch (failure) {
      setError(
        failure instanceof Error && failure.message.startsWith('The plan')
          ? failure.message
          : 'Plans on this device need attention before they can be kept on your account.',
      );
      return;
    }
    if (copy.present) {
      setPending({ kind: 'save', write });
      setConfirm('replace');
    } else save(write);
  }

  function remove(version: number) {
    setConfirm(null);
    setPending({ kind: 'remove', version });
    void run(
      'remove',
      (signal) => deleteBrowserAccountPlanning(accountId, version, signal),
      (removed) => {
        setCopy(removed);
        setPending(null);
        setNotice('Your account copy was removed. Plans on this device are unchanged.');
      },
    );
  }

  function addToDevice() {
    if (!copy || !copy.present) return;
    setError('');
    try {
      // Check the merge first so a rejected merge does not mark local storage as failed.
      mergePlanningBackup(planning.state, copy.state);
    } catch (failure) {
      setNotice('');
      setError(failure instanceof Error ? failure.message : 'Plans could not be added.');
      return;
    }
    try {
      const summary = planning.restoreBackup(copy.state);
      const kept = summary.keptPlans
        ? ` ${count(summary.keptPlans, 'plan was', 'plans were')} already on this device.`
        : '';
      setNotice(
        `Added ${count(summary.addedPlans, 'plan', 'plans')} and ${count(summary.addedPlaces, 'saved place', 'saved places')} to this device.${kept}`,
      );
    } catch (failure) {
      setNotice('');
      setError(failure instanceof Error ? failure.message : 'Plans could not be added.');
    }
  }

  // A pending exact request stays offered until it succeeds, fails definitively, or a new check discards it.
  const retry = pending && !busy && !confirm;
  const matches = copy?.present && samePlanningContent(copy.state, planning.state);
  const disabled = Boolean(busy) || !online || !planning.ready;
  const nothingToSave =
    planning.state.plans.length === 0 &&
    planning.state.saved.length === 0 &&
    copy?.present === false;
  return (
    <section className="settings-section planning-copy" aria-labelledby="planning-copy-heading">
      <h2 id="planning-copy-heading">Plans on your account</h2>
      <p className="planning-copy-intro">
        Keep a private copy of this device’s plans and saved places on your Routiqo account, then
        add them on another device. Nothing is uploaded unless you choose to save. Plan text can
        include places such as home or work; only you can see your copy.
      </p>
      <dl className="planning-copy-status">
        <div>
          <dt>This device</dt>
          <dd>{planning.ready ? describe(planning.state) : 'Loading…'}</dd>
        </div>
        <div>
          <dt>Your account</dt>
          <dd aria-live="polite">
            {busy === 'check'
              ? 'Checking…'
              : !copy
                ? 'Not checked yet'
                : !copy.present
                  ? 'No plans saved on your account yet'
                  : `${describe(copy.state)} · saved ${savedAt(copy.updatedAt!)}`}
          </dd>
        </div>
      </dl>
      {matches && <p className="planning-copy-match">This device matches your account copy.</p>}
      {nothingToSave && (
        <p className="planning-copy-match">Save a plan or a place on this device to keep a copy.</p>
      )}
      {!online && (
        <p className="planning-copy-offline" role="status">
          You’re offline. Your account copy needs a connection; plans on this device still work.
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="backup-notice" role="status">
          {notice}
        </p>
      )}
      <div className="planning-copy-actions">
        {retry ? (
          <button
            className="button primary"
            disabled={!online}
            onClick={() =>
              pending.kind === 'save' ? save(pending.write) : remove(pending.version)
            }
          >
            <RefreshCw size={16} aria-hidden="true" />
            {pending.kind === 'save' ? 'Retry save' : 'Retry removal'}
          </button>
        ) : null}
        <button className="button secondary" disabled={disabled} onClick={check}>
          <RefreshCw size={16} aria-hidden="true" />
          {busy === 'check' ? 'Checking…' : copy ? 'Check again' : 'Check account copy'}
        </button>
        {copy && (
          <button
            className="button primary"
            disabled={disabled || Boolean(planning.error) || Boolean(retry) || nothingToSave}
            onClick={startSave}
          >
            <CloudUpload size={16} aria-hidden="true" />
            {busy === 'save' ? 'Saving…' : 'Save this device’s plans'}
          </button>
        )}
        {copy?.present && (
          <button
            className="button secondary"
            disabled={Boolean(busy) || !planning.ready || Boolean(planning.error) || Boolean(retry)}
            onClick={addToDevice}
          >
            <CloudDownload size={16} aria-hidden="true" />
            Add account plans to this device
          </button>
        )}
      </div>
      {copy?.present && (
        <button
          className="account-delete-link"
          disabled={disabled || Boolean(retry)}
          onClick={() => setConfirm('remove')}
        >
          {busy === 'remove' ? 'Removing account copy…' : 'Remove account copy'}
        </button>
      )}
      {confirm === 'replace' && copy && pending?.kind === 'save' && (
        <Modal
          title="Replace your account copy?"
          onClose={() => {
            setConfirm(null);
            setPending(null);
          }}
        >
          <p className="modal-intro">
            Your account copy has {describe(copy.state)}. Saving replaces it with this device’s{' '}
            {describe({ version: 1, plans: pending.write.plans, saved: pending.write.saved })}.
            Plans only in the account copy will be removed from it.
          </p>
          <p className="local-strip">
            To keep both, choose “Add account plans to this device” first, then save.
          </p>
          <div className="detail-actions">
            <button
              className="button secondary"
              onClick={() => {
                setConfirm(null);
                setPending(null);
              }}
            >
              Keep account copy
            </button>
            <button className="button primary" onClick={() => save(pending.write)}>
              Replace account copy
            </button>
          </div>
        </Modal>
      )}
      {confirm === 'remove' && copy?.present && (
        <Modal title="Remove your account copy?" onClose={() => setConfirm(null)}>
          <p className="modal-intro">
            This removes {describe(copy.state)} from your account. Plans and saved places on this
            device are not changed.
          </p>
          <div className="detail-actions">
            <button className="button secondary" onClick={() => setConfirm(null)}>
              Keep account copy
            </button>
            <button className="button danger" onClick={() => remove(copy.version)}>
              Remove account copy
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}
