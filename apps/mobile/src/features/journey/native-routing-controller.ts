import {
  readPlaceQuery,
  readPlaceResults,
  readRouteResult,
  type PlaceMatch,
  type PlaceResults,
  type RouteMode,
  type RouteRequest,
  type RouteResult,
} from '@routiqo/shared';
import { NativeSessionRequired } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import { NativeRoutingError } from './native-routing';

export type RoutingEnd = 'origin' | 'destination';
export type RoutingFailure =
  | 'invalid_query'
  | 'same_place'
  | 'invalid'
  | 'coverage'
  | 'offline'
  | 'session'
  | 'forbidden'
  | 'rate_limited'
  | 'unavailable'
  | null;
export interface NativeRoutingEndpoint {
  text: string;
  selected: PlaceMatch | null;
  results: PlaceResults | null;
  attribution: string | null;
}
export interface NativeRoutingState {
  origin: NativeRoutingEndpoint;
  destination: NativeRoutingEndpoint;
  mode: RouteMode;
  route: RouteResult | null;
  alternative: number;
  step: number;
  busy: RoutingEnd | 'route' | null;
  failure: RoutingFailure;
  noRoute: boolean;
}
export interface NativeRoutingEnvironment {
  accountId: string | null;
  online: boolean;
  eligible: boolean;
  foreground: boolean;
  focused: boolean;
  sessionEpoch: number;
}
export interface NativeRoutingPorts {
  environment(): NativeRoutingEnvironment;
  search(query: string, signal: AbortSignal): Promise<PlaceResults>;
  calculate(request: RouteRequest, signal: AbortSignal): Promise<RouteResult>;
}

