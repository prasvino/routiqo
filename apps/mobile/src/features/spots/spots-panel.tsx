import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { AppState } from 'react-native';
import { useFocusEffect } from 'expo-router';
import {
  activityRequestIds,
  matchSpotsToRoute,
  spotsAhead,
  type JourneyRoute,
} from '@routiqo/shared';
import { useNativeAccount } from '../../auth/native-account-provider';
import type { CurrentJourney, RouteProgress } from '../journey/journey-mode-model';
import { useSpotCatalogStore } from './spots-provider';
import { createSpotCatalogStore } from './spot-catalog-store';
import {
  createSpotActivityController,
  type SpotActivityController,
  type SpotActivityState,
} from './spot-activity-controller';
import { spotsPanelModel, type SpotsPanelSize } from './spots-model';
import { SpotsPanelView } from './spots-panel-view';
import { useSpotContributionControls } from './spot-contribution-controls';

export const SPOTS_RECOMPUTE_MS = 10_000;
const idleStore = createSpotCatalogStore({
  load: async () => null,
  save: async () => undefined,
  clear: async () => undefined,
  fetch: () => Promise.reject(new Error('Spots are off.')),
  now: Date.now,
});

/**
 * Matches the cached catalog to the device-only route once per route or catalog change, and
 * applies the traveller's position at most every 10 s. Nothing here leaves the phone.
 */
export function useSpotsAhead(route: JourneyRoute | null, progress: RouteProgress | null) {
  const store = useSpotCatalogStore() ?? idleStore;
  const catalog = useSyncExternalStore(store.subscribe, store.getState);
  const matched = useMemo(
    () => (route && catalog.catalog ? matchSpotsToRoute(catalog.catalog, route) : []),
    [route, catalog.catalog],
  );
  const hasFix = progress !== null && !progress.fromStart;
  const along = hasFix ? progress.alongMetres : 0;
  const [applied, setApplied] = useState({ along, hasFix });
  const appliedAt = useRef(0);
  const appliedFix = useRef(hasFix);
  useEffect(() => {
    appliedAt.current = 0; // a new route or catalog applies the position immediately
  }, [matched]);
  useEffect(() => {
    const apply = () => {
      appliedAt.current = Date.now();
      appliedFix.current = hasFix;
      setApplied({ along, hasFix });
    };
    // Gaining or losing a fix applies at once; movement is applied at most every 10 s.
    const wait =
      hasFix !== appliedFix.current ? 0 : appliedAt.current + SPOTS_RECOMPUTE_MS - Date.now();
    if (wait <= 0) {
      apply();
      return;
    }
    const timer = setTimeout(apply, wait);
    return () => clearTimeout(timer);
  }, [along, hasFix, matched]);
  const ahead = useMemo(
    () => spotsAhead(matched, applied.along, applied.hasFix),
    [matched, applied],
  );
  // "(from start)" follows the same applied snapshot as the list, never the live position.
  return { store, catalog, matched, ahead, fromStart: !applied.hasFix };
}

export interface SpotsPanelProps {
  accountId: string;
  journey: CurrentJourney;
  hasRoute: boolean;
  spots: ReturnType<typeof useSpotsAhead>;
  now: number;
  /** Mirrors activity to the map; null when the panel's controller goes away. */
  onActivity(state: SpotActivityState | null): void;
}

