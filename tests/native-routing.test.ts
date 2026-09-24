import { afterEach, expect, it, vi } from 'vitest';
import type { RouteRequest } from '../packages/shared/src/routing';
import type { createNativeAccount } from '../apps/mobile/src/auth/native-account';
import { createNativeTransport } from '../apps/mobile/src/auth/safe-transport';
import {
  calculateNativeRoute,
  NativeRoutingError,
  searchNativePlaces,
} from '../apps/mobile/src/features/journey/native-routing';

const accountId = '00000000-0000-4000-8000-000000000001';
const credential = 'A'.repeat(43);
const routePath = '/api/v1/native/routes';
const placePath = '/api/v1/native/routes/places';
const requestInput: RouteRequest = { mode: 'driving', origin: [80, 13], destination: [79, 12] };
const routeResult = {
  provider: 'mapbox',
  calculatedAt: '2026-09-09T12:00:00Z',
  routes: [
    {
      distanceMetres: 1200,
      durationSeconds: 600,
      geometry: [
        [80, 13],
        [79, 12],
      ],
      steps: [
        {
          instruction: 'Continue south',
          distanceMetres: 1200,
          durationSeconds: 600,
          location: [80, 13],
        },
      ],
    },
  ],
};
const placeResults = {
  provider: 'photon',
  attribution: 'Synthetic attribution',
  places: [{ id: 'synthetic-place', label: 'Synthetic town', coordinate: [80, 13] }],
};
type Account = ReturnType<typeof createNativeAccount>;
function actor(
  verifiedRequest: Account['verifiedRequest'],
  revision = () => 1,
  activeAccount = () => accountId,
): Account {
  return { verifiedRequest, revision, activeAccount } as unknown as Account;
}
afterEach(() => vi.useRealTimers());

it('permits only exact POST paths and exact 200 JSON within separate body limits', async () => {
  const driver = vi.fn(async (_origin: string, path: string) => ({
    status: 200,
    body: JSON.stringify(path === routePath ? routeResult : placeResults),
  }));
  const transport = createNativeTransport({ request: driver }, 'https://staging.routiqo.example');
  expect(
    await transport.request(routePath, 'POST', { credential, accountId, body: requestInput }),
  ).toEqual(routeResult);
  expect(
    await transport.request(placePath, 'POST', {
      credential,
      accountId,
      body: { query: 'Chennai' },
    }),
  ).toEqual(placeResults);
  expect(driver).toHaveBeenNthCalledWith(
    1,
    'https://staging.routiqo.example',
    routePath,
    'POST',
    credential,
    accountId,
    JSON.stringify(requestInput),
  );
  for (const invalid of [routePath + '?x=1', placePath + '\n', routePath + '/extra'])
    await expect(
      transport.request(invalid, 'POST', { credential, accountId, body: {} }),
    ).rejects.toThrow();
  for (const path of [routePath, placePath])
    await expect(transport.request(path, 'GET', { credential, accountId })).rejects.toThrow();
  expect(driver).toHaveBeenCalledTimes(2);
  for (const [path, result, limit] of [
    [routePath, routeResult, 1024 * 1024],
    [placePath, placeResults, 256 * 1024],
  ] as const) {
    const base = JSON.stringify(result);
    const bounded = createNativeTransport(
      { request: async () => ({ status: 200, body: base + ' '.repeat(limit - base.length) }) },
      'https://staging.routiqo.example',
    );
    await expect(
      bounded.request(path, 'POST', { credential, accountId, body: {} }),
    ).resolves.toEqual(result);
    const oversized = createNativeTransport(
      { request: async () => ({ status: 200, body: base + ' '.repeat(limit + 1 - base.length) }) },
      'https://staging.routiqo.example',
    );
    await expect(
      oversized.request(path, 'POST', { credential, accountId, body: {} }),
    ).rejects.toThrow('Native server response is invalid.');
  }
  for (const status of [201, 204]) {
    const wrong = createNativeTransport(
      { request: async () => ({ status, body: JSON.stringify(routeResult) }) },
      'https://staging.routiqo.example',
    );
    await expect(
      wrong.request(routePath, 'POST', { credential, accountId, body: {} }),
    ).rejects.toThrow('Native server response is invalid.');
  }
  const malformed = createNativeTransport(
    { request: async () => ({ status: 200, body: '\ud800' }) },
    'https://staging.routiqo.example',
  );
  await expect(
    malformed.request(placePath, 'POST', { credential, accountId, body: {} }),
  ).rejects.toThrow('Native server response is invalid.');
});