const empty = (): NativeRoutingEndpoint => ({
  text: '',
  selected: null,
  results: null,
  attribution: null,
});
const copied = (place: PlaceMatch): PlaceMatch => ({
  id: place.id,
  label: place.label,
  coordinate: [place.coordinate[0], place.coordinate[1]],
});
export function createNativeRoutingController(
  accountId: string,
  ports: NativeRoutingPorts,
  publish: (state: NativeRoutingState) => void,
) {
  let state: NativeRoutingState = {
    origin: empty(),
    destination: empty(),
    mode: 'driving',
    route: null,
    alternative: 0,
    step: 0,
    busy: null,
    failure: null,
    noRoute: false,
  };
  let revision = 0;
  let pending: AbortController | null = null;
  let requestEpoch = 0;
  let disposed = false;
  const update = (patch: Partial<NativeRoutingState>) => {
    state = { ...state, ...patch };
    if (!disposed) publish(state);
  };
  const available = () => {
    const environment = ports.environment();
    return (
      environment.accountId === accountId &&
      environment.online &&
      environment.eligible &&
      environment.foreground &&
      environment.focused
    );
  };
  const cancel = () => {
    revision++;
    pending?.abort();
    pending = null;
    if (state.busy) update({ busy: null });
  };
  const current = (run: number, abort: AbortController) =>
    !disposed &&
    revision === run &&
    !abort.signal.aborted &&
    available() &&
    ports.environment().sessionEpoch === requestEpoch;
  const begin = (busy: RoutingEnd | 'route') => {
    if (disposed || state.busy || !available()) return null;
    const run = ++revision;
    requestEpoch = ports.environment().sessionEpoch;
    const abort = new AbortController();
    pending = abort;
    update({ busy, failure: null });
    return { run, abort };
  };
  const finish = (run: number, abort: AbortController) => {
    if (!current(run, abort)) return;
    pending = null;
    update({ busy: null });
  };
  const classify = (error: unknown): RoutingFailure => {
    if (error instanceof NativeSessionRequired) return 'session';
    if (error instanceof NativeRoutingError) {
      if (error.code === 'session') return 'session';
      if (error.code === 'forbidden') return 'forbidden';
      if (error.code === 'invalid') return 'invalid';
      if (error.code === 'coverage') return 'coverage';
      if (error.code === 'rate-limited') return 'rate_limited';
    }
    if (error instanceof NativeHttpStatus) {
      if (error.status === 401 || error.status === 403) return 'session';
      if (error.status === 429) return 'rate_limited';
    }
    return 'unavailable';
  };
  const clearRoute = () => ({ route: null, alternative: 0, step: 0, noRoute: false });
  const edit = (end: RoutingEnd, text: string) => {
    if (disposed) return;
    cancel();
    update({
      [end]: { text, selected: null, results: null, attribution: null },
      ...clearRoute(),
      failure: null,
    });
  };
  const select = (end: RoutingEnd, id: string) => {
    if (disposed || state.busy) return;
    const endpoint = state[end];
    const found = endpoint.results?.places.find((place) => place.id === id);
    if (!found) return;
    update({
      [end]: {
        text: found.label,
        selected: copied(found),
        results: null,
        attribution: endpoint.results?.attribution ?? null,
      },
      ...clearRoute(),
      failure: null,
    });
  };
  const mode = (value: RouteMode) => {
    if (disposed || !['driving', 'walking', 'cycling'].includes(value)) return;
    cancel();
    if (value !== state.mode) update({ mode: value, ...clearRoute(), failure: null });
  };
  const swap = () => {
    if (disposed || state.busy || !state.origin.selected || !state.destination.selected) return;
    update({
      origin: state.destination,
      destination: state.origin,
      ...clearRoute(),
      failure: null,
    });
  };
  async function search(end: RoutingEnd) {
    if (disposed || state.busy) return;
    let query: string;
    try {
      query = readPlaceQuery(state[end].text);
    } catch {
      update({ failure: 'invalid_query' });
      return;
    }
    const started = begin(end);
    if (!started) return;
    const { run, abort } = started;
    try {
      const results = readPlaceResults(await ports.search(query, abort.signal));
      if (!current(run, abort)) return;
      update({ [end]: { ...state[end], results }, failure: null });
    } catch (error) {
      if (!current(run, abort)) return;
      update({ [end]: { ...state[end], results: null }, failure: classify(error) });
    } finally {
      finish(run, abort);
    }
  }
  async function calculate() {
    if (disposed || state.busy || !state.origin.selected || !state.destination.selected) return;
    const request: RouteRequest = {
      mode: state.mode,
      origin: [...state.origin.selected.coordinate],
      destination: [...state.destination.selected.coordinate],
    };
    if (
      request.origin[0] === request.destination[0] &&
      request.origin[1] === request.destination[1]
    ) {
      update({ failure: 'same_place' });
      return;
    }
    const started = begin('route');
    if (!started) return;
    const { run, abort } = started;
    try {
      const result = readRouteResult(await ports.calculate(request, abort.signal));
      if (!current(run, abort)) return;
      update({
        route: result.routes.length ? result : null,
        alternative: 0,
        step: 0,
        noRoute: result.routes.length === 0,
        failure: null,
      });
    } catch (error) {
      if (!current(run, abort)) return;
      update({ failure: classify(error) }); // Keep the last successful same-input route.
    } finally {
      finish(run, abort);
    }
  }
  const alternative = (index: number) => {
    if (disposed || !state.route?.routes[index] || index === state.alternative) return;
    update({ alternative: index, step: 0 });
  };
  const step = (index: number) => {
    const count = state.route?.routes[state.alternative]?.steps?.length ?? 0;
    if (disposed || !Number.isInteger(index) || index < 0 || index >= count) return;
    update({ step: index });
  };
  return {
    state: () => state,
    edit,
    select,
    mode,
    swap,
    search,
    calculate,
    alternative,
    step,
    suspend: cancel,
    environmentChanged: () => {
      if (!disposed && !available()) cancel();
    },
    sessionChanged: cancel,
    dispose: () => {
      disposed = true;
      revision++;
      pending?.abort();
      pending = null;
    },
  };
}
