import type { SpotActivity } from '@routiqo/shared';
import { NativeSpotsError, type SpotsFailure } from './native-spots';

export const ACTIVITY_INTERVAL_MS = 60_000;
export const DETAIL_INTERVAL_MS = 20_000;
export const MAX_BACKOFF_MS = 300_000;

export type SpotActivityStatus = 'idle' | 'loading' | 'ready' | Exclude<SpotsFailure, 'invalid'>;

export interface SpotActivityState {
  /** The last good response, kept in memory only; never written anywhere. */
  activity: SpotActivity | null;
  receivedAt: number | null;
  status: SpotActivityStatus;
}

export interface SpotActivityPorts {
  /** Journey mode focused, app in foreground, online, signed in, journey current and confirmed. */
  eligible(): boolean;
  fetch(spotIds: readonly string[], signal: AbortSignal): Promise<SpotActivity>;
  now(): number;
  schedule(run: () => void, delayMs: number): () => void;
  onCatalogVersion?(version: string): void;
}

/**
 * Bounded foreground refresh of Spot activity (ADR 0066, SPOTS_SPEC): every 60 s, or every 20 s
 * while a Spot detail is open; one request in flight; cancelled when no longer eligible; exponential
 * backoff up to 5 minutes on 429 and 503. Create one per account and journey, so switching either
 * drops all state.
 */
export function createSpotActivityController(
  ports: SpotActivityPorts,
  publish: (state: SpotActivityState) => void,
) {
  let state: SpotActivityState = { activity: null, receivedAt: null, status: 'idle' };
  let spotIds: string[] = [];
  let detailOpen = false;
  let lastAttemptAt: number | null = null;
  /** Restored when a request is cancelled, so returning to Journey mode refreshes promptly. */
  let attemptBefore: number | null = null;
  let failures = 0;
  let inFlight: AbortController | null = null;
  let cancelTimer: (() => void) | null = null;
  let disposed = false;

  const set = (next: Partial<SpotActivityState>) => {
    state = { ...state, ...next };
    publish(state);
  };
  const interval = () => (detailOpen ? DETAIL_INTERVAL_MS : ACTIVITY_INTERVAL_MS);
  const delay = () =>
    failures === 0 ? interval() : Math.min(MAX_BACKOFF_MS, interval() * 2 ** failures);

  function clearTimer() {
    cancelTimer?.();
    cancelTimer = null;
  }

  function cancelInFlight() {
    if (!inFlight) return;
    inFlight.abort();
    inFlight = null;
    lastAttemptAt = attemptBefore;
  }

  function plan() {
    clearTimer();
    if (disposed) return;
    if (!ports.eligible() || spotIds.length === 0) {
      cancelInFlight();
      return;
    }
    if (inFlight) return;
    const due = lastAttemptAt === null ? ports.now() : lastAttemptAt + delay();
    cancelTimer = ports.schedule(run, Math.max(0, due - ports.now()));
  }

  async function run() {
    cancelTimer = null;
    if (disposed || inFlight || !ports.eligible() || spotIds.length === 0) return;
    const controller = new AbortController();
    inFlight = controller;
    attemptBefore = lastAttemptAt;
    lastAttemptAt = ports.now();
    // A retry keeps showing the last outcome (e.g. "Updates paused briefly") rather than a spinner.
    if (state.status === 'idle') set({ status: 'loading' });
    try {
      const activity = await ports.fetch([...spotIds], controller.signal);
      if (inFlight !== controller) return;
      failures = 0;
      set({ activity, receivedAt: ports.now(), status: 'ready' });
      ports.onCatalogVersion?.(activity.catalogVersion);
    } catch (error) {
      if (inFlight !== controller) return;
      if (error instanceof Error && error.name === 'AbortError') return;
      const code = error instanceof NativeSpotsError ? error.code : 'unavailable';
      if (code === 'rate-limited' || code === 'unavailable' || code === 'invalid') failures += 1;
      set({ status: code === 'invalid' ? 'unavailable' : code });
      if (code === 'session') {
        inFlight = null;
        return;
      }
    } finally {
      if (inFlight === controller) inFlight = null;
    }
    plan();
  }

  return {
    getState: () => state,
    /** The current Spots ahead, as sorted IDs. An empty list stops requests. */
    setSpotIds(next: readonly string[]) {
      const sorted = [...next].sort();
      if (sorted.length === spotIds.length && sorted.every((id, index) => id === spotIds[index]))
        return;
      spotIds = sorted;
      plan();
    },
    setDetailOpen(open: boolean) {
      if (open === detailOpen) return;
      detailOpen = open;
      plan();
    },
    /** Call when focus, foreground, network, session or journey confirmation changes. */
    environmentChanged: plan,
    dispose() {
      disposed = true;
      clearTimer();
      cancelInFlight();
    },
  };
}
export type SpotActivityController = ReturnType<typeof createSpotActivityController>;
