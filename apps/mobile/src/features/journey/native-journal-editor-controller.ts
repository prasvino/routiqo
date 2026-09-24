import { readTripJournalWrite, type TripJournal, type TripJournalWrite } from '@routiqo/shared';
import { NativeSessionRequired } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import { NativeJournalStorageError, type NativeJournalDraft } from '../../storage/journal-storage';

export type EditorFailure =
  | 'offline'
  | 'session'
  | 'missing'
  | 'conflict'
  | 'storage'
  | 'full'
  | 'unavailable'
  | 'invalid'
  | null;
export interface NativeJournalEditorState {
  open: boolean;
  busy: boolean;
  title: string;
  notes: string;
  journal: TripJournal | null;
  draft: NativeJournalDraft | null;
  reviewed: TripJournal | null;
  failure: EditorFailure;
  dirty: boolean;
  settled: boolean;
}
export interface NativeJournalEditorPorts {
  online(): boolean;
  mutationId(): string;
  cache(journal: TripJournal): Promise<TripJournal>;
  stored(
    journeyId: string,
  ): Promise<{ draft: NativeJournalDraft | null; journal: TripJournal | null }>;
  save(draft: NativeJournalDraft, previousMutationId: string | null): Promise<NativeJournalDraft>;
  write(journeyId: string, input: TripJournalWrite, signal: AbortSignal): Promise<TripJournal>;
  read(journeyId: string, signal: AbortSignal): Promise<TripJournal>;
  acknowledge(journeyId: string, mutationId: string, response: TripJournal): Promise<boolean>;
  discard(journeyId: string, mutationId: string, reviewed: TripJournal): Promise<TripJournal>;
}

