import type { RouteCoordinate } from '@routiqo/shared';

/**
 * Foreground-only own position for the Journey map (ANDROID_JOURNEY_MAP_SPEC, ADR 0067).
 * Readings stay in this in-memory store: never persisted, logged or sent anywhere.
 * This module must not import storage, transport or analytics code.
 */
export type LocationPermission = 'granted' | 'undetermined' | 'denied' | 'blocked';
export interface LocationFix {
  coordinate: RouteCoordinate;
  accuracyMetres: number | null;
  at: number;
}
export interface LocationDriver {
  permission(): Promise<LocationPermission>;
  request(): Promise<LocationPermission>;
  servicesEnabled(): Promise<boolean>;
  /** Balanced accuracy, about every 30 s or 50 m, foreground only. */
  watch(onFix: (fix: LocationFix) => void, onError: () => void): Promise<() => void>;
}
export type LocationStatus =
  | 'idle'
  | 'not_asked'
  | 'requesting'
  | 'denied'
  | 'blocked'
  | 'services_off'
  | 'waiting'
  | 'weak'
  | 'ready'
  | 'unavailable';
export interface LocationState {
  status: LocationStatus;
  fix: LocationFix | null;
}

export const WEAK_ACCURACY_METRES = 100;
export const LOCATION_PUBLISH_INTERVAL_MS = 1000;

export interface JourneyLocationStore {
  getState(): LocationState;
  subscribe(listener: () => void): () => void;
  /** Reads the permission without asking; starts updates if already granted and active. */
  check(): Promise<void>;
  /** Asks for while-in-use permission from an explicit user action, then starts. */
  request(): Promise<void>;
  /** The Journey screen is visible and the app is in the foreground. */
  setActive(active: boolean): Promise<void>;
  /** Stops updates and forgets the last reading. */
  stop(): void;
}

export function createJourneyLocationStore(
  driver: LocationDriver,
  now: () => number = Date.now,
  schedule: (task: () => void, delay: number) => unknown = setTimeout,
): JourneyLocationStore {
  let state: LocationState = { status: 'idle', fix: null };
  const listeners = new Set<() => void>();
  let active = false;
  let unwatch: (() => void) | null = null;
  let generation = 0;
  let lastFixPublished = Number.NEGATIVE_INFINITY;
  let pendingFix: LocationFix | null = null;
  let flushScheduled = false;

  const set = (next: LocationState) => {
    state = next;
    listeners.forEach((listener) => listener());
  };
  const statusFor = (fix: LocationFix): LocationStatus =>
    fix.accuracyMetres === null || fix.accuracyMetres > WEAK_ACCURACY_METRES ? 'weak' : 'ready';
  const flush = () => {
    flushScheduled = false;
    if (!pendingFix || !unwatch) return;
    const fix = pendingFix;
    pendingFix = null;
    lastFixPublished = now();
    set({ status: statusFor(fix), fix });
  };
  const onFix = (run: number) => (fix: LocationFix) => {
    if (run !== generation) return;
    pendingFix = fix;
    const wait = LOCATION_PUBLISH_INTERVAL_MS - (now() - lastFixPublished);
    if (wait <= 0) flush();
    else if (!flushScheduled) {
      flushScheduled = true;
      schedule(flush, wait);
    }
  };
  const halt = () => {
    generation++;
    unwatch?.();
    unwatch = null;
    pendingFix = null;
  };
  const fromPermission = (permission: LocationPermission): LocationStatus =>
    permission === 'undetermined' ? 'not_asked' : permission === 'blocked' ? 'blocked' : 'denied';

  async function begin(permission: LocationPermission) {
    if (permission !== 'granted') {
      halt();
      set({ status: fromPermission(permission), fix: null });
      return;
    }
    if (!active || unwatch) return;
    const run = ++generation;
    try {
      if (!(await driver.servicesEnabled())) {
        if (run === generation) set({ status: 'services_off', fix: null });
        return;
      }
      if (run !== generation || !active) return;
      set({ status: 'waiting', fix: null });
      const stopWatching = await driver.watch(onFix(run), () => {
        if (run !== generation) return;
        halt();
        set({ status: 'unavailable', fix: null });
      });
      if (run !== generation || !active) {
        stopWatching();
        return;
      }
      unwatch = stopWatching;
    } catch {
      if (run === generation) set({ status: 'unavailable', fix: null });
    }
  }

  return {
    getState: () => state,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    async check() {
      try {
        await begin(await driver.permission());
      } catch {
        set({ status: 'unavailable', fix: null });
      }
    },
    async request() {
      set({ status: 'requesting', fix: state.fix });
      try {
        await begin(await driver.request());
      } catch {
        set({ status: 'unavailable', fix: null });
      }
    },
    async setActive(next) {
      active = next;
      if (!next) {
        halt();
        if (state.status !== 'idle') set({ status: 'idle', fix: null });
        return;
      }
      await this.check();
    },
    stop() {
      active = false;
      halt();
      set({ status: 'idle', fix: null });
    },
  };
}
