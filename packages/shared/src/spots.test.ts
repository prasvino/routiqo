import { describe, expect, it } from 'vitest';
import type { JourneyRoute } from './journey-route';
import type { RouteCoordinate } from './routing';
import {
  activityRequestIds,
  catalogVersionFromEtag,
  matchSpotsToRoute,
  readSpotActivity,
  readSpotCatalog,
  spotDistanceLabel,
  spotsAhead,
  updatedAgoLabel,
  type Spot,
  type SpotCatalog,
} from './spots';

const R = 6_371_008.8;
const degreesNorth = (metres: number) => (metres / R) * (180 / Math.PI);
const degreesEast = (metres: number, latitude: number) =>
  degreesNorth(metres) / Math.cos((latitude * Math.PI) / 180);
const version = '00000000-0000-4000-8000-0000000000aa';
const etag = `"${version}"`;
const spotId = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;

/** About 22 km due north along longitude 80, with a vertex in the middle. */
const route: JourneyRoute = {
  version: 1,
  journeyId: '00000000-0000-4000-8000-000000000003',
  mode: 'driving',
  originLabel: 'Start',
  destinationLabel: 'End',
  origin: [80, 12],
  destination: [80, 12.2],
  alternativeIndex: 0,
  distanceMetres: 22_000,
  durationSeconds: 1200,
  calculatedAt: '2026-11-05T06:00:00Z',
  geometry: [
    [80, 12],
    [80, 12.1],
    [80, 12.2],
  ],
};

function spot(n: number, coordinate: RouteCoordinate, kind: Spot['kind'] = 'toll'): Spot {
  return {
    id: spotId(n),
    name: `Spot ${n}`,
    nameTa: 'இடம்',
    kind,
    coordinate,
    district: 'chengalpattu',
    corridors: ['gst-trunk'],
    categories: ['traffic'],
  };
}
const catalogOf = (spots: Spot[]): SpotCatalog => ({
  version,
  corridors: [{ id: 'gst-trunk', name: 'GST Road' }],
  spots,
});

describe('matchSpotsToRoute', () => {
  it('matches by distance to segments at the 100 m boundary, not to vertices', () => {
    const latitude = 12.05; // mid-segment, 5.5 km from the nearest vertex
    const matched = matchSpotsToRoute(
      catalogOf([
        spot(1, [80 + degreesEast(99, latitude), latitude]),
        spot(2, [80 - degreesEast(101, latitude), latitude]),
      ]),
      route,
    );
    expect(matched.map((entry) => entry.spot.id)).toEqual([spotId(1)]);
    expect(matched[0]?.alongMetres).toBeCloseTo(5559.7, -1);
  });

  it('excludes Spots within 1,000 m of either endpoint except bus stands', () => {
    const matched = matchSpotsToRoute(
      catalogOf([
        spot(1, [80, 12 + degreesNorth(999)]),
        spot(2, [80, 12 + degreesNorth(1001)]),
        spot(3, [80, 12.2 - degreesNorth(999)]),
        spot(4, [80, 12 + degreesNorth(300)], 'bus_stand'),
        spot(5, [80, 12.2], 'bus_stand'),
      ]),
      route,
    );
    expect(matched.map((entry) => entry.spot.id)).toEqual([spotId(4), spotId(2), spotId(5)]);
  });

  it('orders along the route and re-matches when the catalog is replaced', () => {
    const first = catalogOf([spot(1, [80, 12.15]), spot(2, [80, 12.05])]);
    expect(matchSpotsToRoute(first, route).map((entry) => entry.spot.id)).toEqual([
      spotId(2),
      spotId(1),
    ]);
    const replaced = { ...catalogOf([spot(3, [80, 12.12])]), version: spotId(99) };
    expect(matchSpotsToRoute(replaced, route).map((entry) => entry.spot.id)).toEqual([spotId(3)]);
  });
});

