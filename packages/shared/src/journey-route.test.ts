import { describe, expect, it } from 'vitest';
import {
  buildJourneyRoute,
  cumulativeRouteMetres,
  haversineMetres,
  projectOntoRoute,
  readJourneyRoute,
  simplifyJourneyGeometry,
} from './journey-route';
import type { RouteCoordinate } from './routing';

const journeyId = '00000000-0000-4000-8000-000000000003';
/** Points due north along a meridian, about 111 m apart. */
const north = (count: number, longitude = 80): RouteCoordinate[] =>
  Array.from({ length: count }, (_, index) => [longitude, 12 + index * 0.001]);

describe('simplifyJourneyGeometry', () => {
  it('drops collinear points and keeps the endpoints', () => {
    const line = north(50);
    const simplified = simplifyJourneyGeometry(line);
    expect(simplified).toEqual([line[0], line[49]]);
  });

  it('keeps a corner larger than the tolerance', () => {
    const corner: RouteCoordinate[] = [
      [80, 12],
      [80, 12.01],
      [80.01, 12.01],
    ];
    expect(simplifyJourneyGeometry(corner)).toEqual(corner);
  });

  it('tightens to the point budget while keeping endpoints', () => {
    const zigzag: RouteCoordinate[] = Array.from({ length: 5001 }, (_, index) => [
      80 + (index % 2) * 0.001,
      12 + index * 0.0005,
    ]);
    const simplified = simplifyJourneyGeometry(zigzag, 100);
    expect(simplified.length).toBeLessThanOrEqual(100);
    expect(simplified[0]).toEqual(zigzag[0]);
    expect(simplified.at(-1)).toEqual(zigzag.at(-1));
  });

  it('rejects invalid input', () => {
    expect(() => simplifyJourneyGeometry([[80, 12]])).toThrow();
    expect(() =>
      simplifyJourneyGeometry([
        [80, 12],
        [200, 12],
      ]),
    ).toThrow();
  });
});

describe('projectOntoRoute', () => {
  const line = north(11); // about 1.11 km
  const measures = cumulativeRouteMetres(line);

  it('measures cumulative distance', () => {
    expect(measures[0]).toBe(0);
    expect(measures[10]).toBeCloseTo(haversineMetres(line[0], line[10]), 3);
  });

  it('projects onto a segment interior, not only vertices', () => {
    const projection = projectOntoRoute(line, [80.0005, 12.0055], measures);
    expect(projection.alongMetres).toBeCloseTo(haversineMetres(line[0], [80, 12.0055]), -1);
    expect(projection.offsetMetres).toBeGreaterThan(50);
    expect(projection.offsetMetres).toBeLessThan(60);
    expect(projection.offRoute).toBe(false);
  });

  it('clamps before the start and after the end', () => {
    expect(projectOntoRoute(line, [80, 11.999], measures).alongMetres).toBe(0);
    expect(projectOntoRoute(line, [80, 12.02], measures).alongMetres).toBeCloseTo(measures[10], 3);
  });

  it('flags positions more than 1 km from the route', () => {
    expect(projectOntoRoute(line, [80.02, 12.005], measures).offRoute).toBe(true);
    expect(projectOntoRoute(line, [80.005, 12.005], measures).offRoute).toBe(false);
  });
});

describe('journey route record', () => {
  const source = {
    journeyId,
    mode: 'driving' as const,
    originLabel: '  Kilambakkam bus terminus ',
    destinationLabel: 'Trichy',
    origin: [80.08, 12.87] as RouteCoordinate,
    destination: [78.7, 10.8] as RouteCoordinate,
    alternativeIndex: 1,
    calculatedAt: '2026-09-25T10:00:00Z',
    route: { distanceMetres: 5500, durationSeconds: 600, geometry: north(50) },
  };

  it('builds a validated, simplified record', () => {
    const record = buildJourneyRoute(source);
    expect(record.version).toBe(1);
    expect(record.originLabel).toBe('Kilambakkam bus terminus');
    expect(record.geometry).toHaveLength(2);
    expect(readJourneyRoute(JSON.parse(JSON.stringify(record)))).toEqual(record);
  });

  it('rejects malformed records', () => {
    const record = buildJourneyRoute(source);
    for (const broken of [
      { ...record, version: 2 },
      { ...record, journeyId: 'nope' },
      { ...record, mode: 'flying' },
      { ...record, alternativeIndex: 3 },
      { ...record, originLabel: '   ' },
      { ...record, destinationLabel: 'a\u0000b' },
      { ...record, calculatedAt: 'yesterday' },
      { ...record, geometry: [record.geometry[0]] },
      { ...record, distanceMetres: -1 },
      null,
    ])
      expect(() => readJourneyRoute(broken)).toThrow();
  });

  it('truncates long labels', () => {
    const record = buildJourneyRoute({ ...source, destinationLabel: 'x'.repeat(300) });
    expect(record.destinationLabel).toHaveLength(120);
  });
});
