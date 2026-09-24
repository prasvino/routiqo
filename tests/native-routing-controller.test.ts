import { expect, it, vi } from 'vitest';
import type { PlaceResults, RouteResult } from '@routiqo/shared';
import { NativeRoutingError } from '../apps/mobile/src/features/journey/native-routing';
import {
  createNativeRoutingController,
  type NativeRoutingEnvironment,
} from '../apps/mobile/src/features/journey/native-routing-controller';

const places = (label: string, longitude: number): PlaceResults => ({
  provider: 'photon',
  attribution: '© OpenStreetMap contributors',
  places: [{ id: label, label, coordinate: [longitude, 13] }],
});
const route: RouteResult = {
  provider: 'valhalla',
  calculatedAt: '2026-09-24T06:00:00Z',
  routes: [
    {
      distanceMetres: 1000,
      durationSeconds: 600,
      geometry: [
        [80, 13],
        [81, 13],
      ],
      steps: [
        { instruction: 'Head east', distanceMetres: 400, durationSeconds: 240, location: [80, 13] },
        { instruction: 'Continue', distanceMetres: 600, durationSeconds: 360, location: [81, 13] },
      ],
    },
    {
      distanceMetres: 1200,
      durationSeconds: 720,
      geometry: [
        [80, 13],
        [81, 13],
      ],
      steps: [],
    },
  ],
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
  const accountId = '00000000-0000-4000-8000-000000000001';
  const environment: NativeRoutingEnvironment = {
    accountId,
    online: true,
    eligible: true,
    foreground: true,
    focused: true,
    sessionEpoch: 1,
  };
  const search = vi.fn(async (query: string) =>
    query === 'Chennai' ? places('Chennai', 80) : places('Pondicherry', 81),
  );
  const calculate = vi.fn(async () => route);
  const publish = vi.fn();
  const controller = createNativeRoutingController(
    accountId,
    { environment: () => environment, search, calculate },
    publish,
  );
  return { accountId, environment, search, calculate, publish, controller };
}
async function selectBoth(f: ReturnType<typeof fixture>) {
  f.controller.edit('origin', 'Chennai');
  await f.controller.search('origin');
  f.controller.select('origin', 'Chennai');
  f.controller.edit('destination', 'Pondicherry');
  await f.controller.search('destination');
  f.controller.select('destination', 'Pondicherry');
}

it('does no implicit traffic and requires explicit search, selection and calculate', async () => {
  const f = fixture();
  expect(f.search).not.toHaveBeenCalled();
  expect(f.calculate).not.toHaveBeenCalled();
  await f.controller.calculate();
  f.controller.swap();
  expect(f.calculate).not.toHaveBeenCalled();
  expect(f.controller.state().origin.text).toBe('');
  await selectBoth(f);
  expect(f.controller.state().origin.attribution).toContain('OpenStreetMap');
  expect(f.controller.state().origin.results).toBeNull();
  await f.controller.calculate();
  expect(f.calculate).toHaveBeenCalledWith(
    { mode: 'driving', origin: [80, 13], destination: [81, 13] },
    expect.any(AbortSignal),
  );
  expect(f.controller.state().route).toEqual(route);
  f.controller.step(1);
  f.controller.alternative(0);
  expect(f.controller.state().step).toBe(1);
  f.controller.alternative(1);
  expect(f.controller.state().step).toBe(0);
  f.controller.swap();
  expect(f.controller.state()).toMatchObject({
    origin: { text: 'Pondicherry' },
    destination: { text: 'Chennai' },
    route: null,
  });
});

it('keeps loaded directions through same-input failure, offline, focus and renewal', async () => {
  const f = fixture();
  await selectBoth(f);
  await f.controller.calculate();
  f.controller.step(1);
  f.calculate.mockRejectedValueOnce(new NativeRoutingError('coverage', 422));
  await f.controller.calculate();
  expect(f.controller.state()).toMatchObject({ route, step: 1, failure: 'coverage' });
  f.environment.online = false;
  f.controller.environmentChanged();
  await f.controller.calculate();
  expect(f.calculate).toHaveBeenCalledTimes(2);
  expect(f.controller.state().route).toEqual(route);
  f.environment.online = true;
  f.environment.focused = false;
  f.controller.suspend();
  f.environment.focused = true;
  f.environment.sessionEpoch++;
  f.controller.sessionChanged();
  expect(f.controller.state().route).toEqual(route);
  expect(f.calculate).toHaveBeenCalledTimes(2);
});

it('edits cancel pending search and late results cannot repopulate old matches', async () => {
  const f = fixture();
  const pending = deferred<PlaceResults>();
  f.search.mockImplementationOnce(() => pending.promise);
  f.controller.edit('origin', 'Chennai');
  const started = f.controller.search('origin');
  await f.controller.search('destination');
  expect(f.search).toHaveBeenCalledTimes(1);
  f.controller.edit('origin', 'New text');
  pending.resolve(places('Chennai', 80));
  await started;
  expect(f.controller.state()).toMatchObject({
    origin: { text: 'New text', selected: null, results: null },
    busy: null,
  });
  expect(f.publish.mock.lastCall?.[0].origin.text).toBe('New text');
});

it('fences route response on session epoch change, account change and disposal', async () => {
  const f = fixture();
  await selectBoth(f);
  const first = deferred<RouteResult>();
  f.calculate.mockImplementationOnce(() => first.promise);
  const started = f.controller.calculate();
  f.environment.sessionEpoch++;
  f.controller.sessionChanged();
  first.resolve(route);
  await started;
  expect(f.controller.state().route).toBeNull();
  const second = deferred<RouteResult>();
  f.calculate.mockImplementationOnce(() => second.promise);
  const again = f.controller.calculate();
  f.environment.accountId = '00000000-0000-4000-8000-000000000002';
  f.controller.environmentChanged();
  const calls = f.publish.mock.calls.length;
  f.controller.dispose();
  second.reject(new Error('late'));
  await again;
  expect(f.publish.mock.calls.length).toBe(calls);
});

it('validates query locally and treats an empty validated route as no-route', async () => {
  const f = fixture();
  f.controller.edit('origin', 'a');
  await f.controller.search('origin');
  expect(f.controller.state().failure).toBe('invalid_query');
  expect(f.search).not.toHaveBeenCalled();
  await selectBoth(f);
  f.calculate.mockResolvedValueOnce({ ...route, routes: [] });
  await f.controller.calculate();
  expect(f.controller.state()).toMatchObject({ noRoute: true, route: null });
  f.controller.mode('walking');
  expect(f.controller.state()).toMatchObject({ mode: 'walking', noRoute: false, route: null });
});