describe('spotsAhead', () => {
  const matched = matchSpotsToRoute(
    catalogOf(
      Array.from({ length: 30 }, (_, index) => spot(index + 1, [80, 12.02 + index * 0.005])),
    ),
    route,
  );

  it('orders from the route start without a position and stops at 20', () => {
    const ahead = spotsAhead(matched, 0);
    expect(ahead).toHaveLength(20);
    expect(ahead[0]?.spot.id).toBe(spotId(1));
    expect(
      ahead.every(
        (entry, index) => index === 0 || entry.aheadMetres >= ahead[index - 1]!.aheadMetres,
      ),
    ).toBe(true);
  });

  it('keeps a Spot as "Here" until 200 m behind, then drops it', () => {
    const first = matched[0]!;
    const within = spotsAhead(matched, first.alongMetres + 199);
    expect(within[0]).toMatchObject({ here: true });
    expect(within[0]?.spot.id).toBe(first.spot.id);
    const passed = spotsAhead(matched, first.alongMetres + 201);
    expect(passed[0]?.spot.id).not.toBe(first.spot.id);
    const approaching = spotsAhead(matched, first.alongMetres - 199);
    expect(approaching[0]).toMatchObject({ here: true });
    expect(spotsAhead(matched, first.alongMetres - 201)[0]).toMatchObject({ here: false });
  });

  it('labels distances and builds a sorted, bounded request', () => {
    expect(spotDistanceLabel({ here: true, aheadMetres: 10 })).toBe('Here');
    expect(spotDistanceLabel({ here: false, aheadMetres: 430 })).toBe('400 m ahead');
    expect(spotDistanceLabel({ here: false, aheadMetres: 4_260 })).toBe('4.3 km ahead');
    expect(spotDistanceLabel({ here: false, aheadMetres: 12_400 })).toBe('12 km ahead');
    const ids = activityRequestIds(spotsAhead([...matched].reverse(), 0));
    expect(ids).toEqual([...ids].sort());
    expect(ids.length).toBeLessThanOrEqual(20);
  });

  it('never shows "Here" without a position, and rounds labels across unit boundaries', () => {
    const first = matched[0]!;
    expect(spotsAhead(matched, first.alongMetres - 50, false)[0]).toMatchObject({ here: false });
    expect(spotDistanceLabel({ here: false, aheadMetres: 960 })).toBe('1.0 km ahead');
    expect(spotDistanceLabel({ here: false, aheadMetres: 940 })).toBe('900 m ahead');
    expect(spotDistanceLabel({ here: false, aheadMetres: 9_960 })).toBe('10 km ahead');
  });

  it('labels freshness', () => {
    expect(updatedAgoLabel(0, 30_000)).toBe('Last updated just now');
    expect(updatedAgoLabel(0, 14 * 60_000)).toBe('Last updated 14 min ago');
    expect(updatedAgoLabel(0, 125 * 60_000)).toBe('Last updated 2 h ago');
  });
});

