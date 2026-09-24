import { NativeSessionRequired } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import type { NativeLiveConsent } from './native-consent';

const maxGeneration = '9223372036854775807';
export type NativeConsentFailure =
  'offline' | 'conflict' | 'unavailable' | 'rate_limited' | 'session' | 'missing' | null;
export type NativeConsentNotice =
  | 'unknown'
  | 'checking'
  | 'checked'
  | 'allowing'
  | 'allowed'
  | 'stopping'
  | 'stopped'
  | 'interrupted';
export interface NativeConsentState {
  confirmed: NativeLiveConsent | null;
  lastGeneration: string;
  uncertain: boolean;
  terminal: boolean;
  busy: boolean;
  notice: NativeConsentNotice;
  failure: NativeConsentFailure;
}
export interface NativeConsentEnvironment {
  accountId: string | null;
  journeyId: string | null;
  online: boolean;
  eligible: boolean;
  foreground: boolean;
  focused: boolean;
  sessionEpoch: number;
}
export interface NativeConsentPorts {
  environment(): NativeConsentEnvironment;
  onAuthorityChange?(generation: string | null, sessionEpoch: number): void;
  read(signal: AbortSignal): Promise<NativeLiveConsent>;
  submit(
    input: { expectedGeneration: string; sharing: boolean },
    signal: AbortSignal,
  ): Promise<NativeLiveConsent>;
}

export function createNativeConsentController(
  accountId: string,
  journeyId: string,
  ports: NativeConsentPorts,
  publish: (state: NativeConsentState) => void,
) {
  let state: NativeConsentState = {
    confirmed: null,
    lastGeneration: '0',
    uncertain: false,
    terminal: false,
    busy: false,
    notice: 'unknown',
    failure: null,
  };
  let revision = 0;
  let pending: AbortController | null = null;
  let pendingMutation = false;
  let requestEpoch = 0;
  let confirmedEpoch = -1;
  let disposed = false;
  const update = (patch: Partial<NativeConsentState>) => {
    state = { ...state, ...patch };
    if (!disposed) publish(state);
  };
  const available = () => {
    const value = ports.environment();
    return (
      value.accountId === accountId &&
      value.journeyId === journeyId &&
      value.online &&
      value.eligible &&
      value.foreground &&
      value.focused
    );
  };
  const cancel = () => {
    ports.onAuthorityChange?.(null, ports.environment().sessionEpoch);
    revision++;
    pending?.abort();
    pending = null;
    const uncertain = state.uncertain || pendingMutation;
    pendingMutation = false;
    update({
      confirmed: null,
      uncertain,
      busy: false,
      notice: uncertain ? 'interrupted' : 'unknown',
    });
  };
  const classify = (error: unknown): NativeConsentFailure => {
    if (error instanceof NativeSessionRequired) return 'session';
    if (error instanceof NativeHttpStatus) {
      if (error.status === 401 || error.status === 403) return 'session';
      if (error.status === 404) return 'missing';
      if (error.status === 409) return 'conflict';
      if (error.status === 429) return 'rate_limited';
    }
    return 'unavailable';
  };
  const accept = (value: NativeLiveConsent, sharing: boolean | null) => {
    if (
      value.journeyId !== journeyId ||
      (value.sharing && !value.journeyActive) ||
      (sharing !== null && value.journeyActive && value.sharing !== sharing)
    )
      throw new Error('Invalid native consent response.');
    const terminal = state.terminal || !value.journeyActive;
    confirmedEpoch = requestEpoch;
    const uncertain = sharing === false ? false : sharing === true ? false : state.uncertain;
    update({
      confirmed: terminal ? null : value,
      lastGeneration: value.generation,
      terminal,
      uncertain,
      failure: null,
      notice: terminal
        ? 'checked'
        : sharing === true
          ? 'allowed'
          : sharing === false
            ? 'stopped'
            : 'checked',
    });
  };
  const begin = (mutation: boolean, notice: NativeConsentNotice) => {
    if (disposed || state.busy || !available()) return null;
    ports.onAuthorityChange?.(null, ports.environment().sessionEpoch);
    const run = ++revision;
    requestEpoch = ports.environment().sessionEpoch;
    const abort = new AbortController();
    pending = abort;
    pendingMutation = mutation;
    update({
      busy: true,
      failure: null,
      notice,
      ...(mutation ? { uncertain: true, confirmed: null } : {}),
    });
    return { run, abort };
  };
  const current = (run: number, abort: AbortController) =>
    !disposed &&
    run === revision &&
    !abort.signal.aborted &&
    available() &&
    ports.environment().sessionEpoch === requestEpoch;
  const finish = (run: number, abort: AbortController) => {
    if (!current(run, abort)) return;
    pending = null;
    pendingMutation = false;
    update({ busy: false });
    if (
      state.confirmed?.journeyActive &&
      state.confirmed.sharing &&
      !state.uncertain &&
      !state.terminal
    )
      ports.onAuthorityChange?.(state.confirmed.generation, requestEpoch);
  };
  async function check() {
    const started = begin(false, 'checking');
    if (!started) return;
    const { run, abort } = started;
    try {
      const value = await ports.read(abort.signal);
      if (!current(run, abort)) return;
      accept(value, null);
    } catch (error) {
      if (!current(run, abort)) return;
      update({
        confirmed: null,
        failure: classify(error),
        notice: state.uncertain ? 'interrupted' : 'unknown',
      });
    } finally {
      finish(run, abort);
    }
  }
  async function change(sharing: boolean) {
    if (
      sharing &&
      (state.uncertain ||
        state.terminal ||
        confirmedEpoch !== ports.environment().sessionEpoch ||
        !state.confirmed?.journeyActive ||
        state.confirmed.sharing ||
        state.confirmed.generation === maxGeneration)
    )
      return;
    const expectedGeneration = state.lastGeneration;
    const started = begin(true, sharing ? 'allowing' : 'stopping');
    if (!started) return;
    const { run, abort } = started;
    try {
      const value = await ports.submit({ expectedGeneration, sharing }, abort.signal);
      if (!current(run, abort)) return;
      accept(value, sharing);
    } catch (error) {
      if (!current(run, abort)) return;
      update({ confirmed: null, uncertain: true, failure: classify(error), notice: 'interrupted' });
    } finally {
      finish(run, abort);
    }
  }
  return {
    state: () => state,
    check,
    allow: () => change(true),
    stop: () => change(false),
    suspend: () => {
      if (!disposed) cancel();
    },
    environmentChanged: () => {
      if (!disposed && !available()) cancel();
    },
    sessionChanged: () => {
      if (!disposed) cancel();
    },
    dispose: () => {
      ports.onAuthorityChange?.(null, ports.environment().sessionEpoch);
      disposed = true;
      revision++;
      pending?.abort();
      pending = null;
    },
  };
}