it('copies validated route endpoints before awaits and strips provider extras', async () => {
  let finish!: (value: unknown) => void;
  const request = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const input: RouteRequest = { mode: 'driving', origin: [80, 13], destination: [79, 12] };
  const pending = calculateNativeRoute(actor(request), accountId, input);
  input.origin[0] = 70;
  input.destination[1] = 11;
  input.mode = 'walking';
  expect(request).toHaveBeenCalledWith(routePath, 'POST', {
    accountId,
    body: requestInput,
    signal: expect.any(AbortSignal),
  });
  finish({ ...routeResult, providerSecret: 'discard' });
  await expect(pending).resolves.toEqual(routeResult);
  const places = actor(vi.fn(async () => ({ ...placeResults, providerSecret: 'discard' })));
  await expect(searchNativePlaces(places, accountId, '  Chennai  ')).resolves.toEqual(placeResults);
  expect(places.verifiedRequest).toHaveBeenCalledWith(placePath, 'POST', {
    accountId,
    body: { query: 'Chennai' },
    signal: expect.any(AbortSignal),
  });
});

it('rejects invalid account, route, query and normalized response safely', async () => {
  const request = vi.fn(async () => routeResult);
  const identity = actor(request);
  for (const account of [accountId + '\n', '00000000-0000-0000-0000-000000000000'])
    await expect(calculateNativeRoute(identity, account, requestInput)).rejects.toMatchObject({
      code: 'invalid',
    });
  for (const input of [
    { ...requestInput, origin: [181, 13] },
    { ...requestInput, destination: requestInput.origin },
    { ...requestInput, provider: 'override' },
  ])
    expect(() => calculateNativeRoute(identity, accountId, input as RouteRequest)).toThrow(
      NativeRoutingError,
    );
  for (const query of ['ab', 'town\nstreet', 'x'.repeat(257)])
    expect(() => searchNativePlaces(identity, accountId, query)).toThrow(NativeRoutingError);
  expect(request).not.toHaveBeenCalled();
  const invalidResult = actor(
    vi.fn(async () => ({
      ...routeResult,
      routes: [
        {
          ...routeResult.routes[0],
          geometry: [
            [181, 13],
            [79, 12],
          ],
        },
      ],
    })),
  );
  await expect(calculateNativeRoute(invalidResult, accountId, requestInput)).rejects.toMatchObject({
    code: 'unavailable',
  });
});

it('maps HTTP status and raw failures without disclosing response details', async () => {
  for (const [status, code] of [
    [400, 'invalid'],
    [401, 'session'],
    [403, 'forbidden'],
    [429, 'rate-limited'],
    [422, 'coverage'],
    [503, 'unavailable'],
  ] as const) {
    const identity = actor(
      vi.fn(async () => {
        throw new (await import('../apps/mobile/src/auth/safe-transport')).NativeHttpStatus(status);
      }),
    );
    await expect(searchNativePlaces(identity, accountId, 'Chennai')).rejects.toMatchObject({
      code,
      status,
    });
  }
  const failure = actor(
    vi.fn(async () => {
      throw new Error('private provider detail');
    }),
  );
  await expect(searchNativePlaces(failure, accountId, 'Chennai')).rejects.toMatchObject({
    code: 'unavailable',
    message: 'Route planning is unavailable. Try again.',
  });
});

it('fences abort, account revision and an abort-ignoring late result', async () => {
  let finish!: (value: unknown) => void;
  let revision = 1;
  const request = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const identity = actor(request, () => revision);
  const cancellation = new AbortController();
  const cancelled = calculateNativeRoute(identity, accountId, requestInput, cancellation.signal);
  cancellation.abort();
  await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
  finish(routeResult);
  const stale = searchNativePlaces(identity, accountId, 'Chennai');
  revision++;
  finish(placeResults);
  await expect(stale).rejects.toMatchObject({ code: 'session' });
  const prior = new AbortController();
  prior.abort();
  await expect(
    searchNativePlaces(identity, accountId, 'Chennai', prior.signal),
  ).rejects.toMatchObject({ name: 'AbortError' });
  expect(request).toHaveBeenCalledTimes(2);
});

it('times out credential or bridge stalls after one eighteen-second deadline', async () => {
  vi.useFakeTimers();
  let finish!: (value: unknown) => void;
  const request = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const pending = calculateNativeRoute(actor(request), accountId, requestInput);
  const assertion = expect(pending).rejects.toMatchObject({ code: 'timeout' });
  await vi.advanceTimersByTimeAsync(18_000);
  await assertion;
  finish(routeResult);
  expect(request).toHaveBeenCalledTimes(1);
});
