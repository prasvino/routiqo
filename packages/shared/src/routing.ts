export type RouteMode = 'driving' | 'walking' | 'cycling';
export type RouteCoordinate = [longitude: number, latitude: number];
export interface RouteRequest {
  mode: RouteMode;
  origin: RouteCoordinate;
  destination: RouteCoordinate;
}
export interface RouteOption {
  distanceMetres: number;
  durationSeconds: number;
  geometry: RouteCoordinate[];
}
export interface RouteResult {
  provider: 'mapbox';
  calculatedAt: string;
  routes: RouteOption[];
}
/** Validate the normalized application contract; never retain provider extras. */
export function readRouteResult(input: unknown): RouteResult {
  if (
    !record(input) ||
    input.provider !== 'mapbox' ||
    typeof input.calculatedAt !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(input.calculatedAt) ||
    input.calculatedAt.startsWith('0000') ||
    !Number.isFinite(Date.parse(input.calculatedAt)) ||
    new Date(input.calculatedAt).toISOString().slice(0, 19) !== input.calculatedAt.slice(0, 19) ||
    !Array.isArray(input.routes) ||
    input.routes.length > 3
  )
    throw new Error('Routing response is invalid.');
  const routes =
    input.routes.length === 0
      ? []
      : readMapboxRoutes({
          code: 'Ok',
          routes: input.routes.map((route: unknown) => {
            if (!record(route)) throw new Error('Routing response is invalid.');
            return {
              distance: route.distanceMetres,
              duration: route.durationSeconds,
              geometry: { type: 'LineString', coordinates: route.geometry },
            };
          }),
        });
  return { provider: 'mapbox', calculatedAt: input.calculatedAt, routes };
}
const record = (input: unknown): input is Record<string, unknown> =>
  typeof input === 'object' && input !== null && !Array.isArray(input);
function coordinate(input: unknown): RouteCoordinate {
  if (
    !Array.isArray(input) ||
    input.length !== 2 ||
    typeof input[0] !== 'number' ||
    typeof input[1] !== 'number' ||
    !Number.isFinite(input[0]) ||
    !Number.isFinite(input[1]) ||
    Math.abs(input[0]) > 180 ||
    Math.abs(input[1]) > 90
  )
    throw new Error('Choose valid route endpoints.');
  return [input[0], input[1]];
}
export function readRouteRequest(input: unknown): RouteRequest {
  if (!record(input) || !['driving', 'walking', 'cycling'].includes(input.mode as string))
    throw new Error('Choose a supported travel mode.');
  const origin = coordinate(input.origin),
    destination = coordinate(input.destination);
  if (origin[0] === destination[0] && origin[1] === destination[1])
    throw new Error('Choose two different places.');
  return { mode: input.mode as RouteMode, origin, destination };
}
export function readMapboxRoutes(input: unknown): RouteOption[] {
  if (!record(input)) throw new Error('Routing response is unavailable.');
  if (input.code === 'NoRoute') return [];
  if (
    input.code !== 'Ok' ||
    !Array.isArray(input.routes) ||
    input.routes.length < 1 ||
    input.routes.length > 3
  )
    throw new Error('Routing response is unavailable.');
  return input.routes.map((route: unknown) => {
    if (
      !record(route) ||
      typeof route.distance !== 'number' ||
      !Number.isFinite(route.distance) ||
      route.distance < 0 ||
      typeof route.duration !== 'number' ||
      !Number.isFinite(route.duration) ||
      route.duration < 0 ||
      !record(route.geometry) ||
      route.geometry.type !== 'LineString' ||
      !Array.isArray(route.geometry.coordinates) ||
      route.geometry.coordinates.length < 2 ||
      route.geometry.coordinates.length > 10000
    )
      throw new Error('Routing response is invalid.');
    return {
      distanceMetres: route.distance,
      durationSeconds: route.duration,
      geometry: route.geometry.coordinates.map(coordinate),
    };
  });
}
