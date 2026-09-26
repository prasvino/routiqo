import {
  cumulativeRouteMetres,
  haversineMetres,
  projectOntoRoute,
  type JourneyRoute,
} from './journey-route';
import type { RouteCoordinate } from './routing';

/**
 * Spots ahead on the device (SPOTS_SPEC, ADR 0067). The catalog is public reference data; the
 * route and position used here never leave the phone. Only Spot IDs are sent for activity.
 */
export const SPOT_KINDS = [
  'toll',
  'eatery',
  'fuel',
  'restroom',
  'bus_stand',
  'temple',
  'junction',
  'rest_area',
] as const;
export type SpotKind = (typeof SPOT_KINDS)[number];
export const SPOT_CATEGORIES = ['traffic', 'queue', 'food', 'fuel', 'restroom'] as const;
export type SpotCategory = (typeof SPOT_CATEGORIES)[number];
export type SpotState = 'live' | 'fading' | 'quiet';

export interface Spot {
  id: string;
  name: string;
  nameTa: string;
  kind: SpotKind;
  coordinate: RouteCoordinate;
  district: string;
  corridors: string[];
  categories: SpotCategory[];
}

export interface SpotCatalog {
  version: string;
  corridors: { id: string; name: string }[];
  spots: Spot[];
}

export interface SpotActivityEntry {
  id: string;
  state: SpotState;
  alertIds: string[];
}

export interface SpotActivity {
  serverTime: string;
  catalogVersion: string;
  spots: SpotActivityEntry[];
}

export const SPOT_MATCH_METRES = 100;
export const SPOT_ENDPOINT_EXCLUSION_METRES = 1000;
export const SPOT_HERE_METRES = 200;
export const SPOTS_AHEAD_LIMIT = 20;
export const SPOT_CATALOG_MAX_SPOTS = 512;

const spotId = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const nilId = '00000000-0000-0000-0000-000000000000';
const slug = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const districtKey = /^[a-z]+(?:_[a-z]+)*$/;
const instant = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
const spotKinds: ReadonlySet<string> = new Set(SPOT_KINDS);
const spotCategories: ReadonlySet<string> = new Set(SPOT_CATEGORIES);

function invalid(): never {
  throw new Error('The Spot list is not valid.');
}

function record(input: unknown): Record<string, unknown> {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) invalid();
  return input as Record<string, unknown>;
}

function id(input: unknown): string {
  if (typeof input !== 'string' || !spotId.test(input) || input === nilId) invalid();
  return input;
}

/** Display names: 1-80 code points, no control characters (the server applies the full rule). */
function label(input: unknown): string {
  if (typeof input !== 'string') invalid();
  const length = [...input].length;
  if (length < 1 || length > 80 || /\p{Cc}/u.test(input) || input.trim() !== input) invalid();
  return input;
}

function coordinate(longitude: unknown, latitude: unknown): RouteCoordinate {
  if (
    typeof longitude !== 'number' ||
    typeof latitude !== 'number' ||
    !Number.isFinite(longitude) ||
    !Number.isFinite(latitude) ||
    Math.abs(longitude) > 180 ||
    Math.abs(latitude) > 90
  )
    invalid();
  return [longitude, latitude];
}

function list(input: unknown, max: number): unknown[] {
  if (!Array.isArray(input) || input.length > max) invalid();
  return input;
}

/** Strips the quotes from a strong ETag produced by the catalog endpoint. */
export function catalogVersionFromEtag(etag: string): string {
  const match = /^"([0-9a-f-]{36})"$/.exec(etag);
  if (!match?.[1]) invalid();
  return id(match[1]);
}

/**
 * Validates a downloaded catalog. Throws (so the cached copy is kept) when the version does not
 * match the ETag or a required field is malformed. Unknown keys are ignored, and a Spot with an
 * unknown kind or category is dropped or trimmed, so a later additive catalog cannot blank the list.
 */
export function readSpotCatalog(input: unknown, etag: string): SpotCatalog {
  const root = record(input);
  const version = id(root.version);
  if (version !== catalogVersionFromEtag(etag)) invalid();
  const corridors = list(root.corridors, 64).map((value) => {
    const corridor = record(value);
    if (typeof corridor.id !== 'string' || !slug.test(corridor.id) || corridor.id.length > 40)
      invalid();
    return { id: corridor.id, name: label(corridor.name) };
  });
  const seen = new Set<string>();
  const spots: Spot[] = [];
  for (const value of list(root.spots, SPOT_CATALOG_MAX_SPOTS)) {
    const spot = record(value);
    const spotIdentifier = id(spot.id);
    if (seen.has(spotIdentifier)) invalid();
    seen.add(spotIdentifier);
    const name = label(spot.name);
    const nameTa = label(spot.nameTa);
    const where = coordinate(spot.longitude, spot.latitude);
    if (typeof spot.district !== 'string' || !districtKey.test(spot.district)) invalid();
    const spotCorridors = list(spot.corridors, 16).map((corridor) => {
      if (typeof corridor !== 'string' || !slug.test(corridor)) invalid();
      return corridor;
    });
    const categories = list(spot.categories, 16).filter(
      (category): category is SpotCategory =>
        typeof category === 'string' && spotCategories.has(category),
    );
    if (typeof spot.kind !== 'string') invalid();
    if (!spotKinds.has(spot.kind)) continue;
    spots.push({
      id: spotIdentifier,
      name,
      nameTa,
      kind: spot.kind as SpotKind,
      coordinate: where,
      district: spot.district,
      corridors: spotCorridors,
      categories: [...new Set(categories)],
    });
  }
  if (spots.length === 0) invalid();
  return { version, corridors, spots };
}

