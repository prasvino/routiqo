import { NativeSessionRequired } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import type { NativeHistoryCursor, NativeHistoryPage } from './native-history';

export interface NativeHistoryState {
  page: NativeHistoryPage | null;
  pageLabel: 'Latest journeys' | 'Earlier journeys';
  busy: boolean;
  error: boolean;
  authenticationRequired: boolean;
}

/** One instance belongs to one account and session epoch; only its current request may publish. */
export function createNativeHistoryController(
  read: (before: NativeHistoryCursor | null) => Promise<NativeHistoryPage>,
  online: () => boolean,
  publish: (state: NativeHistoryState) => void,
) {
  let state: NativeHistoryState = {
    page: null,
    pageLabel: 'Latest journeys',
    busy: false,
    error: false,
    authenticationRequired: false,
  };
  let retry: NativeHistoryCursor | null = null;
  let revision = 0;
  let disposed = false;
  const update = (patch: Partial<NativeHistoryState>) => {
    state = { ...state, ...patch };
    if (!disposed) publish(state);
  };
  async function load(before: NativeHistoryCursor | null) {
    if (disposed || state.busy || !online() || state.authenticationRequired) return;
    const request = ++revision;
    retry = before;
    update({ busy: true, error: false });
    try {
      const page = await read(before);
      if (disposed || request !== revision) return;
      update({
        page,
        pageLabel: before === null ? 'Latest journeys' : 'Earlier journeys',
        authenticationRequired: false,
      });
    } catch (failure) {
      if (disposed || request !== revision) return;
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeHttpStatus && (failure.status === 401 || failure.status === 403))
      )
        update({ page: null, error: false, authenticationRequired: true });
      else update({ error: true });
    } finally {
      if (!disposed && request === revision) update({ busy: false });
    }
  }
  return {
    state: () => state,
    latest: () => load(null),
    earlier: () => (state.page?.next ? load(state.page.next) : Promise.resolve()),
    retry: () => load(retry),
    dispose: () => {
      disposed = true;
      revision++;
    },
  };
}
