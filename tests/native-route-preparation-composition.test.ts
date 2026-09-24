import { expect, it, vi } from 'vitest';
import type { PlaceResults, RouteResult } from '@routiqo/shared';
import { createNativeConsentController } from '../apps/mobile/src/features/live/native-consent-controller';
import { createNativeRoutingController } from '../apps/mobile/src/features/journey/native-routing-controller';
import { createNativeRoutePreparationCoordinator } from '../apps/mobile/src/features/live/native-route-preparation-coordinator';
import { createNativeRoutePreparationController } from '../apps/mobile/src/features/live/native-route-preparation-controller';
import type { NativeRouteContext } from '../apps/mobile/src/features/live/native-route-context';

const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const accountId = id(1),
  journeyId = id(2);
const route: RouteResult = {
  provider: 'valhalla',
  calculatedAt: '2026-09-24T10:00:00Z',
  routes: [
    {
      distanceMetres: 1000,
      durationSeconds: 600,
      geometry: [
        [80, 13],
        [81, 13],
      ],
      steps: [],
    },
    {
      distanceMetres: 1200,
      durationSeconds: 700,
      geometry: [
        [80, 13],
        [81, 13],
      ],
      steps: [],
    },
  ],
};
const context: NativeRouteContext = {
  contextId: id(3),
  revision: '1',
  anchorIds: [id(4)],
  issuedAt: '2026-09-24T10:00:00Z',
  expiresAt: '2026-09-24T10:10:00Z',
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
function fixture() {
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
  const search = vi.fn(async (query: string): Promise<PlaceResults> => ({
    provider: 'photon',
    attribution: '© OpenStreetMap contributors',
    places: [{ id: query, label: query, coordinate: query === 'Chennai' ? [80, 13] : [81, 13] }],
  }));
  const calculate = vi.fn(async () => route);
  const routing = createNativeRoutingController(
    accountId,
    {
      environment: () => env,
      onSelectionChange: (value, epoch) => coordinator.setSelection(value, epoch),
      search,
      calculate,
    },
    vi.fn(),
  );
  const submit = vi.fn(async (input: { expectedGeneration: string; sharing: boolean }) => ({
    journeyId,
    generation: input.sharing ? '3' : '3',
    sharing: input.sharing,
    journeyActive: true,
  }));
  const consent = createNativeConsentController(
    accountId,
    journeyId,
    {
      environment: () => env,
      onAuthorityChange: (generation, epoch) =>
        coordinator.setConsent(accountId, journeyId, generation, epoch),
      read: async () => ({ journeyId, generation: '2', sharing: true, journeyActive: true }),
      submit,
    },
    vi.fn(),
  );
  const bind = vi.fn(async () => ({ status: 'bound' as const, context }));
  const preparation = createNativeRoutePreparationController(
    accountId,
    journeyId,
    {
      environment: () => env,
      consent: () => coordinator.consent(accountId, journeyId),
      selection: () => coordinator.selection(),
      read: async () => ({ context: null }),
      bind,
      now: () => Date.parse('2026-09-24T10:01:00Z'),
    },
    vi.fn(),
  );
  const unsubscribe = coordinator.subscribe(() => preparation.authorityChanged());
  return { env, coordinator, routing, consent, preparation, bind, submit, calculate, unsubscribe };
}
async function selectRoute(f: ReturnType<typeof fixture>) {
  f.routing.edit('origin', 'Chennai');
  await f.routing.search('origin');
  f.routing.select('origin', 'Chennai');
  f.routing.edit('destination', 'Pondicherry');
  await f.routing.search('destination');
  f.routing.select('destination', 'Pondicherry');
  await f.routing.calculate();
}

it('requires actual consent and successful route callbacks; pending Stop cancels a bind synchronously', async () => {
  const f = fixture();
  await f.preparation.check();
  expect(f.preparation.state().observed).toBeUndefined();
  await f.consent.check();
  expect(f.coordinator.consent(accountId, journeyId)?.generation).toBe('2');
  await selectRoute(f);
  expect(f.coordinator.selection().selection?.alternativeIndex).toBe(0);
  await f.preparation.check();
  expect(f.preparation.state().observed).toBeNull();
  const pending = deferred<{ status: 'bound'; context: NativeRouteContext }>();
  f.bind.mockImplementationOnce(() => pending.promise);
  const started = f.preparation.prepare();
  const stop = f.consent.stop();
  expect(f.preparation.state()).toMatchObject({
    observed: undefined,
    acknowledged: null,
    busy: null,
  });
  pending.resolve({ status: 'bound', context });
  await started;
  await stop;
  expect(f.preparation.state().acknowledged).toBeNull();
  expect(f.bind).toHaveBeenCalledTimes(1);
  f.unsubscribe();
});

it('route edit, alternative and recalculation invalidate preparation before network results', async () => {
  const f = fixture();
  await f.consent.check();
  await selectRoute(f);
  await f.preparation.check();
  f.routing.alternative(1);
  expect(f.preparation.state().observed).toBeUndefined();
  await f.preparation.check();
  f.routing.edit('origin', 'Changed');
  expect(f.preparation.state().observed).toBeUndefined();
  await selectRoute(f);
  await f.preparation.check();
  const pending = deferred<RouteResult>();
  f.calculate.mockImplementationOnce(() => pending.promise);
  const calculating = f.routing.calculate();
  expect(f.preparation.state().observed).toBeUndefined();
  expect(f.routing.state().route).toEqual(route);
  pending.resolve(route);
  await calculating;
  expect(f.coordinator.selection().selection).not.toBeNull();
  expect(f.preparation.state().observed).toBeUndefined();
  f.unsubscribe();
});

it('renewal before effects cannot reuse old consent or republish an alternative', async () => {
  const f = fixture();
  await f.consent.check();
  await selectRoute(f);
  f.env.sessionEpoch = 2;
  f.routing.alternative(1);
  expect(f.coordinator.selection().selection).toBeNull();
  await f.preparation.check();
  expect(f.preparation.state().observed).toBeUndefined();
  // An old off confirmation cannot enable consent in the new session before effects clear it.
  f.unsubscribe();
});
