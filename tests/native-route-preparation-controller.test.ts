import { expect, it, vi } from 'vitest';
import {
  NativeRouteContextError,
  type NativeRouteContext,
} from '../apps/mobile/src/features/live/native-route-context';
import { createNativeRoutePreparationCoordinator } from '../apps/mobile/src/features/live/native-route-preparation-coordinator';
import { createNativeRoutePreparationController } from '../apps/mobile/src/features/live/native-route-preparation-controller';

const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const accountId = id(1);
const journeyId = id(2);
const context: NativeRouteContext = {
  contextId: id(3),
  revision: '1',
  anchorIds: [id(4)],
  issuedAt: '2026-09-24T10:00:00Z',
  expiresAt: '2026-09-24T10:10:00Z',
};
const selection = {
  mode: 'driving' as const,
  origin: [80, 13] as [number, number],
  destination: [81, 13] as [number, number],
  alternativeIndex: 1,
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function fixture() {
  let time = Date.parse('2026-09-24T10:01:00Z');
  const env = {
    accountId,
    journeyId,
    sessionEpoch: 1,
    online: true,
    eligible: true,
    foreground: true,
    focused: true,
  };
  const coordinator = createNativeRoutePreparationCoordinator();
  coordinator.setConsent(accountId, journeyId, '4', 1);
  coordinator.setSelection(selection, 1);
  const read = vi.fn(async () => ({ context: null as NativeRouteContext | null }));
  const bind = vi.fn(async () => ({ status: 'bound' as const, context }));
  const publish = vi.fn();
  const controller = createNativeRoutePreparationController(
    accountId,
    journeyId,
    {
      environment: () => env,
      consent: () => coordinator.consent(accountId, journeyId),
      selection: () => coordinator.selection(),
      read,
      bind,
      now: () => time,
    },
    publish,
  );
  const unsubscribe = coordinator.subscribe(() => controller.authorityChanged());
  return {
    env,
    coordinator,
    read,
    bind,
    publish,
    controller,
    setTime: (value: string) => {
      time = Date.parse(value);
    },
    unsubscribe,
  };
}

it('distinguishes unknown from observed null and sends exact copied expectation only after Check', async () => {
  const f = fixture();
  expect(f.controller.state().observed).toBeUndefined();
  expect(f.read).not.toHaveBeenCalled();
  await f.controller.prepare();
  expect(f.bind).not.toHaveBeenCalled();
  await f.controller.check();
  expect(f.controller.state().observed).toBeNull();
  await f.controller.prepare();
  expect(f.bind).toHaveBeenCalledWith(
    { ...selection, expectedContextId: null },
    expect.any(AbortSignal),
  );
  expect(f.controller.state().acknowledged).toEqual(context);
  await f.controller.check();
  expect(f.controller.state().acknowledged).toBeNull();
  expect(f.controller.state().observed).toBeNull();
  f.unsubscribe();
});

it('uses an observed context ID but never claims a GET recovered the displayed route', async () => {
  const f = fixture();
  f.read.mockResolvedValueOnce({ context });
  await f.controller.check();
  expect(f.controller.state()).toMatchObject({ observed: context, acknowledged: null });
  await f.controller.prepare();
  expect(f.bind).toHaveBeenCalledWith(
    { ...selection, expectedContextId: context.contextId },
    expect.any(AbortSignal),
  );
  f.unsubscribe();
});

it('pending Stop invalidates bind before effects and fences a late acknowledgement', async () => {
  const f = fixture();
  await f.controller.check();
  const pending = deferred<{ status: 'bound'; context: NativeRouteContext }>();
  f.bind.mockImplementationOnce(() => pending.promise);
  const run = f.controller.prepare();
  expect(f.controller.state().busy).toBe('preparing');
  f.coordinator.setConsent(accountId, journeyId, null, 1); // Consent Stop begins synchronously.
  expect(f.controller.state()).toMatchObject({
    observed: undefined,
    acknowledged: null,
    busy: null,
  });
  pending.resolve({ status: 'bound', context });
  await run;
  expect(f.controller.state().acknowledged).toBeNull();
  f.unsubscribe();
});

it('selection, renewal, offline and late reads invalidate without implicit recovery', async () => {
  const f = fixture();
  const pending = deferred<{ context: NativeRouteContext | null }>();
  f.read.mockImplementationOnce(() => pending.promise);
  const run = f.controller.check();
  f.coordinator.setSelection({ ...selection, alternativeIndex: 0 }, 1);
  pending.resolve({ context });
  await run;
  expect(f.controller.state().observed).toBeUndefined();
  expect(f.bind).not.toHaveBeenCalled();
  await f.controller.check();
  f.env.sessionEpoch = 2;
  await f.controller.prepare(); // Old source provenance fails even before React effects.
  expect(f.bind).not.toHaveBeenCalled();
  f.controller.sessionChanged();
  f.env.online = false;
  f.controller.environmentChanged();
  f.env.online = true;
  expect(f.read).toHaveBeenCalledTimes(2);
  expect(f.controller.state().observed).toBeUndefined();
  f.unsubscribe();
});

it('clears expectation on no-route, conflict and expiry while leaving server context untouched', async () => {
  const f = fixture();
  await f.controller.check();
  f.bind.mockResolvedValueOnce({ status: 'no_route', context: null } as never);
  await f.controller.prepare();
  expect(f.controller.state()).toMatchObject({
    observed: undefined,
    acknowledged: null,
    failure: 'no_route',
  });
  await f.controller.check();
  f.bind.mockRejectedValueOnce(new NativeRouteContextError('conflict', 409));
  await f.controller.prepare();
  expect(f.controller.state()).toMatchObject({ observed: undefined, failure: 'conflict' });
  f.read.mockResolvedValueOnce({ context });
  await f.controller.check();
  f.setTime('2026-09-24T10:10:00Z');
  f.controller.expire();
  expect(f.controller.state()).toMatchObject({
    observed: undefined,
    acknowledged: null,
    failure: 'expired',
  });
  expect(f.controller.nextExpiryMs()).toBeNull();
  f.unsubscribe();
});

it('expires a scheduled observation even if the wall clock moves backward before the timer fires', async () => {
  const f = fixture();
  f.read.mockResolvedValueOnce({ context });
  await f.controller.check();
  expect(f.controller.nextExpiryMs()).toBeGreaterThan(0);
  f.setTime('2026-09-24T09:59:00Z');
  f.controller.expireFromTimer(context.contextId, context.expiresAt);
  expect(f.controller.state()).toMatchObject({
    observed: undefined,
    acknowledged: null,
    failure: 'expired',
  });
  f.unsubscribe();
});
