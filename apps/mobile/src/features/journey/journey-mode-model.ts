import {
  cumulativeRouteMetres,
  projectOntoRoute,
  type JourneyKind,
  type JourneyOutbox,
  type JourneyRoute,
  type JourneySnapshots,
  type OutboxBlock,
} from '@routiqo/shared';
import type { LocationState } from './journey-location';

/** Journey mode is opt-in per build (ANDROID_JOURNEY_MAP_SPEC). Exact `true` only. */
export const journeyMapEnabled = (value = process.env.EXPO_PUBLIC_ROUTIQO_JOURNEY_MAP_ENABLED) =>
  value === 'true';

export interface CurrentJourney {
  id: string;
  kind: JourneyKind;
  startedAt: string | null;
  /** The server has confirmed the start. Completion is offered only then. */
  confirmed: boolean;
  blocked: OutboxBlock | null;
}

/** The server-active journey, or a start still waiting to send; null otherwise. */
export function currentJourney(
  partition: { outbox: JourneyOutbox; snapshots: JourneySnapshots } | null,
): CurrentJourney | null {
  if (!partition) return null;
  const completing = (id: string) =>
    partition.outbox.entries.some(
      (entry) => entry.command.journeyId === id && entry.command.action === 'complete',
    );
  const active = partition.snapshots.journeys.find((item) => item.status === 'active');
  if (active && !completing(active.id))
    return {
      id: active.id,
      kind: active.kind,
      startedAt: active.startedAt,
      confirmed: true,
      blocked: null,
    };
  const queued = partition.outbox.entries.find((entry) => entry.command.action === 'start');
  if (!queued || queued.command.action !== 'start' || completing(queued.command.journeyId))
    return null;
  if (partition.snapshots.journeys.some((item) => item.id === queued.command.journeyId))
    return null;
  return {
    id: queued.command.journeyId,
    kind: queued.command.kind,
    startedAt: null,
    confirmed: false,
    blocked: queued.blocked,
  };
}

export function elapsedLabel(startedAt: string | null, now: number): string | null {
  if (!startedAt) return null;
  const minutes = Math.max(0, Math.floor((now - Date.parse(startedAt)) / 60_000));
  if (!Number.isFinite(minutes)) return null;
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours} h ${rest} min` : `${hours} h`;
}

export const kilometres = (metres: number) =>
  metres < 1000 ? `${Math.round(metres / 10) * 10} m` : `${(metres / 1000).toFixed(1)} km`;

export interface RouteProgress {
  alongMetres: number;
  remainingMetres: number;
  offRoute: boolean;
  fromStart: boolean;
}

/** Progress along the stored route from the latest reading (on device only). */
export function routeProgress(
  route: JourneyRoute | null,
  location: LocationState,
  measures: readonly number[] | null = route ? cumulativeRouteMetres(route.geometry) : null,
): RouteProgress | null {
  if (!route || !measures) return null;
  const total = measures[measures.length - 1] ?? 0;
  if (!location.fix)
    return { alongMetres: 0, remainingMetres: total, offRoute: false, fromStart: true };
  const projection = projectOntoRoute(route.geometry, location.fix.coordinate, measures);
  return {
    alongMetres: projection.alongMetres,
    remainingMetres: Math.max(0, total - projection.alongMetres),
    offRoute: projection.offRoute,
    fromStart: false,
  };
}

export type PositionAction = 'show' | 'settings' | 'retry' | null;
export interface PositionCopy {
  text: string;
  action: PositionAction;
  alert: boolean;
}

export function positionCopy(location: LocationState): PositionCopy {
  switch (location.status) {
    case 'not_asked':
    case 'idle':
      return {
        text: 'Your position stays on this phone and is used only to order the Spots ahead.',
        action: 'show',
        alert: false,
      };
    case 'requesting':
      return { text: 'Waiting for your permission choice…', action: null, alert: false };
    case 'denied':
      return {
        text: 'Location permission is off. The map still shows your route.',
        action: 'show',
        alert: false,
      };
    case 'blocked':
      return {
        text: 'Location permission is blocked. Turn it on in system settings to see your position.',
        action: 'settings',
        alert: false,
      };
    case 'services_off':
      return {
        text: 'Location services are off on this phone. Turn them on to see your position.',
        action: 'retry',
        alert: true,
      };
    case 'waiting':
      return { text: 'Finding your position…', action: null, alert: false };
    case 'weak':
      return {
        text: 'Weak location signal. Your position may be off by more than 100 m.',
        action: null,
        alert: false,
      };
    case 'unavailable':
      return {
        text: 'Your position is unavailable right now.',
        action: 'retry',
        alert: true,
      };
    case 'ready':
      return { text: 'Showing your position. It stays on this phone.', action: null, alert: false };
  }
}

export function routeNote(route: JourneyRoute | null, progress: RouteProgress | null): string {
  if (!route) return 'No route selected, so Spots ahead are not available for this journey.';
  if (progress?.offRoute)
    return 'You seem to be off the selected route. Spots are ordered from the nearest point ahead.';
  const remaining = progress
    ? kilometres(progress.remainingMetres)
    : kilometres(route.distanceMetres);
  return progress?.fromStart
    ? `${route.originLabel} to ${route.destinationLabel} · ${remaining} (from start)`
    : `${route.originLabel} to ${route.destinationLabel} · about ${remaining} to go`;
}

export function journeyTitle(journey: CurrentJourney): string {
  return journey.kind === 'commute' ? 'Commute in progress' : 'Trip in progress';
}

export function startNote(journey: CurrentJourney, online: boolean): string | null {
  if (journey.confirmed) return null;
  if (journey.blocked) return `Journey start needs attention: ${journey.blocked}. See Trips.`;
  return online
    ? 'Journey start saved on this device and sending.'
    : 'Journey start saved on this device. It will send when connected.';
}
