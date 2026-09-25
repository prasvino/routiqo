import { readRouteCoordinate, type RouteCoordinate, type RouteMode } from './routing';

/**
 * Device-only route of the active journey (ANDROID_JOURNEY_MAP_SPEC, ADR 0067).
 * Never enters the journey outbox, backups, logs or any request.
 */
export interface JourneyRoute {
  version: 1;
  journeyId: string;
  mode: RouteMode;
  originLabel: string;
  destinationLabel: string;
  origin: RouteCoordinate;
  destination: RouteCoordinate;
  alternativeIndex: number;
  distanceMetres: number;
  durationSeconds: number;
  calculatedAt: string;
  geometry: RouteCoordinate[];
}

export const JOURNEY_ROUTE_MAX_POINTS = 2000;
export const JOURNEY_ROUTE_TOLERANCE_METRES = 10;
export const OFF_ROUTE_METRES = 1000;
const EARTH_RADIUS_METRES = 6_371_008.8;
const MAX_LABEL = 120;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const instant = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
const radians = (degrees: number) => (degrees * Math.PI) / 180;

export function haversineMetres(a: RouteCoordinate, b: RouteCoordinate): number {
  const dLat = radians(b[1] - a[1]);
  const dLon = radians(b[0] - a[0]);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(radians(a[1])) * Math.cos(radians(b[1])) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Local equirectangular metres around a reference latitude; accurate at route scale. */
function planar(point: RouteCoordinate, referenceLatitude: number): [number, number] {
  return [
    radians(point[0]) * Math.cos(radians(referenceLatitude)) * EARTH_RADIUS_METRES,
    radians(point[1]) * EARTH_RADIUS_METRES,
  ];
}

function segmentProjection(
  point: RouteCoordinate,
  start: RouteCoordinate,
  end: RouteCoordinate,
): { fraction: number; distance: number } {
  const reference = point[1];
  const [px, py] = planar(point, reference);
  const [ax, ay] = planar(start, reference);
  const [bx, by] = planar(end, reference);
  const dx = bx - ax;
  const dy = by - ay;
  const lengthSquared = dx * dx + dy * dy;
  const fraction =
    lengthSquared === 0
      ? 0
      : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
  return { fraction, distance: Math.hypot(px - (ax + fraction * dx), py - (ay + fraction * dy)) };
}

const at = (points: readonly RouteCoordinate[], index: number): RouteCoordinate => {
  const point = points[index];
  if (!point) throw new Error('A route point is missing.');
  return point;
};

function douglasPeucker(points: RouteCoordinate[], tolerance: number): RouteCoordinate[] {
  const keep = new Uint8Array(points.length);
  keep[0] = 1;
  keep[points.length - 1] = 1;
  const stack: Array<[number, number]> = [[0, points.length - 1]];
  while (stack.length > 0) {
    const [first, last] = stack.pop()!;
    let farthest = -1;
    let farthestDistance = tolerance;
    const from = at(points, first);
    const to = at(points, last);
    for (let index = first + 1; index < last; index++) {
      const { distance } = segmentProjection(at(points, index), from, to);
      if (distance > farthestDistance) {
        farthest = index;
        farthestDistance = distance;
      }
    }
    if (farthest >= 0) {
      keep[farthest] = 1;
      stack.push([first, farthest], [farthest, last]);
    }
  }
  return points.filter((_, index) => keep[index] === 1);
}

/**
 * Douglas–Peucker at about 10 m, tightened until at most 2,000 points remain.
 * Endpoints are always kept.
 */
export function simplifyJourneyGeometry(
  geometry: readonly RouteCoordinate[],
  maxPoints = JOURNEY_ROUTE_MAX_POINTS,
  toleranceMetres = JOURNEY_ROUTE_TOLERANCE_METRES,
): RouteCoordinate[] {
  if (geometry.length < 2) throw new Error('A route needs at least two points.');
  if (maxPoints < 2) throw new Error('A route keeps at least its endpoints.');
  const points = geometry.map((point) => readRouteCoordinate(point));
  let tolerance = toleranceMetres;
  let simplified = douglasPeucker(points, tolerance);
  while (simplified.length > maxPoints) {
    tolerance *= 2;
    simplified = douglasPeucker(points, tolerance);
  }
  return simplified;
}

/** Cumulative distance in metres at each vertex, starting at 0. */
export function cumulativeRouteMetres(geometry: readonly RouteCoordinate[]): number[] {
  const measures = [0];
  let total = 0;
  for (let index = 1; index < geometry.length; index++) {
    total += haversineMetres(at(geometry, index - 1), at(geometry, index));
    measures.push(total);
  }
  return measures;
}

export interface RouteProjection {
  /** Metres from the route start to the nearest point on the route. */
  alongMetres: number;
  /** Metres from the position to that nearest point. */
  offsetMetres: number;
  offRoute: boolean;
}

/** Nearest point on the route line (segments, not only vertices). */
export function projectOntoRoute(
  geometry: readonly RouteCoordinate[],
  point: RouteCoordinate,
  measures: readonly number[] = cumulativeRouteMetres(geometry),
): RouteProjection {
  if (geometry.length < 2 || measures.length !== geometry.length)
    throw new Error('A route needs at least two points.');
  let best = { alongMetres: 0, offsetMetres: Number.POSITIVE_INFINITY };
  for (let index = 1; index < geometry.length; index++) {
    const { fraction, distance } = segmentProjection(
      point,
      at(geometry, index - 1),
      at(geometry, index),
    );
    if (distance < best.offsetMetres) {
      const startMeasure = measures[index - 1] ?? 0;
      const endMeasure = measures[index] ?? startMeasure;
      best = {
        alongMetres: startMeasure + fraction * (endMeasure - startMeasure),
        offsetMetres: distance,
      };
    }
  }
  return { ...best, offRoute: best.offsetMetres > OFF_ROUTE_METRES };
}

const label = (input: unknown): string => {
  if (typeof input !== 'string') throw new Error('Journey route is invalid.');
  const value = input.trim().slice(0, MAX_LABEL);
  if (!value || /\p{Cc}/u.test(value)) throw new Error('Journey route is invalid.');
  return value;
};
const record = (input: unknown): input is Record<string, unknown> =>
  typeof input === 'object' && input !== null && !Array.isArray(input);

/** Validate a stored or newly built record; rejects anything unexpected. */
export function readJourneyRoute(input: unknown): JourneyRoute {
  if (
    !record(input) ||
    input.version !== 1 ||
    typeof input.journeyId !== 'string' ||
    !uuid.test(input.journeyId) ||
    !['driving', 'walking', 'cycling'].includes(input.mode as string) ||
    !Number.isInteger(input.alternativeIndex) ||
    (input.alternativeIndex as number) < 0 ||
    (input.alternativeIndex as number) > 2 ||
    typeof input.distanceMetres !== 'number' ||
    !Number.isFinite(input.distanceMetres) ||
    input.distanceMetres < 0 ||
    typeof input.durationSeconds !== 'number' ||
    !Number.isFinite(input.durationSeconds) ||
    input.durationSeconds < 0 ||
    typeof input.calculatedAt !== 'string' ||
    !instant.test(input.calculatedAt) ||
    !Number.isFinite(Date.parse(input.calculatedAt)) ||
    !Array.isArray(input.geometry) ||
    input.geometry.length < 2 ||
    input.geometry.length > JOURNEY_ROUTE_MAX_POINTS
  )
    throw new Error('Journey route is invalid.');
  return {
    version: 1,
    journeyId: input.journeyId,
    mode: input.mode as RouteMode,
    originLabel: label(input.originLabel),
    destinationLabel: label(input.destinationLabel),
    origin: readRouteCoordinate(input.origin),
    destination: readRouteCoordinate(input.destination),
    alternativeIndex: input.alternativeIndex as number,
    distanceMetres: input.distanceMetres,
    durationSeconds: input.durationSeconds,
    calculatedAt: input.calculatedAt,
    geometry: input.geometry.map((point) => readRouteCoordinate(point)),
  };
}

export interface JourneyRouteSource {
  journeyId: string;
  mode: RouteMode;
  originLabel: string;
  destinationLabel: string;
  origin: RouteCoordinate;
  destination: RouteCoordinate;
  alternativeIndex: number;
  calculatedAt: string;
  route: { distanceMetres: number; durationSeconds: number; geometry: RouteCoordinate[] };
}

/** Build the record from a calculated route, simplifying the geometry. */
export function buildJourneyRoute(source: JourneyRouteSource): JourneyRoute {
  return readJourneyRoute({
    version: 1,
    journeyId: source.journeyId,
    mode: source.mode,
    originLabel: source.originLabel,
    destinationLabel: source.destinationLabel,
    origin: source.origin,
    destination: source.destination,
    alternativeIndex: source.alternativeIndex,
    distanceMetres: source.route.distanceMetres,
    durationSeconds: source.route.durationSeconds,
    calculatedAt: source.calculatedAt,
    geometry: simplifyJourneyGeometry(source.route.geometry),
  });
}