export function createNativeJournalEditorController(
  ports: NativeJournalEditorPorts,
  publish: (state: NativeJournalEditorState) => void,
) {
  let state: NativeJournalEditorState = {
    open: false,
    busy: false,
    title: '',
    notes: '',
    journal: null,
    draft: null,
    reviewed: null,
    failure: null,
    dirty: false,
    settled: false,
  };
  let revision = 0;
  let pending: AbortController | null = null;
  let localCritical: string | null = null;
  let disposed = false;
  const update = (patch: Partial<NativeJournalEditorState>) => {
    state = { ...state, ...patch };
    if (!disposed) publish(state);
  };
  const cancel = () => {
    revision++;
    pending?.abort();
    pending = null;
  };
  const current = (request: number) => !disposed && request === revision;
  const classify = (error: unknown): EditorFailure => {
    if (error instanceof NativeSessionRequired) return 'session';
    if (error instanceof NativeHttpStatus) {
      if (error.status === 401 || error.status === 403) return 'session';
      if (error.status === 404) return 'missing';
      if (error.status === 409) return 'conflict';
    }
    if (error instanceof NativeJournalStorageError)
      return error.code === 'full' ? 'full' : 'storage';
    return 'unavailable';
  };
  const journeyId = () => state.journal?.journey.id ?? null;
  const changed = (title: string, notes: string) =>
    title !== (state.draft?.title ?? state.journal?.annotation.title ?? '') ||
    notes !== (state.draft?.notes ?? state.journal?.annotation.notes ?? '');
  async function open(journal: TripJournal) {
    if (disposed) return;
    cancel();
    const request = revision;
    update({
      open: true,
      busy: true,
      title: journal.annotation.title,
      notes: journal.annotation.notes,
      journal,
      draft: null,
      reviewed: null,
      failure: null,
      dirty: false,
      settled: false,
    });
    try {
      const before = await ports.stored(journal.journey.id);
      if (!current(request)) return;
      const cached =
        before.journal && before.journal.annotation.version > journal.annotation.version
          ? before.journal
          : await ports.cache(journal);
      if (!current(request)) return;
      const saved = await ports.stored(journal.journey.id);
      if (!current(request)) return;
      const latest = saved.journal ?? cached;
      update({
        journal: latest,
        draft: saved.draft,
        title: saved.draft?.title ?? latest.annotation.title,
        notes: saved.draft?.notes ?? latest.annotation.notes,
      });
    } catch (error) {
      if (!current(request)) return;
      update({ failure: classify(error) });
    } finally {
      if (current(request)) update({ busy: false });
    }
  }
  function edit(patch: { title?: string; notes?: string }) {
    if (!state.open || state.busy) return;
    const title = patch.title ?? state.title;
    const notes = patch.notes ?? state.notes;
    update({
      title,
      notes,
      dirty: changed(title, notes),
      reviewed: null,
      failure: state.failure === 'invalid' ? null : state.failure,
      settled: false,
    });
  }
  async function persist(request: number): Promise<NativeJournalDraft | null> {
    const id = journeyId();
    if (!id || !state.journal) return null;
    const previous = state.draft;
    if (!state.dirty && previous) return previous;
    const input = readTripJournalWrite({
      title: state.title,
      notes: state.notes,
      expectedVersion: previous?.expectedVersion ?? state.journal.annotation.version,
      mutationId: ports.mutationId(),
    });
    const saved = await ports.save({ ...input, journeyId: id }, previous?.mutationId ?? null);
    if (!current(request)) {
      // A same-account session rotation can finish after the SQLite commit. Keep
      // the exact durable draft if this editor still represents that text.
      if (
        !disposed &&
        state.open &&
        state.journal?.journey.id === id &&
        (state.draft?.mutationId ?? null) === (previous?.mutationId ?? null)
      )
        update({ draft: saved, dirty: state.title !== saved.title || state.notes !== saved.notes });
      return null;
    }
    update({ draft: saved, dirty: false, failure: null, settled: false });
    return saved;
  }
  async function saveDraft() {
    if (disposed || !state.open || state.busy) return;
    const request = revision;
    update({ busy: true, failure: null });
    try {
      await persist(request);
    } catch (error) {
      if (current(request))
        update({
          failure:
            error instanceof NativeJournalStorageError ||
            error instanceof NativeSessionRequired ||
            error instanceof NativeHttpStatus
              ? classify(error)
              : 'invalid',
        });
    } finally {
      if (current(request)) update({ busy: false });
    }
  }
  async function send() {
    if (disposed || !state.open || state.busy) return;
    let online: boolean;
    try {
      online = ports.online();
    } catch (error) {
      update({ failure: classify(error) });
      return;
    }
    if (!online) {
      update({ failure: 'offline' });
      return;
    }
    const request = revision;
    const abort = new AbortController();
    let acknowledging: string | null = null;
    pending = abort;
    update({ busy: true, failure: null, reviewed: null });
    try {
      const draft = await persist(request);
      if (!draft || !current(request) || abort.signal.aborted) return;
      const { title, notes, expectedVersion, mutationId } = draft;
      const response = await ports.write(
        draft.journeyId,
        { title, notes, expectedVersion, mutationId },
        abort.signal,
      );
      if (!current(request) || abort.signal.aborted) return;
      acknowledging = draft.mutationId;
      localCritical = draft.mutationId;
      let acknowledged: boolean;
      try {
        acknowledged = await ports.acknowledge(draft.journeyId, draft.mutationId, response);
      } finally {
        localCritical = null;
      }
      if (!current(request) || abort.signal.aborted) {
        if (
          acknowledged &&
          !disposed &&
          state.open &&
          !state.dirty &&
          state.journal?.journey.id === draft.journeyId &&
          state.draft?.mutationId === draft.mutationId
        )
          update({
            journal: response,
            draft: null,
            title: response.annotation.title,
            notes: response.annotation.notes,
            dirty: false,
            reviewed: null,
            failure: null,
            settled: true,
            busy: false,
          });
        else if (!disposed && state.open && state.draft?.mutationId === draft.mutationId)
          update({ busy: false, failure: 'storage' });
        return;
      }
      if (!acknowledged) {
        update({ failure: 'storage' });
        return;
      }
      update({
        journal: response,
        draft: null,
        title: response.annotation.title,
        notes: response.annotation.notes,
        dirty: false,
        reviewed: null,
        failure: null,
        settled: true,
      });
    } catch (error) {
      if (current(request) && !abort.signal.aborted) update({ failure: classify(error) });
      else if (
        !disposed &&
        acknowledging &&
        state.open &&
        state.draft?.mutationId === acknowledging
      )
        update({ failure: classify(error) });
    } finally {
      if (current(request)) {
        pending = null;
        update({ busy: false });
      } else if (
        !disposed &&
        acknowledging &&
        state.open &&
        state.draft?.mutationId === acknowledging &&
        !localCritical
      ) {
        update({ busy: false });
      }
    }
  }
  async function reviewLatest() {
    const id = journeyId();
    if (disposed || !state.open || state.busy || !id) return;
    let online: boolean;
    try {
      online = ports.online();
    } catch (error) {
      update({ failure: classify(error) });
      return;
    }
    if (!online) {
      update({ failure: 'offline' });
      return;
    }
    const request = revision;
    const abort = new AbortController();
    pending = abort;
    update({ busy: true, reviewed: null });
    try {
      const latest = await ports.read(id, abort.signal);
      if (!current(request) || abort.signal.aborted) return;
      await ports.cache(latest);
      if (!current(request) || abort.signal.aborted) return;
      update({ reviewed: latest, failure: 'conflict' });
    } catch (error) {
      if (current(request) && !abort.signal.aborted) update({ failure: classify(error) });
    } finally {
      if (current(request)) {
        pending = null;
        update({ busy: false });
      }
    }
  }
  async function useAccountVersion() {
    const id = journeyId();
    const draft = state.draft;
    const reviewed = state.reviewed;
    if (disposed || !state.open || state.busy || !id || !draft || !reviewed) return;
    const confirmedTitle = state.title;
    const confirmedNotes = state.notes;
    const request = revision;
    localCritical = draft.mutationId;
    update({ busy: true });
    try {
      const currentJournal = await ports.discard(id, draft.mutationId, reviewed);
      if (!current(request)) {
        if (
          !disposed &&
          state.open &&
          state.journal?.journey.id === id &&
          state.draft?.mutationId === draft.mutationId &&
          state.title === confirmedTitle &&
          state.notes === confirmedNotes
        )
          update({
            journal: currentJournal,
            draft: null,
            title: currentJournal.annotation.title,
            notes: currentJournal.annotation.notes,
            reviewed: null,
            failure: null,
            dirty: false,
            settled: false,
            busy: false,
          });
        return;
      }
      update({
        journal: currentJournal,
        draft: null,
        title: currentJournal.annotation.title,
        notes: currentJournal.annotation.notes,
        reviewed: null,
        failure: null,
        dirty: false,
        settled: false,
      });
    } catch (error) {
      if (
        current(request) ||
        (!disposed && state.open && state.draft?.mutationId === draft.mutationId)
      )
        update({ failure: classify(error), reviewed: null });
    } finally {
      localCritical = null;
      if (
        current(request) ||
        (!disposed && state.open && state.draft?.mutationId === draft.mutationId)
      )
        update({ busy: false });
    }
  }
  return {
    state: () => state,
    open,
    edit,
    saveDraft,
    send,
    reviewLatest,
    useAccountVersion,
    close: () => {
      if (state.busy || state.dirty) return false;
      cancel();
      update({
        open: false,
        title: '',
        notes: '',
        journal: null,
        draft: null,
        reviewed: null,
        failure: null,
        dirty: false,
        settled: false,
      });
      return true;
    },
    abandon: () => {
      if (state.busy) return false;
      cancel();
      update({
        open: false,
        title: '',
        notes: '',
        journal: null,
        draft: null,
        reviewed: null,
        failure: null,
        dirty: false,
        settled: false,
      });
      return true;
    },
    sessionChanged: () => {
      cancel();
      if (state.open)
        update({ busy: localCritical !== null, reviewed: null, failure: null, settled: false });
    },
    dispose: () => {
      disposed = true;
      cancel();
    },
  };
}