/** Validates an activity response; alerts are tolerated because they arrive additively later. */
export function readSpotActivity(input: unknown): SpotActivity {
  const root = record(input);
  if (typeof root.serverTime !== 'string' || !instant.test(root.serverTime)) invalid();
  const catalogVersion = id(root.catalogVersion);
  const spots = list(root.spots, SPOTS_AHEAD_LIMIT).map((value) => {
    const entry = record(value);
    const state = entry.state;
    if (state !== 'live' && state !== 'fading' && state !== 'quiet') invalid();
    const alertIds = Array.isArray(entry.alertIds)
      ? entry.alertIds.filter((alert): alert is string => typeof alert === 'string')
      : [];
    return { id: id(entry.id), state: state as SpotState, alertIds };
  });
  if (root.alerts !== undefined && !Array.isArray(root.alerts)) invalid();
  return { serverTime: root.serverTime, catalogVersion, spots };
}

export interface MatchedSpot {
  spot: Spot;
  alongMetres: number;
}

/**
 * Catalog Spots within 100 m of the route line (distance to segments), leaving out Spots within
 * 1,000 m of either stored endpoint except bus stands, ordered along the route. Run once per route
 * or catalog change; the result is reused for every position update.
 */
export function matchSpotsToRoute(catalog: SpotCatalog, route: JourneyRoute): MatchedSpot[] {
  const measures = cumulativeRouteMetres(route.geometry);
  // Cheap bounding-box prefilter with a margin comfortably above the match distance.
  let west = Infinity;
  let south = Infinity;
  let east = -Infinity;
  let north = -Infinity;
  for (const [longitude, latitude] of route.geometry) {
    west = Math.min(west, longitude);
    east = Math.max(east, longitude);
    south = Math.min(south, latitude);
    north = Math.max(north, latitude);
  }
  const marginLatitude = (SPOT_MATCH_METRES * 2) / 111_000;
  const widest = Math.max(Math.abs(south), Math.abs(north));
  const marginLongitude = marginLatitude / Math.max(0.01, Math.cos((widest * Math.PI) / 180));
  const matched: MatchedSpot[] = [];
  for (const spot of catalog.spots) {
    const [longitude, latitude] = spot.coordinate;
    if (
      longitude < west - marginLongitude ||
      longitude > east + marginLongitude ||
      latitude < south - marginLatitude ||
      latitude > north + marginLatitude
    )
      continue;
    if (
      spot.kind !== 'bus_stand' &&
      (haversineMetres(spot.coordinate, route.origin) <= SPOT_ENDPOINT_EXCLUSION_METRES ||
        haversineMetres(spot.coordinate, route.destination) <= SPOT_ENDPOINT_EXCLUSION_METRES)
    )
      continue;
    const projection = projectOntoRoute(route.geometry, spot.coordinate, measures);
    if (projection.offsetMetres > SPOT_MATCH_METRES) continue;
    matched.push({ spot, alongMetres: projection.alongMetres });
  }
  return matched.sort((a, b) => a.alongMetres - b.alongMetres || (a.spot.id < b.spot.id ? -1 : 1));
}

export interface SpotAhead {
  spot: Spot;
  /** Metres from the traveller (or the route start) to the Spot along the route; negative behind. */
  aheadMetres: number;
  here: boolean;
}

/**
 * The next Spots from the traveller's position along the route (or the route start without a
 * position). A Spot within 200 m either side is "Here"; Spots further behind drop out. At most 20.
 */
export function spotsAhead(
  matched: readonly MatchedSpot[],
  travellerAlongMetres: number,
): SpotAhead[] {
  const ahead: SpotAhead[] = [];
  for (const { spot, alongMetres } of matched) {
    const aheadMetres = alongMetres - travellerAlongMetres;
    if (aheadMetres < -SPOT_HERE_METRES) continue;
    ahead.push({ spot, aheadMetres, here: Math.abs(aheadMetres) <= SPOT_HERE_METRES });
    if (ahead.length === SPOTS_AHEAD_LIMIT) break;
  }
  return ahead;
}

/** "Here", "400 m ahead" or "12 km ahead"; "from start" is added by the caller without a position. */
export function spotDistanceLabel(ahead: Pick<SpotAhead, 'aheadMetres' | 'here'>): string {
  if (ahead.here) return 'Here';
  const metres = Math.max(0, ahead.aheadMetres);
  if (metres < 1000) return `${Math.round(metres / 100) * 100} m ahead`;
  if (metres < 10_000) return `${(metres / 1000).toFixed(1)} km ahead`;
  return `${Math.round(metres / 1000)} km ahead`;
}

/** "Last updated just now", "Last updated 14 min ago" or "Last updated 2 h ago". */
export function updatedAgoLabel(receivedAt: number, now: number): string {
  const minutes = Math.max(0, Math.floor((now - receivedAt) / 60_000));
  if (minutes < 1) return 'Last updated just now';
  if (minutes < 60) return `Last updated ${minutes} min ago`;
  return `Last updated ${Math.floor(minutes / 60)} h ago`;
}

/** Sorted, distinct Spot IDs for the activity request body (the server requires ascending order). */
export function activityRequestIds(ahead: readonly SpotAhead[]): string[] {
  return [...new Set(ahead.map((entry) => entry.spot.id))].sort().slice(0, SPOTS_AHEAD_LIMIT);
}