describe('catalog and activity parsing', () => {
  const wire = (overrides: Record<string, unknown> = {}) => ({
    version,
    corridors: [{ id: 'gst-trunk', name: 'GST Road' }],
    spots: [
      {
        id: spotId(1),
        name: 'Toll',
        nameTa: 'சுங்கம்',
        kind: 'toll',
        longitude: 80,
        latitude: 12.1,
        district: 'chengalpattu',
        corridors: ['gst-trunk'],
        categories: ['traffic', 'queue'],
      },
    ],
    ...overrides,
  });

  it('reads a catalog whose version matches the ETag', () => {
    const catalog = readSpotCatalog(wire(), etag);
    expect(catalog.version).toBe(version);
    expect(catalog.spots[0]).toMatchObject({ kind: 'toll', coordinate: [80, 12.1] });
    expect(catalogVersionFromEtag(etag)).toBe(version);
  });

  it('rejects a version/ETag mismatch and malformed required fields', () => {
    expect(() => readSpotCatalog(wire(), `"${spotId(7)}"`)).toThrow();
    expect(() => readSpotCatalog(wire(), version)).toThrow();
    for (const bad of [
      { name: '' },
      { nameTa: undefined },
      { id: '0000000a-0000-4000-8000-00000000000A' },
      { latitude: 91 },
      { longitude: '80' },
      { district: 'Chengalpattu' },
    ]) {
      const input = wire();
      input.spots = [{ ...input.spots[0]!, ...bad } as never];
      expect(() => readSpotCatalog(input, etag)).toThrow();
    }
    const duplicate = wire();
    duplicate.spots = [duplicate.spots[0]!, duplicate.spots[0]!];
    expect(() => readSpotCatalog(duplicate, etag)).toThrow();
    const tooMany = wire({
      spots: Array.from({ length: 513 }, (_, index) => ({
        ...wire().spots[0]!,
        id: spotId(index + 1),
      })),
    });
    expect(() => readSpotCatalog(tooMany, etag)).toThrow();
  });

  it('drops unknown kinds and categories but keeps the rest (additive catalogs)', () => {
    const input = wire({ extra: true });
    input.spots = [
      { ...input.spots[0]!, categories: ['traffic', 'parking'] },
      { ...input.spots[0]!, id: spotId(2), kind: 'hospital' },
    ];
    const catalog = readSpotCatalog(input, etag);
    expect(catalog.spots).toHaveLength(1);
    expect(catalog.spots[0]?.categories).toEqual(['traffic']);
    const onlyUnknown = wire();
    onlyUnknown.spots = [{ ...onlyUnknown.spots[0]!, kind: 'hospital' }];
    expect(() => readSpotCatalog(onlyUnknown, etag)).toThrow();
  });

  it('reads activity and tolerates alerts arriving later', () => {
    const activity = readSpotActivity({
      serverTime: '2026-11-05T06:30:00Z',
      catalogVersion: version,
      spots: [{ id: spotId(1), state: 'quiet', alertIds: ['alert-1'] }],
      alerts: [{ id: 'alert-1' }],
    });
    expect(activity.spots).toEqual([
      {
        id: spotId(1),
        state: 'quiet',
        alertIds: ['alert-1'],
        signals: [],
        posts: [],
        postsTruncated: false,
        highlights: [],
      },
    ]);
    expect(() =>
      readSpotActivity({ serverTime: 'now', catalogVersion: version, spots: [], alerts: [] }),
    ).toThrow();
    const entry = { id: spotId(1), state: 'quiet', alertIds: [] };
    expect(() =>
      readSpotActivity({
        serverTime: '2026-11-05T06:30:00Z',
        catalogVersion: version,
        spots: Array.from({ length: 21 }, () => entry),
      }),
    ).toThrow();
    expect(() =>
      readSpotActivity({
        serverTime: '2026-11-05T06:30:00Z',
        catalogVersion: version,
        spots: [{ ...entry, id: '0000000A-0000-4000-8000-000000000001' }],
      }),
    ).toThrow();
    expect(() =>
      readSpotActivity({
        serverTime: '2026-11-05T06:30:00Z',
        catalogVersion: version,
        spots: [{ id: spotId(1), state: 'busy' }],
      }),
    ).toThrow();
  });

  it('parses Spot content strictly and skips unknown signal categories', () => {
    const summary = {
      ref: spotId(90),
      category: 'traffic',
      value: 'slow',
      values: [
        { value: 'slow', reports: 2 },
        { value: 'moving', reports: 1 },
      ],
      latestAt: '2026-11-05T06:20:00Z',
      stillTrue: 1,
      viewerVote: null,
    };
    const post = {
      ref: spotId(91),
      alias: 'Calm Auto',
      text: 'Lane 3 moving',
      type: 'traffic',
      capturedAt: '2026-11-05T06:25:00Z',
      expiresAt: '2026-11-05T07:55:00Z',
      stillTrue: 0,
      viewerVote: 'still_true',
      mine: false,
      hidden: false,
    };
    const base = {
      id: spotId(1),
      state: 'live',
      alertIds: [],
      signals: [summary, { ...summary, ref: spotId(92), category: 'parking' }],
      posts: [post],
      postsTruncated: true,
      highlights: [{ text: 'Clean restrooms', createdAt: '2026-11-04T06:00:00Z' }],
    };
    const read = (entry: object) =>
      readSpotActivity({
        serverTime: '2026-11-05T06:30:00Z',
        catalogVersion: version,
        spots: [entry],
        alerts: [],
      }).spots[0]!;
    const parsed = read(base);
    expect(parsed.signals).toEqual([summary]);
    expect(parsed.posts).toEqual([post]);
    expect(parsed.postsTruncated).toBe(true);
    const olderServer: Record<string, unknown> = { ...post };
    delete olderServer.hidden;
    expect(read({ ...base, posts: [olderServer] }).posts[0]!.hidden).toBe(false);
    expect(read({ ...base, posts: [{ ...post, mine: true, hidden: true }] }).posts[0]!.hidden).toBe(
      true,
    );
    expect(parsed.highlights).toHaveLength(1);
    for (const broken of [
      { ...base, posts: [{ ...post, mine: 'yes' }] },
      { ...base, posts: [{ ...post, hidden: 'yes' }] },
      { ...base, posts: [{ ...post, mine: false, hidden: true }] },
      { ...base, posts: [{ ...post, text: 'two\nlines' }] },
      { ...base, posts: [{ ...post, text: 'x'.repeat(201) }] },
      { ...base, posts: [{ ...post, viewerVote: 'maybe' }] },
      { ...base, posts: Array.from({ length: 11 }, () => post) },
      { ...base, signals: [{ ...summary, values: [] }] },
      { ...base, signals: [{ ...summary, stillTrue: -1 }] },
      { ...base, highlights: [{ text: '', createdAt: '2026-11-04T06:00:00Z' }] },
      { ...base, postsTruncated: 'no' },
    ])
      expect(() => read(broken)).toThrow();
  });
});
