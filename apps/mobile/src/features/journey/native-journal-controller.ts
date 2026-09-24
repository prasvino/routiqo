import type { TripJournal } from '@routiqo/shared';
import { NativeSessionRequired } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import type { NativeHistoryPage } from './native-history';

export type NativeJournalFailure = 'unavailable' | 'missing' | 'session' | null;
export interface NativeJournalState {
  selectedId: string | null;
  journal: TripJournal | null;
  busy: boolean;
  failure: NativeJournalFailure;
}

export function createNativeJournalController(
  read: (id: string, signal: AbortSignal) => Promise<TripJournal>,
  online: () => boolean,
  publish: (state: NativeJournalState) => void,
) {
  let state: NativeJournalState = { selectedId: null, journal: null, busy: false, failure: null };
  let revision = 0;
  let pending: AbortController | null = null;
  let disposed = false;
  const update = (patch: Partial<NativeJournalState>) => {
    state = { ...state, ...patch };
    if (!disposed) publish(state);
  };
  const cancel = () => {
    revision++;
    pending?.abort();
    pending = null;
  };
  async function load(id: string) {
    if (disposed || !online()) return;
    cancel();
    const request = revision;
    const abort = new AbortController();
    pending = abort;
    update({ busy: true, failure: null });
    try {
      const journal = await read(id, abort.signal);
      if (disposed || abort.signal.aborted || request !== revision) return;
      if (
        journal.journey.id !== id ||
        journal.journey.kind !== 'trip' ||
        journal.journey.status !== 'completed'
      )
        throw new Error('Journal identity changed.');
      update({ journal, failure: null });
    } catch (error) {
      if (disposed || abort.signal.aborted || request !== revision) return;
      const status = error instanceof NativeHttpStatus ? error.status : null;
      const failure: NativeJournalFailure =
        error instanceof NativeSessionRequired || status === 401 || status === 403
          ? 'session'
          : status === 404 || status === 409
            ? 'missing'
            : 'unavailable';
      update({ journal: failure === 'unavailable' ? state.journal : null, failure });
    } finally {
      if (!disposed && !abort.signal.aborted && request === revision) {
        pending = null;
        update({ busy: false });
      }
    }
  }
  return {
    state: () => state,
    open: (id: string, page: NativeHistoryPage | null) => {
      if (
        disposed ||
        !page?.journeys.some(
          (journey) =>
            journey.id === id && journey.kind === 'trip' && journey.status === 'completed',
        )
      )
        return;
      cancel();
      update({ selectedId: id, journal: null, busy: false, failure: null });
      return load(id);
    },
    retry: () => (state.selectedId && !state.busy ? load(state.selectedId) : Promise.resolve()),
    close: () => {
      cancel();
      update({ selectedId: null, journal: null, busy: false, failure: null });
    },
    offline: () => {
      if (pending) {
        cancel();
        update({ busy: false, failure: null });
      }
    },
    dispose: () => {
      disposed = true;
      cancel();
      state = { selectedId: null, journal: null, busy: false, failure: null };
    },
  };
}