/** Container for the Spots-ahead panel: activity refresh and the panel's own UI state. */
export function SpotsPanel(props: SpotsPanelProps) {
  const session = useNativeAccount();
  const [size, setSize] = useState<SpotsPanelSize>('collapsed');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [activity, setActivity] = useState<SpotActivityState>({
    activity: null,
    receivedAt: null,
    status: 'idle',
  });
  const focused = useRef(false);
  const foreground = useRef(AppState.currentState === 'active');
  const env = useRef({ online: session.online, confirmed: false, account: '', journey: '' });
  env.current = {
    online: session.online,
    confirmed: props.journey.confirmed && !props.journey.blocked,
    account: session.accountId ?? '',
    journey: props.journey.id,
  };
  const accountId = props.accountId;
  const journeyId = props.journey.id;
  const fetchRef = useRef(session.fetchSpotActivity);
  fetchRef.current = session.fetchSpotActivity;
  const onActivity = useRef(props.onActivity);
  onActivity.current = props.onActivity;
  const { store } = props.spots;
  const refreshedFor = useRef<string | null>(null);

  // One controller per account and journey: switching either drops all activity state. Created
  // in an effect so a remount (or StrictMode's double effects) always gets a live controller.
  const [controller, setController] = useState<SpotActivityController | null>(null);
  useEffect(() => {
    const created = createSpotActivityController(
      {
        eligible: () =>
          focused.current &&
          foreground.current &&
          env.current.online &&
          env.current.confirmed &&
          env.current.account === accountId &&
          env.current.journey === journeyId,
        fetch: (ids, signal) => fetchRef.current(ids, signal),
        now: Date.now,
        schedule: (run, delay) => {
          const timer = setTimeout(run, delay);
          return () => clearTimeout(timer);
        },
        onCatalogVersion: (version) => {
          // One catalog refresh per announced version, so a mismatch cannot loop.
          if (store.version() === version || refreshedFor.current === version) return;
          refreshedFor.current = version;
          void store.refresh();
        },
      },
      (state) => {
        setActivity(state);
        onActivity.current(state);
      },
    );
    setController(created);
    setActivity(created.getState());
    setSelectedId(null);
    return () => {
      created.dispose();
      onActivity.current(null);
    };
  }, [accountId, journeyId, store]);
  useEffect(
    () => controller?.environmentChanged(),
    [controller, session.online, props.journey.confirmed, props.journey.blocked, session.accountId],
  );
  const ids = activityRequestIds(props.spots.ahead);
  const idsKey = ids.join(',');
  useEffect(() => controller?.setSpotIds(idsKey ? idsKey.split(',') : []), [controller, idsKey]);

  useFocusEffect(
    useCallback(() => {
      focused.current = true;
      foreground.current = AppState.currentState === 'active';
      if (env.current.online) void store.refresh(); // SPOTS_SPEC: refresh on opening Journey mode
      controller?.environmentChanged();
      const change = AppState.addEventListener('change', (state) => {
        foreground.current = state === 'active';
        controller?.environmentChanged();
      });
      return () => {
        change.remove();
        focused.current = false;
        controller?.environmentChanged();
      };
    }, [controller, store]),
  );

  const model = spotsPanelModel({
    catalog: {
      loaded: props.spots.catalog.loaded,
      available: props.spots.catalog.catalog !== null,
      refreshing: props.spots.catalog.refreshing,
    },
    hasRoute: props.hasRoute,
    matchedCount: props.spots.matched.length,
    ahead: props.spots.ahead,
    fromStart: props.spots.fromStart,
    activity,
    online: session.online,
    confirmed: props.journey.confirmed && !props.journey.blocked,
    now: props.now,
    size,
    selectedId,
  });
  // The 20 s cadence applies only while a detail is actually on screen.
  const detailVisible = model.rows.some((row) => row.selected);
  useEffect(() => controller?.setDetailOpen(detailVisible), [controller, detailVisible]);
  const selectedSpot = detailVisible
    ? (props.spots.ahead.find((entry) => entry.spot.id === selectedId)?.spot ?? null)
    : null;
  const controls = useSpotContributionControls({
    spot: selectedSpot,
    entry: activity.activity?.spots.find((entry) => entry.id === selectedSpot?.id) ?? null,
    journeyId: journeyId,
    online: session.online,
    now: props.now,
    refreshSoon: () => controller?.refreshSoon(),
  });
  return <SpotsPanelView {...model} {...controls} onSelect={setSelectedId} onResize={setSize} />;
}
