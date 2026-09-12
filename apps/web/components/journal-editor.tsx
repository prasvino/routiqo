'use client';

import { useEffect, useRef, useState } from 'react';
import type { TripJournal, TripJournalWrite } from '@routiqo/shared';
import {
  BrowserJournalError,
  readBrowserTripJournal,
  saveBrowserTripJournal,
} from '../lib/browser-journals';
import {
  acknowledgeBrowserJournalDraft,
  cacheBrowserTripJournal,
  discardBrowserJournalDraft,
  readBrowserJournal,
  saveBrowserJournalDraft,
  type BrowserJournalDraft,
} from '../lib/journal-storage';
import { Modal } from './modal';

interface JournalEditorProps {
  account: string;
  journeyId: string;
  onClose: () => void;
}

type SaveDestination = 'draft' | 'account';
type BusyState = SaveDestination | 'discard' | null;
const historyGuardKey = '__routiqoJournalGuard';
interface JournalHistoryGuard {
  marker: string;
  previousState: unknown;
}

function errorMessage(failure: unknown, fallback: string) {
  return failure instanceof Error && failure.message ? failure.message : fallback;
}

function JournalEditorSession({ account, journeyId, onClose }: JournalEditorProps) {
  const [journal, setJournal] = useState<TripJournal | null>(null);
  const [draft, setDraft] = useState<BrowserJournalDraft | null>(null);
  const [title, setTitle] = useState('');
  const [notes, setNotes] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<BusyState>(null);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [latestServer, setLatestServer] = useState<TripJournal | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [confirmUseAccount, setConfirmUseAccount] = useState(false);
  const [accountFresh, setAccountFresh] = useState(false);
  const mounted = useRef(true);
  const operation = useRef<AbortController | null>(null);
  const pendingHistoryExit = useRef(false);
  const historyGuard = useRef<JournalHistoryGuard | null>(null);
  const historyRestoring = useRef(false);
  const dirtyRef = useRef(false);
  const closeRef = useRef(onClose);

  useEffect(() => {
    mounted.current = true;
    const controller = new AbortController();
    let active = true;

    void (async () => {
      let localJournal: TripJournal | null = null;
      let localDraft: BrowserJournalDraft | null = null;
      let localFailure = '';

      try {
        const local = await readBrowserJournal(account, journeyId);
        localJournal = local.journal;
        localDraft = local.draft;
      } catch (failure) {
        localFailure = errorMessage(failure, 'Saved journal data is unavailable on this device.');
      }

      let selectedJournal = localJournal;
      let remoteFailure = '';
      if (navigator.onLine) {
        try {
          const remote = await readBrowserTripJournal(account, journeyId, controller.signal);
          if (!active) return;
          setAccountFresh(true);
          try {
            selectedJournal = await cacheBrowserTripJournal(account, remote);
          } catch (failure) {
            selectedJournal = localJournal ?? remote;
            localFailure = errorMessage(
              failure,
              'This journal opened, but device storage is unavailable.',
            );
          }
        } catch (failure) {
          if (controller.signal.aborted) return;
          remoteFailure = errorMessage(
            failure,
            'The journal could not be loaded from your account.',
          );
        }
      }

      if (!active) return;
      if (!selectedJournal) {
        setLoadError(
          navigator.onLine
            ? remoteFailure || localFailure || 'This trip journal is unavailable.'
            : localFailure ||
                'This journal is not saved on this device. Connect to the internet to open it.',
        );
        setLoading(false);
        return;
      }

      setJournal(selectedJournal);
      setDraft(localDraft);
      setTitle(localDraft?.title ?? selectedJournal.annotation.title);
      setNotes(localDraft?.notes ?? selectedJournal.annotation.notes);
      if (remoteFailure)
        setNotice('Showing the version saved on this device. Account refresh is unavailable.');
      else if (localFailure)
        setNotice('Device storage is unavailable. Your text must be saved locally before sending.');
      setLoading(false);
    })();

    return () => {
      active = false;
      mounted.current = false;
      controller.abort();
      operation.current?.abort();
    };
  }, [account, journeyId]);

  const savedTitle = draft?.title ?? journal?.annotation.title ?? '';
  const savedNotes = draft?.notes ?? journal?.annotation.notes ?? '';
  const dirty = journal !== null && (title !== savedTitle || notes !== savedNotes);

  useEffect(() => {
    dirtyRef.current = dirty;
    closeRef.current = onClose;
  }, [dirty, onClose]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  useEffect(() => {
    const guardHistory = (event: PopStateEvent) => {
      const guard = historyGuard.current;
      if (!guard) return;
      if (historyRestoring.current) {
        historyRestoring.current = false;
        event.stopImmediatePropagation();
        return;
      }
      const current = event.state as Record<string, unknown> | null;
      if (current?.[historyGuardKey] === guard.marker) return;
      if (!dirtyRef.current) {
        historyGuard.current = null;
        closeRef.current();
        return;
      }
      event.stopImmediatePropagation();
      pendingHistoryExit.current = true;
      setConfirmDiscard(true);
      historyRestoring.current = true;
      window.history.forward();
    };
    window.addEventListener('popstate', guardHistory, { capture: true });
    return () => {
      window.removeEventListener('popstate', guardHistory, { capture: true });
      const guard = historyGuard.current;
      const current = window.history.state as Record<string, unknown> | null;
      if (guard && current?.[historyGuardKey] === guard.marker)
        window.history.replaceState(guard.previousState, '', window.location.href);
      historyGuard.current = null;
    };
  }, []);

  useEffect(() => {
    if (!dirty || historyGuard.current) return;
    const marker = crypto.randomUUID();
    const previousState: unknown = window.history.state;
    const state =
      typeof previousState === 'object' && previousState !== null
        ? { ...previousState, [historyGuardKey]: marker }
        : { [historyGuardKey]: marker };
    historyGuard.current = { marker, previousState };
    window.history.pushState(state, '', window.location.href);
  }, [dirty]);

  const closeWithHistory = (continueHistory: boolean) => {
    const guard = historyGuard.current;
    historyGuard.current = null;
    onClose();
    if (!guard) return;
    const current = window.history.state as Record<string, unknown> | null;
    if (current?.[historyGuardKey] !== guard.marker) return;
    window.setTimeout(() => (continueHistory ? window.history.go(-2) : window.history.back()), 0);
  };

  const requestClose = () => {
    if (busy) return;
    if (dirty) {
      setConfirmDiscard(true);
      return;
    }
    closeWithHistory(false);
  };

  const persistCurrent = async (): Promise<BrowserJournalDraft> => {
    if (!journal) throw new Error('This journal is not ready.');
    if (draft && draft.title === title && draft.notes === notes)
      return saveBrowserJournalDraft(account, draft, draft.mutationId);

    const edit: BrowserJournalDraft = {
      journeyId,
      title,
      notes,
      expectedVersion: draft?.expectedVersion ?? journal.annotation.version,
      mutationId: crypto.randomUUID(),
    };
    return saveBrowserJournalDraft(account, edit, draft?.mutationId ?? null);
  };

  const save = async (destination: SaveDestination) => {
    if (busy || loading || !journal) return;
    setBusy(destination);
    setError('');
    setNotice('');
    const controller = new AbortController();
    operation.current?.abort();
    operation.current = controller;

    let savedDraft: BrowserJournalDraft;
    try {
      savedDraft = await persistCurrent();
    } catch (failure) {
      if (!mounted.current || controller.signal.aborted) return;
      setError(
        errorMessage(
          failure,
          'The draft could not be saved on this device. Nothing was sent to your account.',
        ),
      );
      setBusy(null);
      return;
    }

    if (!mounted.current || controller.signal.aborted) return;
    setDraft(savedDraft);
    if (destination === 'draft') {
      setNotice('Draft saved on this device.');
      setBusy(null);
      return;
    }
    if (!navigator.onLine) {
      setNotice('Draft saved on this device. Connect to save it to your account.');
      setBusy(null);
      return;
    }

    try {
      const outbound: TripJournalWrite = {
        title: savedDraft.title,
        notes: savedDraft.notes,
        expectedVersion: savedDraft.expectedVersion,
        mutationId: savedDraft.mutationId,
      };
      const response = await saveBrowserTripJournal(
        account,
        journeyId,
        outbound,
        controller.signal,
      );
      const acknowledged = await acknowledgeBrowserJournalDraft(
        account,
        journeyId,
        savedDraft.mutationId,
        response,
      );
      if (!mounted.current || controller.signal.aborted) return;
      if (!acknowledged) {
        setError(
          'Saved to your account, but a newer device draft was found. Reopen the journal before sending again.',
        );
      } else {
        setJournal(response);
        setDraft(null);
        setLatestServer(null);
        setAccountFresh(true);
        setNotice('Saved to your account.');
      }
    } catch (failure) {
      if (!mounted.current || controller.signal.aborted) return;
      if (failure instanceof BrowserJournalError && failure.status === 409) {
        setError(
          'This journal changed in your account. Your device draft is retained and was not overwritten.',
        );
        try {
          const latest = await readBrowserTripJournal(account, journeyId, controller.signal);
          await cacheBrowserTripJournal(account, latest).catch(() => undefined);
          if (mounted.current && !controller.signal.aborted) setLatestServer(latest);
        } catch {
          // The retained local draft remains the source of truth when review cannot be loaded.
        }
      } else {
        setError(
          errorMessage(
            failure,
            'Your draft is saved on this device but could not be saved to your account.',
          ),
        );
      }
    } finally {
      if (mounted.current && !controller.signal.aborted) setBusy(null);
    }
  };

  const applyAccountVersion = async () => {
    if (busy || !draft || !latestServer) return;
    setBusy('discard');
    setError('');
    setNotice('');
    const controller = new AbortController();
    operation.current?.abort();
    operation.current = controller;
    try {
      const confirmed = await discardBrowserJournalDraft(
        account,
        journeyId,
        draft.mutationId,
        latestServer,
      );
      if (!mounted.current || controller.signal.aborted) return;
      setJournal(confirmed);
      setDraft(null);
      setTitle(confirmed.annotation.title);
      setNotes(confirmed.annotation.notes);
      setLatestServer(null);
      setConfirmUseAccount(false);
      setAccountFresh(true);
      setNotice('Using the account version. The device draft was removed.');
    } catch (failure) {
      if (!mounted.current || controller.signal.aborted) return;
      setConfirmUseAccount(false);
      setError(
        errorMessage(
          failure,
          'The device draft could not be removed. Your text is still available here.',
        ),
      );
    } finally {
      if (mounted.current && !controller.signal.aborted) setBusy(null);
    }
  };

  const context = journal ? (
    <div className="journal-context" aria-label="Trip dates">
      <span>
        Started{' '}
        <time dateTime={journal.journey.startedAt}>
          {new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(
            new Date(journal.journey.startedAt),
          )}
        </time>
      </span>
      <span>
        Completed{' '}
        <time dateTime={journal.journey.completedAt ?? undefined}>
          {new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(
            new Date(journal.journey.completedAt!),
          )}
        </time>
      </span>
    </div>
  ) : null;

  return (
    <Modal title="Trip journal" onClose={requestClose} wide>
      {loading ? (
        <div className="journal-loading" role="status">
          Loading your private journal…
        </div>
      ) : loadError ? (
        <div className="journal-unavailable">
          <p role="alert">{loadError}</p>
          <button className="button secondary" type="button" onClick={onClose}>
            Close
          </button>
        </div>
      ) : confirmDiscard ? (
        <div className="journal-discard">
          <p className="eyebrow">UNSAVED CHANGES</p>
          <h3>Discard changes?</h3>
          <p>Your latest edits have not been saved on this device.</p>
          <div className="detail-actions">
            <button
              className="button secondary"
              type="button"
              onClick={() => {
                pendingHistoryExit.current = false;
                setConfirmDiscard(false);
              }}
            >
              Continue writing
            </button>
            <button
              className="button danger"
              type="button"
              onClick={() => {
                const leaveHistory = pendingHistoryExit.current;
                pendingHistoryExit.current = false;
                closeWithHistory(leaveHistory);
              }}
            >
              Discard changes
            </button>
          </div>
        </div>
      ) : confirmUseAccount ? (
        <div className="journal-discard">
          <p className="eyebrow">DEVICE DRAFT</p>
          <h3>Use the account version?</h3>
          <p>
            This removes the device draft and any current unsaved edits. It does not change the
            journal saved to your account.
          </p>
          <div className="detail-actions">
            <button
              className="button secondary"
              type="button"
              disabled={busy !== null}
              onClick={() => setConfirmUseAccount(false)}
            >
              Keep device draft
            </button>
            <button
              className="button danger"
              type="button"
              disabled={busy !== null}
              onClick={() => void applyAccountVersion()}
            >
              {busy === 'discard'
                ? 'Removing device draft…'
                : 'Remove draft and use account version'}
            </button>
          </div>
        </div>
      ) : (
        <div className="journal-editor">
          {context}
          <p className="modal-intro">A private title and notes for this completed trip.</p>
          <form className="plan-form" onSubmit={(event) => event.preventDefault()}>
            <label>
              Title <span className="optional">Optional · {title.length}/120</span>
              <input
                aria-label="Title"
                value={title}
                maxLength={120}
                disabled={busy !== null}
                onChange={(event) => {
                  setTitle(event.target.value);
                  setError('');
                  setNotice('');
                }}
              />
            </label>
            <label>
              Notes <span className="optional">Optional · {notes.length}/4000</span>
              <textarea
                aria-label="Notes"
                value={notes}
                maxLength={4000}
                rows={9}
                disabled={busy !== null}
                onChange={(event) => {
                  setNotes(event.target.value);
                  setError('');
                  setNotice('');
                }}
              />
            </label>
          </form>
          <p className="journal-save-state" role="status" aria-live="polite">
            {busy === 'draft'
              ? 'Saving draft…'
              : busy === 'account'
                ? 'Saving to your account…'
                : dirty
                  ? 'Unsaved changes'
                  : draft
                    ? 'Draft saved on this device'
                    : accountFresh
                      ? 'Up to date with your account'
                      : 'Saved account version on this device'}
          </p>
          {notice && <p className="journal-notice">{notice}</p>}
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          {latestServer && (
            <section className="journal-server-review" aria-label="Latest account version">
              <p className="eyebrow">LATEST ACCOUNT VERSION</p>
              <h3>{latestServer.annotation.title || 'No personal title'}</h3>
              <p className="journal-server-notes">
                {latestServer.annotation.notes || 'No personal notes.'}
              </p>
              <p className="fine-print">
                Account version {latestServer.annotation.version}. Review only.
              </p>
              {draft && (
                <button
                  className="button secondary"
                  type="button"
                  disabled={busy !== null}
                  onClick={() => {
                    setError('');
                    setNotice('');
                    setConfirmUseAccount(true);
                  }}
                >
                  Use account version
                </button>
              )}
            </section>
          )}
          <div className="detail-actions journal-actions">
            <button
              className="button secondary"
              type="button"
              disabled={busy !== null || !dirty}
              onClick={() => void save('draft')}
            >
              Save draft
            </button>
            <button
              className="button primary"
              type="button"
              disabled={busy !== null || (!dirty && draft === null)}
              onClick={() => void save('account')}
            >
              Save to account
            </button>
          </div>
        </div>
      )}
    </Modal>
  );
}

export function JournalEditor(props: JournalEditorProps) {
  return <JournalEditorSession key={`${props.account}:${props.journeyId}`} {...props} />;
}
