import { expect, it } from 'vitest';
import { readMapboxRoutes, readRouteRequest } from './routing';
it('validates coordinates and rejects identical endpoints without inventing routes', () => {
  const input = { mode: 'driving', origin: [80, 13], destination: [79, 12] };
  expect(readRouteRequest(input)).toEqual(input);
  for (const invalid of [
    { ...input, origin: [181, 13] },
    { ...input, destination: input.origin },
    { ...input, mode: 'flying' },
    { ...input, destination: [Infinity, 12] },
  ])
    expect(() => readRouteRequest(invalid)).toThrow();
});
it('distinguishes no route from provider failure and validates every geometry point', () => {
  expect(readMapboxRoutes({ code: 'NoRoute' })).toEqual([]);
  expect(() => readMapboxRoutes({ code: 'InvalidInput' })).toThrow();
  const route = {
    distance: 1200,
    duration: 600,
    geometry: {
      type: 'LineString',
      coordinates: [
        [80, 13],
        [79, 12],
      ],
    },
    privateMetadata: 'discard',
  };
  expect(readMapboxRoutes({ code: 'Ok', routes: [route] })[0]).not.toHaveProperty(
    'privateMetadata',
  );
  expect(() => readMapboxRoutes({ code: 'Ok', routes: [{ ...route, duration: -1 }] })).toThrow();
  expect(() =>
    readMapboxRoutes({
      code: 'Ok',
      routes: [
        {
          ...route,
          geometry: {
            type: 'LineString',
            coordinates: [
              [80, 13],
              [79, 100],
            ],
          },
        },
      ],
    }),
  ).toThrow();
});
