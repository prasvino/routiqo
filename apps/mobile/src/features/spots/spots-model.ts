import {
  spotDistanceLabel,
  updatedAgoLabel,
  type SpotActivity,
  type SpotAhead,
  type SpotKind,
  type SpotState,
} from '@routiqo/shared';
import { ACTIVITY_INTERVAL_MS, type SpotActivityStatus } from './spot-activity-controller';

/** Client flag: exact `true` only, default off. Journey mode must also be enabled. */
export const spotsEnabled = (value = process.env.EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED) =>
  value === 'true';

export type SpotsPanelSize = 'collapsed' | 'half' | 'full';
const visibleRows: Record<SpotsPanelSize, number> = { collapsed: 1, half: 5, full: 20 };

const kindLabels: Record<SpotKind, string> = {
  toll: 'Toll plaza',
  eatery: 'Eatery',
  fuel: 'Fuel station',
  restroom: 'Restroom',
  bus_stand: 'Bus stand',
  temple: 'Temple',
  junction: 'Junction',
  rest_area: 'Rest area',
};
const stateLabels: Record<SpotState, string> = {
  live: 'Live',
  fading: 'Earlier today',
  quiet: 'No recent reports',
};
const NOT_CLEAR = 'No recent reports. This does not mean the road is clear.';

export interface SpotRowView {
  id: string;
  name: string;
  nameTa: string;
  kind: string;
  distance: string;
  /** Null until the first activity response names this Spot. */
  state: SpotState | null;
  stateLabel: string | null;
  accessibilityLabel: string;
  selected: boolean;
  detail: { lines: string[] } | null;
}

export interface SpotsPanelViewModel {
  notice: string | null;
  freshness: string | null;
  rows: SpotRowView[];
  sizeAction: { label: string; next: SpotsPanelSize } | null;
}

export interface SpotsPanelInput {
  catalog: { loaded: boolean; available: boolean; refreshing: boolean };
  hasRoute: boolean;
  /** Matched Spots on this route, before the traveller's position is applied. */
  matchedCount: number;
  ahead: readonly SpotAhead[];
  fromStart: boolean;
  activity: {
    status: SpotActivityStatus;
    activity: SpotActivity | null;
    receivedAt: number | null;
  };
  online: boolean;
  confirmed: boolean;
  now: number;
  size: SpotsPanelSize;
  selectedId: string | null;
}

const districtLabel = (key: string) =>
  key
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');

function activityNotice(input: SpotsPanelInput): string | null {
  if (!input.online)
    return input.activity.receivedAt === null
      ? 'Offline. Spot updates resume when you are back online.'
      : 'Offline. Showing the last update.';
  if (!input.confirmed || input.activity.status === 'no-journey')
    return 'Spot updates start once your journey reaches the server.';
  if (input.activity.status === 'rate-limited') return 'Updates paused briefly.';
  if (input.activity.status === 'unavailable') return 'Spot updates are unavailable right now.';
  if (input.activity.status === 'loading') return 'Checking the Spots ahead…';
  return null;
}

/** Everything the Spots-ahead panel shows, derived without React so every state is testable. */
export function spotsPanelModel(input: SpotsPanelInput): SpotsPanelViewModel {
  const empty = (notice: string): SpotsPanelViewModel => ({
    notice,
    freshness: null,
    rows: [],
    sizeAction: null,
  });
  if (!input.catalog.loaded) return empty('Loading Spots…');
  if (!input.catalog.available)
    return empty(
      input.catalog.refreshing
        ? 'Downloading the Spot list…'
        : 'Spots will appear once the Spot list downloads.',
    );
  if (!input.hasRoute) return empty('Spots ahead need a route chosen when the journey starts.');
  if (input.matchedCount === 0) return empty('No Spots on this route.');
  if (input.ahead.length === 0) return empty('No more Spots ahead on this route.');

  const states = new Map(input.activity.activity?.spots.map((entry) => [entry.id, entry.state]));
  const shown = input.ahead.slice(0, visibleRows[input.size]);
  const rows = shown.map((entry): SpotRowView => {
    const state = states.get(entry.spot.id) ?? null;
    const distance =
      spotDistanceLabel(entry) + (input.fromStart && !entry.here ? ' (from start)' : '');
    const kind = kindLabels[entry.spot.kind];
    const stateLabel = state ? stateLabels[state] : null;
    const selected = entry.spot.id === input.selectedId;
    return {
      id: entry.spot.id,
      name: entry.spot.name,
      nameTa: entry.spot.nameTa,
      kind,
      distance,
      state,
      stateLabel,
      accessibilityLabel: [entry.spot.name, entry.spot.nameTa, kind, distance, stateLabel]
        .filter(Boolean)
        .join(', '),
      selected,
      detail: selected
        ? {
            lines: [
              `${kind} · ${districtLabel(entry.spot.district)} district`,
              state === 'live'
                ? 'Live: reported in the last 30 minutes.'
                : state === 'fading'
                  ? 'Earlier today: no report in the last 30 minutes.'
                  : NOT_CLEAR,
            ],
          }
        : null,
    };
  });

  const received = input.activity.receivedAt;
  const stale =
    received !== null &&
    (!input.online ||
      input.activity.status !== 'ready' ||
      input.now - received > 2 * ACTIVITY_INTERVAL_MS);
  const allQuiet =
    input.activity.status === 'ready' &&
    input.ahead.every((entry) => (states.get(entry.spot.id) ?? 'quiet') === 'quiet');
  const total = input.ahead.length;
  const sizeAction: SpotsPanelViewModel['sizeAction'] =
    input.size === 'collapsed' && total > 1
      ? { label: 'Show more Spots', next: 'half' }
      : input.size === 'half' && total > visibleRows.half
        ? { label: `Show all ${total} Spots`, next: 'full' }
        : input.size !== 'collapsed' && total > 1
          ? { label: 'Show fewer Spots', next: 'collapsed' }
          : null;
  return {
    notice:
      activityNotice(input) ??
      (allQuiet
        ? 'No recent reports on the Spots ahead. That does not mean the road is clear.'
        : null),
    freshness: stale && received !== null ? updatedAgoLabel(received, input.now) : null,
    rows,
    sizeAction,
  };
}
