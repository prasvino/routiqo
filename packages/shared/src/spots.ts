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

export type SpotVote = 'still_true' | 'no_longer_true';
export type SpotPostType = 'traffic' | 'place';

/** An unattributed signal summary: counts per value, never who reported them. */
export interface SpotSignalSummary {
  ref: string;
  category: SpotCategory;
  value: string;
  values: { value: string; reports: number }[];
  latestAt: string;
  stillTrue: number;
  viewerVote: SpotVote | null;
}

export interface SpotPost {
  ref: string;
  alias: string;
  text: string;
  type: SpotPostType;
  capturedAt: string;
  expiresAt: string;
  stillTrue: number;
  viewerVote: SpotVote | null;
  /** True only for the viewer's own posts, so they can delete them. */
  mine: boolean;
  /** True only on the viewer's own post that a moderator hid; no one else receives it (ADR 0075). */
  hidden: boolean;
}

export interface SpotHighlight {
  text: string;
  createdAt: string;
}

export interface SpotActivityEntry {
  id: string;
  state: SpotState;
  alertIds: string[];
  signals: SpotSignalSummary[];
  posts: SpotPost[];
  postsTruncated: boolean;
  highlights: SpotHighlight[];
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

function time(input: unknown): string {
  if (typeof input !== 'string' || !instant.test(input) || Number.isNaN(Date.parse(input)))
    invalid();
  return input;
}

function count(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) invalid();
  return input;
}

function vote(input: unknown): SpotVote | null {
  if (input === null) return null;
  if (input !== 'still_true' && input !== 'no_longer_true') invalid();
  return input;
}

/** Content text shown as received: 1-200 code points, one paragraph, no control characters. */
function contentText(input: unknown): string {
  if (typeof input !== 'string') invalid();
  const length = [...input].length;
  if (length < 1 || length > 200 || /\p{Cc}/u.test(input)) invalid();
  return input;
}

const signalValue = /^[a-z0-9_]{1,16}$/;

/** Summaries in a category this app does not know are skipped, so new categories stay additive. */
function signalSummary(input: unknown): SpotSignalSummary | null {
  const summary = record(input);
  const ref = id(summary.ref);
  if (typeof summary.value !== 'string' || !signalValue.test(summary.value)) invalid();
  const values = list(summary.values, 4).map((value) => {
    const entry = record(value);
    if (typeof entry.value !== 'string' || !signalValue.test(entry.value)) invalid();
    const reports = count(entry.reports);
    if (reports < 1) invalid();
    return { value: entry.value, reports };
  });
  if (values.length === 0) invalid();
  const parsed = {
    ref,
    value: summary.value,
    values,
    latestAt: time(summary.latestAt),
    stillTrue: count(summary.stillTrue),
    viewerVote: vote(summary.viewerVote),
  };
  if (typeof summary.category !== 'string') invalid();
  if (!spotCategories.has(summary.category)) return null;
  return { ...parsed, category: summary.category as SpotCategory };
}

function post(input: unknown): SpotPost {
  const entry = record(input);
  if (typeof entry.alias !== 'string' || [...entry.alias].length > 40 || entry.alias.length === 0)
    invalid();
  if (entry.type !== 'traffic' && entry.type !== 'place') invalid();
  if (typeof entry.mine !== 'boolean') invalid();
  // An older server omits `hidden`; a hidden post that is not the viewer's own is never valid.
  const hidden = entry.hidden === undefined ? false : entry.hidden;
  if (typeof hidden !== 'boolean' || (hidden && !entry.mine)) invalid();
  return {
    ref: id(entry.ref),
    alias: label(entry.alias),
    text: contentText(entry.text),
    type: entry.type,
    capturedAt: time(entry.capturedAt),
    expiresAt: time(entry.expiresAt),
    stillTrue: count(entry.stillTrue),
    viewerVote: vote(entry.viewerVote),
    mine: entry.mine,
    hidden,
  };
}

/**
 * Validates an activity response. Alerts are tolerated because they arrive additively later;
 * content lists missing from an older server read as empty.
 */
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
    const signals =
      entry.signals === undefined
        ? []
        : list(entry.signals, SPOT_CATEGORIES.length + 8)
            .map(signalSummary)
            .filter((summary): summary is SpotSignalSummary => summary !== null);
    const posts = entry.posts === undefined ? [] : list(entry.posts, 10).map(post);
    const highlights =
      entry.highlights === undefined
        ? []
        : list(entry.highlights, 3).map((value) => {
            const highlight = record(value);
            return { text: contentText(highlight.text), createdAt: time(highlight.createdAt) };
          });
    if (entry.postsTruncated !== undefined && typeof entry.postsTruncated !== 'boolean') invalid();
    return {
      id: id(entry.id),
      state: state as SpotState,
      alertIds,
      signals,
      posts,
      postsTruncated: entry.postsTruncated === true,
      highlights,
    };
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
  /** Without a position the list runs from the route start and nothing is "Here". */
  hasPosition = true,
): SpotAhead[] {
  const ahead: SpotAhead[] = [];
  for (const { spot, alongMetres } of matched) {
    const aheadMetres = alongMetres - travellerAlongMetres;
    if (aheadMetres < -SPOT_HERE_METRES) continue;
    ahead.push({
      spot,
      aheadMetres,
      here: hasPosition && Math.abs(aheadMetres) <= SPOT_HERE_METRES,
    });
    if (ahead.length === SPOTS_AHEAD_LIMIT) break;
  }
  return ahead;
}

/** "Here", "400 m ahead" or "12 km ahead"; "from start" is added by the caller without a position. */
export function spotDistanceLabel(ahead: Pick<SpotAhead, 'aheadMetres' | 'here'>): string {
  if (ahead.here) return 'Here';
  const metres = Math.max(0, ahead.aheadMetres);
  // Thresholds sit where rounding changes units, so 960 m reads "1.0 km", never "1000 m".
  if (metres < 950) return `${Math.round(metres / 100) * 100} m ahead`;
  if (metres < 9_950) return `${(metres / 1000).toFixed(1)} km ahead`;
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
