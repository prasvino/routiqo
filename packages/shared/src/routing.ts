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
  steps?: RouteStep[];
}
export interface RouteStep {
  instruction: string;
  distanceMetres: number;
  durationSeconds: number;
  location: RouteCoordinate;
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
  const routes = input.routes.map((route: unknown) => {
    if (!record(route)) throw new Error('Routing response is invalid.');
    return readRouteOption(
      route.distanceMetres,
      route.durationSeconds,
      route.geometry,
      route.steps,
      false,
    );
  });
  return { provider: 'mapbox', calculatedAt: input.calculatedAt, routes };
}
const record = (input: unknown): input is Record<string, unknown> =>
  typeof input === 'object' && input !== null && !Array.isArray(input);
export function readRouteCoordinate(input: unknown): RouteCoordinate {
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
  const origin = readRouteCoordinate(input.origin),
    destination = readRouteCoordinate(input.destination);
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
      !record(route.geometry) ||
      route.geometry.type !== 'LineString' ||
      !Array.isArray(route.legs) ||
      route.legs.length !== 1 ||
      !record(route.legs[0])
    )
      throw new Error('Routing response is invalid.');
    return readRouteOption(
      route.distance,
      route.duration,
      route.geometry.coordinates,
      route.legs[0].steps,
      true,
    );
  });
}

function readRouteOption(
  distanceMetres: unknown,
  durationSeconds: unknown,
  geometry: unknown,
  steps: unknown,
  requireSteps: boolean,
): RouteOption {
  const routeSteps = steps === undefined ? [] : steps;
  if (
    typeof distanceMetres !== 'number' ||
    !Number.isFinite(distanceMetres) ||
    distanceMetres < 0 ||
    typeof durationSeconds !== 'number' ||
    !Number.isFinite(durationSeconds) ||
    durationSeconds < 0 ||
    !Array.isArray(geometry) ||
    geometry.length < 2 ||
    geometry.length > 10000 ||
    !Array.isArray(routeSteps) ||
    routeSteps.length > 500 ||
    (requireSteps && routeSteps.length === 0)
  )
    throw new Error('Routing response is invalid.');
  return {
    distanceMetres,
    durationSeconds,
    geometry: geometry.map(readRouteCoordinate),
    steps: routeSteps.map((step) => readRouteStep(step, requireSteps)),
  };
}

function readRouteStep(input: unknown, providerShape: boolean): RouteStep {
  const maneuver = record(input) && record(input.maneuver) ? input.maneuver : undefined;
  const instruction = record(input)
    ? providerShape
      ? maneuver?.instruction
      : input.instruction
    : undefined;
  const distanceMetres = record(input)
    ? providerShape
      ? input.distance
      : input.distanceMetres
    : undefined;
  const durationSeconds = record(input)
    ? providerShape
      ? input.duration
      : input.durationSeconds
    : undefined;
  const location = record(input)
    ? providerShape
      ? maneuver?.location
      : input.location
    : undefined;
  if (
    !record(input) ||
    typeof instruction !== 'string' ||
    instruction.length < 1 ||
    instruction.length > 500 ||
    instruction.trim() !== instruction ||
    [...instruction].some((character) => {
      const point = character.codePointAt(0)!;
      return point <= 0x1f || (point >= 0x7f && point <= 0x9f);
    }) ||
    typeof distanceMetres !== 'number' ||
    !Number.isFinite(distanceMetres) ||
    distanceMetres < 0 ||
    typeof durationSeconds !== 'number' ||
    !Number.isFinite(durationSeconds) ||
    durationSeconds < 0
  )
    throw new Error('Routing response is invalid.');
  return {
    instruction,
    distanceMetres,
    durationSeconds,
    location: readRouteCoordinate(location),
  };
}
