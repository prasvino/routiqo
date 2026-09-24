import { expect, it, vi } from 'vitest';
import { NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import {
  createNativeConsentController,
  type NativeConsentEnvironment,
} from '../apps/mobile/src/features/live/native-consent-controller';
import type { NativeLiveConsent } from '../apps/mobile/src/features/live/native-consent';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const off = (generation = '1'): NativeLiveConsent => ({
  journeyId,
  generation,
  sharing: false,
  journeyActive: true,
});
const on = (generation = '2'): NativeLiveConsent => ({ ...off(generation), sharing: true });
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};
function fixture() {
  const environment: NativeConsentEnvironment = {
    accountId,
    journeyId,
    online: true,
    eligible: true,
    foreground: true,
    focused: true,
    sessionEpoch: 1,
  };
  const read = vi.fn(async () => off());
  const submit = vi.fn(async (input: { expectedGeneration: string; sharing: boolean }) =>
    input.sharing ? on() : off('3'),
  );
  const publish = vi.fn();
  const controller = createNativeConsentController(
    accountId,
    journeyId,
    { environment: () => environment, read, submit },
    publish,
  );
  return { environment, read, submit, publish, controller };
}

it('starts unknown, checks only explicitly, and gates Allow to an observed active off state', async () => {
  const f = fixture();
  expect(f.read).not.toHaveBeenCalled();
  await f.controller.allow();
  expect(f.submit).not.toHaveBeenCalled();
  await f.controller.check();
  expect(f.controller.state()).toMatchObject({
    confirmed: off(),
    uncertain: false,
    notice: 'checked',
  });
  await f.controller.allow();
  expect(f.submit).toHaveBeenCalledWith(
    { expectedGeneration: '1', sharing: true },
    expect.any(AbortSignal),
  );
  expect(f.controller.state()).toMatchObject({
    confirmed: on(),
    uncertain: false,
    notice: 'allowed',
  });
  await f.controller.allow();
  expect(f.submit).toHaveBeenCalledTimes(1);
});

it('permits Stop while unknown with zero, holds single flight and fences a late cancelled write', async () => {
  const f = fixture();
  const pending = deferred<NativeLiveConsent>();
  f.submit.mockImplementationOnce(() => pending.promise);
  const first = f.controller.stop();
  expect(f.submit).toHaveBeenCalledWith(
    { expectedGeneration: '0', sharing: false },
    expect.any(AbortSignal),
  );
  expect(f.controller.state()).toMatchObject({ uncertain: true, busy: true });
  await f.controller.check();
  await f.controller.stop();
  expect(f.read).not.toHaveBeenCalled();
  expect(f.submit).toHaveBeenCalledTimes(1);
  f.environment.online = false;
  f.controller.environmentChanged();
  expect(f.controller.state()).toMatchObject({ confirmed: null, uncertain: true, busy: false });
  pending.resolve(off('8'));
  await first;
  expect(f.controller.state().uncertain).toBe(true);
  f.environment.online = true;
  expect(f.read).not.toHaveBeenCalled();
  await f.controller.check();
  expect(f.controller.state()).toMatchObject({ confirmed: off(), uncertain: true });
  await f.controller.allow();
  expect(f.submit).toHaveBeenCalledTimes(1);
  await f.controller.stop();
  expect(f.controller.state()).toMatchObject({ uncertain: false, notice: 'stopped' });
});

it('keeps uncertainty through renewal, focus loss, conflict and read, then accepts only explicit Stop', async () => {
  const f = fixture();
  await f.controller.check();
  f.submit.mockRejectedValueOnce(new NativeHttpStatus(409));
  await f.controller.allow();
  expect(f.controller.state()).toMatchObject({
    uncertain: true,
    failure: 'conflict',
    confirmed: null,
  });
  f.controller.sessionChanged();
  f.environment.focused = false;
  f.controller.environmentChanged();
  f.environment.focused = true;
  await f.controller.check();
  expect(f.controller.state()).toMatchObject({ uncertain: true, confirmed: off() });
  await f.controller.stop();
  expect(f.submit).toHaveBeenLastCalledWith(
    { expectedGeneration: '1', sharing: false },
    expect.any(AbortSignal),
  );
  expect(f.controller.state().uncertain).toBe(false);
});

it('rejects MAX Allow, terminal completion, account changes and late results after disposal', async () => {
  const f = fixture();
  f.read.mockResolvedValueOnce(off('9223372036854775807'));
  await f.controller.check();
  await f.controller.allow();
  expect(f.submit).not.toHaveBeenCalled();
  f.read.mockResolvedValueOnce({ ...off('9'), journeyActive: false });
  await f.controller.check();
  expect(f.controller.state().terminal).toBe(true);
  await f.controller.allow();
  expect(f.submit).not.toHaveBeenCalled();
  const pending = deferred<NativeLiveConsent>();
  f.submit.mockImplementationOnce(() => pending.promise);
  const stop = f.controller.stop();
  const before = f.publish.mock.calls.length;
  f.environment.accountId = '00000000-0000-4000-8000-000000000003';
  f.controller.environmentChanged();
  f.controller.dispose();
  pending.reject(new Error('late'));
  await stop;
  expect(f.publish.mock.calls.length).toBe(before + 1);
});

it('invalidates binding authority at read and Stop start, and fences stale Allow after renewal', async () => {
  const f = fixture();
  const authority = vi.fn();
  const read = vi.fn(async () => on());
  const pending = deferred<NativeLiveConsent>();
  const controller = createNativeConsentController(
    accountId,
    journeyId,
    {
      environment: () => f.environment,
      onAuthorityChange: authority,
      read,
      submit: vi.fn(() => pending.promise),
    },
    vi.fn(),
  );
  await controller.check();
  expect(authority).toHaveBeenLastCalledWith('2', 1);
  const again = controller.check();
  expect(authority).toHaveBeenLastCalledWith(null, 1);
  await again;
  const stop = controller.stop();
  expect(authority).toHaveBeenLastCalledWith(null, 1);
  f.environment.sessionEpoch = 2;
  await controller.allow();
  expect(authority).not.toHaveBeenLastCalledWith('2', 2);
  controller.sessionChanged();
  pending.resolve(off('3'));
  await stop;
  expect(controller.state().confirmed).toBeNull();
});
