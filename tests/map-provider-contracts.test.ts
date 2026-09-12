import { expect, it } from 'vitest';
import { readRouteResult } from '../packages/shared/src/routing';
import { readPlaceResults } from '../packages/shared/src/place-search';

const route = {
  distanceMetres: 1000,
  durationSeconds: 300,
  geometry: [
    [80, 13],
    [80.01, 13.01],
  ],
};
const routing = { calculatedAt: '2026-09-12T00:00:00Z', routes: [route] };
const place = { id: 'osm:node:123', label: 'Synthetic station', coordinate: [80, 13] };
const search = { places: [place], attribution: '© OpenStreetMap contributors' };

it('preserves supported routing and search identities without retaining provider extras', () => {
  for (const provider of ['mapbox', 'valhalla']) {
    const result = readRouteResult({ ...routing, provider, privateMetadata: 'discard' });
    expect(result.provider).toBe(provider);
    expect(result.routes[0]?.geometry).toEqual(route.geometry);
    expect(result).not.toHaveProperty('privateMetadata');
  }
  for (const provider of ['mapbox', 'photon']) {
    expect(readPlaceResults({ ...search, provider, extra: 'discard' })).toEqual({
      ...search,
      provider,
    });
  }
});

it('rejects unknown and cross-purpose identities instead of relabeling them as Mapbox', () => {
  for (const provider of ['photon', 'Mapbox', 'https://private.example', null, {}]) {
    expect(() => readRouteResult({ ...routing, provider })).toThrow('Routing response is invalid.');
  }
  for (const provider of ['valhalla', 'Photon', 'https://private.example', null, {}]) {
    expect(() => readPlaceResults({ ...search, provider })).toThrow(
      'Place results are unavailable.',
    );
  }
});

it('keeps bounds and geometry validation for the new providers', () => {
  expect(() =>
    readRouteResult({ ...routing, provider: 'valhalla', routes: Array(4).fill(route) }),
  ).toThrow();
  expect(() =>
    readRouteResult({
      ...routing,
      provider: 'valhalla',
      routes: [{ ...route, durationSeconds: -1 }],
    }),
  ).toThrow();
  expect(() =>
    readRouteResult({
      ...routing,
      provider: 'valhalla',
      routes: [
        {
          ...route,
          geometry: [
            [181, 13],
            [80, 13],
          ],
        },
      ],
    }),
  ).toThrow();
  expect(() =>
    readPlaceResults({ ...search, provider: 'photon', places: [place, place] }),
  ).toThrow();
  expect(() =>
    readPlaceResults({ ...search, provider: 'photon', places: Array(6).fill(place) }),
  ).toThrow();
  expect(() =>
    readPlaceResults({
      ...search,
      provider: 'photon',
      places: [{ ...place, coordinate: [80, 91] }],
    }),
  ).toThrow();
});
