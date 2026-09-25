import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import {
  createJourneyLocationStore,
  type LocationDriver,
  type LocationFix,
  type LocationPermission,
} from '../apps/mobile/src/features/journey/journey-location';

function harness(permission: LocationPermission = 'granted', services = true) {
  let clock = 10_000;
  const timers: Array<{ at: number; task: () => void }> = [];
  let emit: ((fix: LocationFix) => void) | null = null;
  let fail: (() => void) | null = null;
  const stopWatching = vi.fn();
  const driver: LocationDriver = {
    permission: vi.fn(async () => permission),
    request: vi.fn(async () => {
      permission = permission === 'undetermined' ? 'granted' : permission;
      return permission;
    }),
    servicesEnabled: vi.fn(async () => services),
    watch: vi.fn(async (onFix, onError) => {
      emit = onFix;
      fail = onError;
      return stopWatching;
    }),
  };
  const store = createJourneyLocationStore(
    driver,
    () => clock,
    (task, delay) => timers.push({ at: clock + delay, task }),
  );
  return {
    store,
    driver,
    stopWatching,
    fix: (accuracyMetres: number | null = 20, coordinate: [number, number] = [80, 12.9]) =>
      emit?.({ coordinate, accuracyMetres, at: clock }),
    fail: () => fail?.(),
    advance(ms: number) {
      clock += ms;
      timers.splice(0).forEach((timer) => (timer.at <= clock ? timer.task() : timers.push(timer)));
    },
    setPermission: (next: LocationPermission) => (permission = next),
  };
}

describe('journey location store', () => {
  it('never asks for permission on its own', async () => {
    const h = harness('undetermined');
    await h.store.setActive(true);
    expect(h.driver.request).not.toHaveBeenCalled();
    expect(h.driver.watch).not.toHaveBeenCalled();
    expect(h.store.getState().status).toBe('not_asked');
  });

  it('asks only from an explicit request, then watches while active', async () => {
    const h = harness('undetermined');
    await h.store.setActive(true);
    await h.store.request();
    expect(h.driver.watch).toHaveBeenCalledTimes(1);
    expect(h.store.getState().status).toBe('waiting');
    h.fix(20);
    expect(h.store.getState()).toMatchObject({ status: 'ready', fix: { accuracyMetres: 20 } });
  });

  it('reports denied, blocked and location services off', async () => {
    const denied = harness('denied');
    await denied.store.setActive(true);
    expect(denied.store.getState().status).toBe('denied');
    const blocked = harness('blocked');
    await blocked.store.setActive(true);
    expect(blocked.store.getState().status).toBe('blocked');
    const off = harness('granted', false);
    await off.store.setActive(true);
    expect(off.store.getState().status).toBe('services_off');
    expect(off.driver.watch).not.toHaveBeenCalled();
  });

  it('labels weak accuracy and watch failures', async () => {
    const h = harness();
    await h.store.setActive(true);
    h.fix(250);
    expect(h.store.getState().status).toBe('weak');
    h.fail();
    expect(h.store.getState()).toEqual({ status: 'unavailable', fix: null });
    expect(h.stopWatching).toHaveBeenCalled();
  });

  it('publishes at most once per second', async () => {
    const h = harness();
    await h.store.setActive(true);
    const listener = vi.fn();
    h.store.subscribe(listener);
    h.advance(2000);
    h.fix(20, [80, 12.1]);
    h.fix(20, [80, 12.2]);
    h.fix(20, [80, 12.3]);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(h.store.getState().fix?.coordinate).toEqual([80, 12.1]);
    h.advance(1000);
    expect(listener).toHaveBeenCalledTimes(2);
    expect(h.store.getState().fix?.coordinate).toEqual([80, 12.3]);
  });

  it('stops and forgets the reading when inactive or stopped', async () => {
    const h = harness();
    await h.store.setActive(true);
    h.fix();
    await h.store.setActive(false);
    expect(h.stopWatching).toHaveBeenCalledTimes(1);
    expect(h.store.getState()).toEqual({ status: 'idle', fix: null });
    await h.store.setActive(true);
    h.fix();
    h.store.stop();
    expect(h.store.getState()).toEqual({ status: 'idle', fix: null });
    // Late readings after stopping are ignored.
    h.fix();
    expect(h.store.getState().fix).toBeNull();
  });

  it('does not start watching while inactive, even with permission', async () => {
    const h = harness();
    await h.store.check();
    expect(h.driver.watch).not.toHaveBeenCalled();
  });

  it('keeps location code free of storage, transport and logging', () => {
    for (const file of ['journey-location.ts', 'journey-location-expo.ts']) {
      const source = readFileSync(
        new URL(`../apps/mobile/src/features/journey/${file}`, import.meta.url),
        'utf8',
      );
      const imports = source.match(/^import .*$/gm) ?? [];
      for (const line of imports)
        expect(line).not.toMatch(/storage|transport|sqlite|secure-store|netinfo|analytics/i);
      expect(source).not.toMatch(/fetch\(|console\.|startLocationUpdates|TaskManager/);
      expect(source).not.toMatch(/requestBackgroundPermissions/);
    }
  });
});
