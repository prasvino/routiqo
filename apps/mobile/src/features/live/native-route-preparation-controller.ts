import { NativeSessionRequired } from '../../auth/native-account';
import {
  NativeRouteContextError,
  type NativeRouteBindingInput,
  type NativeRouteBindingResult,
  type NativeRouteContext,
  type NativeRouteContextRead,
} from './native-route-context';
import type {
  NativePreparationConsent,
  NativePreparationSelection,
} from './native-route-preparation-coordinator';

export type NativePreparationFailure =
  | 'offline'
  | 'conflict'
  | 'rate_limited'
  | 'session'
  | 'forbidden'
  | 'missing'
  | 'unavailable'
  | 'expired'
  | 'no_route'
  | 'no_anchors'
  | null;
export interface NativePreparationState {
  observed: NativeRouteContext | null | undefined; // undefined = never observed; null = explicitly absent.
  acknowledged: NativeRouteContext | null;
  busy: 'checking' | 'preparing' | null;
  failure: NativePreparationFailure;
}
export interface NativePreparationEnvironment {
  accountId: string | null;
  journeyId: string | null;
  sessionEpoch: number;
  online: boolean;
  eligible: boolean;
  foreground: boolean;
  focused: boolean;
}
export interface NativePreparationPorts {
  environment(): NativePreparationEnvironment;
  consent(): NativePreparationConsent | null;
  selection(): NativePreparationSelection;
  read(signal: AbortSignal): Promise<NativeRouteContextRead>;
  bind(input: NativeRouteBindingInput, signal: AbortSignal): Promise<NativeRouteBindingResult>;
  now?(): number;
}
function instantNanos(value: string): bigint {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match) return -1n;
  const milliseconds = Date.parse(`${match[1]}Z`);
  if (!Number.isFinite(milliseconds)) return -1n;
  return BigInt(milliseconds) * 1_000_000n + BigInt((match[2] ?? '').padEnd(9, '0') || '0');
}
export function createNativeRoutePreparationController(
  accountId: string,
  journeyId: string,
  ports: NativePreparationPorts,
  publish: (state: NativePreparationState) => void,
) {
  let state: NativePreparationState = {
    observed: undefined,
    acknowledged: null,
    busy: null,
    failure: null,
  };
  let revision = 0;
  let pending: AbortController | null = null;
  let disposed = false;
  const now = () => BigInt(ports.now?.() ?? Date.now()) * 1_000_000n;
  const currentContext = (value: NativeRouteContext) =>
    instantNanos(value.issuedAt) <= now() && now() < instantNanos(value.expiresAt);
  const update = (patch: Partial<NativePreparationState>) => {
    state = { ...state, ...patch };
    if (!disposed) publish(state);
  };
  const scope = () => {
    const env = ports.environment();
    const consent = ports.consent();
    const selection = ports.selection();
    if (
      env.accountId !== accountId ||
      env.journeyId !== journeyId ||
      !env.online ||
      !env.eligible ||
      !env.foreground ||
      !env.focused ||
      consent?.accountId !== accountId ||
      consent.journeyId !== journeyId ||
      consent.sessionEpoch !== env.sessionEpoch ||
      selection.sessionEpoch !== env.sessionEpoch ||
      !selection.selection
    )
      return null;
    return { env, consent, selection };
  };
  const clear = (failure: NativePreparationFailure = null) => {
    revision++;
    pending?.abort();
    pending = null;
    update({ observed: undefined, acknowledged: null, busy: null, failure });
  };
  const classify = (error: unknown): NativePreparationFailure => {
    if (error instanceof NativeSessionRequired) return 'session';
    if (error instanceof NativeRouteContextError) {
      if (error.code === 'session') return 'session';
      if (error.code === 'forbidden') return 'forbidden';
      if (error.code === 'not_found') return 'missing';
      if (error.code === 'conflict') return 'conflict';
      if (error.code === 'rate_limited') return 'rate_limited';
    }
    return 'unavailable';
  };
  const begin = (busy: 'checking' | 'preparing') => {
    if (disposed || state.busy) return null;
    const snapshot = scope();
    if (!snapshot) return null;
    const run = ++revision;
    const abort = new AbortController();
    pending = abort;
    update({ observed: undefined, acknowledged: null, busy, failure: null });
    return { run, abort, snapshot };
  };
  const current = (started: NonNullable<ReturnType<typeof begin>>) => {
    if (disposed || started.run !== revision || started.abort.signal.aborted) return false;
    const value = scope();
    return Boolean(
      value &&
      value.env.sessionEpoch === started.snapshot.env.sessionEpoch &&
      value.consent.epoch === started.snapshot.consent.epoch &&
      value.consent.generation === started.snapshot.consent.generation &&
      value.selection.epoch === started.snapshot.selection.epoch,
    );
  };
  const finish = (started: NonNullable<ReturnType<typeof begin>>) => {
    if (!current(started)) return;
    pending = null;
    update({ busy: null });
  };
  async function check() {
    const started = begin('checking');
    if (!started) return;
    try {
      const value = await ports.read(started.abort.signal);
      if (!current(started)) return;
      if (value.context && !currentContext(value.context)) {
        update({ failure: 'expired' });
        return;
      }
      update({ observed: value.context });
    } catch (error) {
      if (!current(started)) return;
      update({ failure: classify(error) });
    } finally {
      finish(started);
    }
  }
  async function prepare() {
    if (state.observed === undefined || state.acknowledged) return;
    if (state.observed && !currentContext(state.observed)) {
      clear('expired');
      return;
    }
    const expectedContextId = state.observed?.contextId ?? null;
    const started = begin('preparing');
    if (!started) return;
    const selected = started.snapshot.selection.selection!;
    const input: NativeRouteBindingInput = {
      mode: selected.mode,
      origin: [selected.origin[0], selected.origin[1]],
      destination: [selected.destination[0], selected.destination[1]],
      alternativeIndex: selected.alternativeIndex,
      expectedContextId,
    };
    try {
      const value = await ports.bind(input, started.abort.signal);
      if (!current(started)) return;
      if (value.status !== 'bound' || !value.context) {
        update({ failure: value.status === 'no_route' ? 'no_route' : 'no_anchors' });
      } else if (!currentContext(value.context)) {
        update({ failure: 'expired' });
      } else {
        update({ observed: value.context, acknowledged: value.context });
      }
    } catch (error) {
      if (!current(started)) return;
      update({ failure: classify(error) });
    } finally {
      finish(started);
    }
  }
  function expire() {
    if (disposed) return;
    if (
      (state.observed && !currentContext(state.observed)) ||
      (state.acknowledged && !currentContext(state.acknowledged))
    )
      clear('expired');
  }
  function expireFromTimer(contextId: string, expiresAt: string) {
    const value = state.acknowledged ?? state.observed;
    if (!disposed && value && value.contextId === contextId && value.expiresAt === expiresAt)
      clear('expired');
  }
  function nextExpiryMs(): number | null {
    const value = state.acknowledged ?? state.observed;
    if (!value) return null;
    const remaining = instantNanos(value.expiresAt) - now();
    if (remaining <= 0n) return 0;
    const milliseconds = (remaining + 999_999n) / 1_000_000n;
    return Number(milliseconds > 2_147_483_647n ? 2_147_483_647n : milliseconds);
  }
  return {
    state: () => state,
    check,
    prepare,
    expire,
    expireFromTimer,
    nextExpiryMs,
    authorityChanged: () => {
      if (!disposed) clear();
    },
    environmentChanged: () => {
      if (!disposed && !scope()) clear();
    },
    sessionChanged: () => {
      if (!disposed) clear();
    },
    suspend: () => {
      if (!disposed) clear();
    },
    dispose: () => {
      disposed = true;
      revision++;
      pending?.abort();
      pending = null;
    },
  };
}
