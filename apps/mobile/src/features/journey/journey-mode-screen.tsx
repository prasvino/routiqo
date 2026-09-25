import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { AppState, Linking, ScrollView, StyleSheet, Text } from 'react-native';
import { useFocusEffect, useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { cumulativeRouteMetres } from '@routiqo/shared';
import { tokens } from '@routiqo/design-tokens';
import { useNativeAccount } from '../../auth/native-account-provider';
import { journeyLocation } from './journey-location-expo';
import { JourneyMap, journeyMapStyle, type JourneyMapStatus } from './journey-map';
import {
  currentJourney,
  elapsedLabel,
  journeyMapEnabled,
  journeyTitle,
  positionCopy,
  routeNote,
  routeProgress,
  startNote,
} from './journey-mode-model';
import { JourneyModeView } from './journey-mode-view';
import { useMinuteClock } from './use-minute-clock';

const enabled = journeyMapEnabled();

export function mapNotice(status: JourneyMapStatus): string | null {
  switch (status) {
    case 'unconfigured':
      return 'The map is not configured for this build. Your route summary is shown below.';
    case 'loading':
      return 'Loading map…';
    case 'tiles_failed':
      return 'Map tiles unavailable offline; your route and Spots ahead still work.';
    case 'failed':
      return 'The map could not load. Your route summary is shown below.';
    case 'ready':
      return null;
  }
}

export function JourneyModeScreen() {
  const session = useNativeAccount();
  const router = useRouter();
  const now = useMinuteClock();
  const location = useSyncExternalStore(journeyLocation.subscribe, journeyLocation.getState);
  const [follow, setFollow] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const completing = useRef(false);
  const [mapStatus, setMapStatus] = useState<JourneyMapStatus>(
    journeyMapStyle ? 'loading' : 'unconfigured',
  );
  const journey = currentJourney(session.partition);
  const route =
    journey && session.journeyRoute?.journeyId === journey.id ? session.journeyRoute : null;
  const measures = useMemo(() => (route ? cumulativeRouteMetres(route.geometry) : null), [route]);
  const progress = routeProgress(route, location, measures);

  // Updates run only while this screen is focused and the app is in the foreground.
  useFocusEffect(
    useCallback(() => {
      let foreground = AppState.currentState === 'active';
      void journeyLocation.setActive(foreground);
      const change = AppState.addEventListener('change', (state) => {
        const next = state === 'active';
        if (next === foreground) return;
        foreground = next;
        void journeyLocation.setActive(next);
      });
      return () => {
        change.remove();
        journeyLocation.stop();
      };
    }, []),
  );
  // Account change, sign-out or completion ends Journey mode and forgets the reading.
  const journeyId = journey?.id ?? null;
  useEffect(() => {
    if (enabled && session.accountId && journeyId) return;
    journeyLocation.stop();
    // Leave Journey mode once the completion is saved on this device.
    if (completing.current) {
      completing.current = false;
      router.back();
    }
  }, [session.accountId, journeyId, router]);

  if (!enabled || !session.accountId || !journey)
    return (
      <SafeAreaView style={styles.empty}>
        <Text style={styles.notice}>
          {!enabled
            ? 'Journey mode is not available in this build.'
            : 'No journey is in progress. Start one from Trips.'}
        </Text>
        <Text style={styles.link} accessibilityRole="button" onPress={() => router.back()}>
          Back
        </Text>
      </SafeAreaView>
    );

  const fix = location.fix?.coordinate ?? null;
  return (
    <SafeAreaView style={styles.safe}>
      <ScrollView contentContainerStyle={styles.content}>
        <JourneyModeView
          title={journeyTitle(journey)}
          elapsed={elapsedLabel(journey.startedAt, now)}
          startNote={startNote(journey, session.online)}
          routeNote={routeNote(route, progress)}
          position={positionCopy(location)}
          map={
            <JourneyMap
              route={route}
              position={fix}
              follow={follow}
              onUserPan={() => setFollow(false)}
              onStatus={setMapStatus}
            />
          }
          mapNotice={mapNotice(mapStatus)}
          follow={fix && (mapStatus === 'ready' || mapStatus === 'tiles_failed') ? follow : null}
          spotsPanel={null}
          canComplete={journey.confirmed}
          confirmingComplete={confirming}
          busy={session.busy}
          error={session.error}
          onClose={() => router.back()}
          onAskComplete={() => setConfirming(true)}
          onCancelComplete={() => setConfirming(false)}
          onConfirmComplete={() => {
            setConfirming(false);
            completing.current = true;
            // On failure the journey stays current and the error is shown here.
            void session.complete(journey.id);
          }}
          onPositionAction={() => {
            if (location.status === 'blocked') void Linking.openSettings();
            else if (location.status === 'not_asked' || location.status === 'idle')
              void journeyLocation.request();
            else if (location.status === 'denied') void journeyLocation.request();
            else void journeyLocation.check();
          }}
          onToggleFollow={() => setFollow((value) => !value)}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: tokens.colors.canvas },
  content: { flexGrow: 1 },
  empty: { flex: 1, padding: 24, backgroundColor: tokens.colors.canvas },
  notice: { color: tokens.colors.muted, fontSize: 16, lineHeight: 24 },
  link: {
    color: tokens.colors.ink,
    fontSize: 16,
    fontWeight: '600',
    minHeight: tokens.touchTarget,
    paddingVertical: 12,
  },
});
