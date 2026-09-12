import { expect, it } from 'vitest';
import { readMapboxRoutes, readRouteRequest, readRouteResult } from './routing';
it('rejects impossible estimate timestamps while retaining Java nanosecond precision', () => {
  const input = { provider: 'mapbox', calculatedAt: '2026-09-09T12:00:00.123456789Z', routes: [] };
  expect(readRouteResult(input)).toEqual(input);
  for (const calculatedAt of [
    '2026-02-30T12:00:00Z',
    '0000-01-01T00:00:00Z',
    '2026-09-09T24:00:00Z',
  ])
    expect(() => readRouteResult({ ...input, calculatedAt })).toThrow();
});
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
  const step = {
    distance: 1200,
    duration: 600,
    maneuver: { instruction: 'Continue south', location: [80, 13] },
  };
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
    legs: [{ steps: [step] }],
    privateMetadata: 'discard',
  };
  expect(readMapboxRoutes({ code: 'Ok', routes: [route] })[0]).toEqual({
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
  });
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

it('normalizes compatible route results with missing or explicit steps', () => {
  const route = {
    distanceMetres: 10,
    durationSeconds: 5,
    geometry: [
      [80, 13],
      [79, 12],
    ],
  };
  const input = { provider: 'mapbox', calculatedAt: '2026-09-09T12:00:00Z', routes: [route] };
  expect(readRouteResult(input).routes[0]?.steps).toEqual([]);
  expect(readRouteResult({ ...input, routes: [{ ...route, steps: [] }] }).routes[0]?.steps).toEqual(
    [],
  );
  expect(
    readRouteResult({
      ...input,
      routes: [
        {
          ...route,
          steps: [
            {
              instruction: 'Turn right',
              distanceMetres: 10,
              durationSeconds: 5,
              location: [79, 12],
              providerExtra: 'discard',
            },
          ],
        },
      ],
    }).routes[0]?.steps,
  ).toEqual([
    {
      instruction: 'Turn right',
      distanceMetres: 10,
      durationSeconds: 5,
      location: [79, 12],
    },
  ]);
});

it('rejects malformed normalized and provider steps', () => {
  const geometry = [
    [80, 13],
    [79, 12],
  ];
  const normalized = {
    provider: 'mapbox',
    calculatedAt: '2026-09-09T12:00:00Z',
    routes: [{ distanceMetres: 10, durationSeconds: 5, geometry }],
  };
  for (const steps of [
    null,
    [{ instruction: ' Turn right', distanceMetres: 10, durationSeconds: 5, location: [79, 12] }],
    [{ instruction: 'Turn\nright', distanceMetres: 10, durationSeconds: 5, location: [79, 12] }],
    [{ instruction: 'Turn right', distanceMetres: -1, durationSeconds: 5, location: [79, 12] }],
    Array.from({ length: 501 }, () => ({
      instruction: 'Continue',
      distanceMetres: 1,
      durationSeconds: 1,
      location: [79, 12],
    })),
  ])
    expect(() =>
      readRouteResult({ ...normalized, routes: [{ ...normalized.routes[0], steps }] }),
    ).toThrow();

  const route = {
    distance: 10,
    duration: 5,
    geometry: { type: 'LineString', coordinates: geometry },
  };
  const providerStep = {
    distance: 10,
    duration: 5,
    maneuver: { instruction: 'Turn right', location: [79, 12] },
  };
  for (const legs of [
    undefined,
    [],
    [{ steps: [] }],
    [{ steps: [providerStep] }, { steps: [providerStep] }],
    [{ steps: Array.from({ length: 501 }, () => providerStep) }],
    [{ steps: [{ ...providerStep, maneuver: { location: [79, 12] } }] }],
  ])
    expect(() => readMapboxRoutes({ code: 'Ok', routes: [{ ...route, legs }] })).toThrow();
});
