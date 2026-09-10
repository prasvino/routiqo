import { readRouteCoordinate, type RouteCoordinate } from './routing';
export interface PlaceMatch {
  id: string;
  label: string;
  coordinate: RouteCoordinate;
}
export interface PlaceResults {
  provider: 'mapbox';
  places: PlaceMatch[];
  attribution: string;
}
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const hasControls = (value: string) =>
  [...value].some((character) => {
    const code = character.codePointAt(0)!;
    return code <= 31 || (code >= 127 && code <= 159);
  });
export function readPlaceQuery(input: unknown): string {
  if (typeof input !== 'string') throw new Error('Enter a place to search.');
  const query = input.trim();
  const words = query.match(/[\p{L}\p{N}]+/gu)?.length ?? 0;
  if (
    query.length < 3 ||
    query.length > 256 ||
    query.includes(';') ||
    hasControls(query) ||
    words === 0 ||
    words > 20
  )
    throw new Error('Enter a shorter city, street or address.');
  return query;
}
function text(value: unknown, limit: number): string {
  if (typeof value !== 'string' || !value.trim() || value.length > limit || hasControls(value))
    throw new Error('Place results are unavailable.');
  return value;
}
export function readPlaceResults(input: unknown): PlaceResults {
  if (
    !record(input) ||
    input.provider !== 'mapbox' ||
    !Array.isArray(input.places) ||
    input.places.length > 5
  )
    throw new Error('Place results are unavailable.');
  const places = input.places.map((place: unknown) => {
    if (!record(place)) throw new Error('Place results are unavailable.');
    return {
      id: text(place.id, 512),
      label: text(place.label, 512),
      coordinate: readRouteCoordinate(place.coordinate),
    };
  });
  if (new Set(places.map((place) => place.id)).size !== places.length)
    throw new Error('Place results are unavailable.');
  return { provider: 'mapbox', places, attribution: text(input.attribution, 2048) };
}
