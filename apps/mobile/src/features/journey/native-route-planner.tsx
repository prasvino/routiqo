import { useCallback, useEffect, useRef, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import {
  AppState,
  Keyboard,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import type { PlaceMatch, RouteMode } from '@routiqo/shared';
import { useNativeAccount } from '../../auth/native-account-provider';
import {
  createNativeRoutingController,
  type NativeRoutingState,
  type RoutingEnd,
} from './native-routing-controller';

const c = tokens.colors;
const modes: RouteMode[] = ['driving', 'walking', 'cycling'];
function Action({
  label,
  disabled = false,
  selected = false,
  onPress,
}: {
  label: string;
  disabled?: boolean;
  selected?: boolean;
  onPress(): void;
}) {
  return (
    <Pressable
      style={[styles.action, disabled && styles.disabled, selected && styles.selected]}
      accessibilityRole="button"
      accessibilityState={{ disabled, selected }}
      disabled={disabled}
      onPress={onPress}
    >
      <Text style={styles.actionText}>{label}</Text>
    </Pressable>
  );
}
export interface NativeRoutePlannerViewProps {
  state: NativeRoutingState;
  online: boolean;
  available: boolean;
  onEdit(end: RoutingEnd, value: string): void;
  onSearch(end: RoutingEnd): void;
  onSelect(end: RoutingEnd, id: string): void;
  onMode(mode: RouteMode): void;
  onSwap(): void;
  onCalculate(): void;
  onAlternative(index: number): void;
  onStep(index: number): void;
}
export function NativeRoutePlannerView({
  state,
  online,
  available,
  onEdit,
  onSearch,
  onSelect,
  onMode,
  onSwap,
  onCalculate,
  onAlternative,
  onStep,
}: NativeRoutePlannerViewProps) {
  const ready = online && available;
  const failure =
    state.failure === 'invalid_query'
      ? 'Enter a city, street or address to search.'
      : state.failure === 'same_place'
        ? 'Choose two different places.'
        : state.failure === 'invalid'
          ? 'Choose valid places and try again.'
          : state.failure === 'coverage'
            ? 'Route coverage is unavailable for these places. Try another area.'
            : state.failure === 'session'
              ? 'Your session needs verification. Sign in from Profile.'
              : state.failure === 'forbidden'
                ? 'Route planning is unavailable for this account.'
                : state.failure === 'rate_limited'
                  ? 'Too many requests. Wait before trying again.'
                  : state.failure === 'unavailable'
                    ? 'Route planning could not finish. Try again when connected.'
                    : null;
  const option = state.route?.routes[state.alternative];
  const steps = option?.steps ?? [];
  const currentStep = steps[state.step];
  return (
    <View style={styles.section}>
      <Text style={styles.heading} accessibilityRole="header">
        Plan a route
      </Text>
      <Text style={styles.notice}>
        Search and select both places, then calculate an estimate. Directions are for manual review;
        this does not follow your position or provide turn alerts.
      </Text>
      {(['origin', 'destination'] as const).map((end) => {
        const endpoint = state[end];
        const label = end === 'origin' ? 'Starting place' : 'Destination';
        return (
          <View key={end} style={styles.endpoint}>
            <Text style={styles.label}>{label}</Text>
            <TextInput
              style={styles.input}
              accessibilityLabel={label}
              value={endpoint.text}
              onChangeText={(value) => onEdit(end, value)}
              maxLength={256}
              autoCorrect={false}
            />
            <Action
              label={
                state.busy === end
                  ? `Searching ${label.toLowerCase()}…`
                  : `Search ${label.toLowerCase()}`
              }
              disabled={!ready || state.busy !== null}
              onPress={() => {
                Keyboard.dismiss();
                onSearch(end);
              }}
            />
            {endpoint.selected ? (
              <Text style={styles.notice}>Selected: {endpoint.selected.label}</Text>
            ) : null}
            {endpoint.selected && endpoint.attribution ? (
              <Text style={styles.attribution}>{endpoint.attribution}</Text>
            ) : null}
            {endpoint.results ? (
              <View>
                <Text style={styles.notice}>
                  {endpoint.results.places.length ? 'Select a match:' : 'No matching places found.'}
                </Text>
                {endpoint.results.places.map((place: PlaceMatch) => (
                  <Action
                    key={place.id}
                    label={place.label}
                    disabled={state.busy !== null}
                    onPress={() => onSelect(end, place.id)}
                  />
                ))}
                <Text style={styles.attribution}>{endpoint.results.attribution}</Text>
              </View>
            ) : null}
          </View>
        );
      })}
      <Text style={styles.label}>Travel mode</Text>
      <View style={styles.modeRow}>
        {modes.map((mode) => (
          <Action
            key={mode}
            label={mode === 'driving' ? 'Driving' : mode === 'walking' ? 'Walking' : 'Cycling'}
            selected={state.mode === mode}
            onPress={() => onMode(mode)}
          />
        ))}
      </View>
      <Action
        label="Swap places"
        disabled={state.busy !== null || !state.origin.selected || !state.destination.selected}
        onPress={onSwap}
      />
      <Action
        label={state.busy === 'route' ? 'Calculating route…' : 'Calculate route'}
        disabled={
          !ready || state.busy !== null || !state.origin.selected || !state.destination.selected
        }
        onPress={() => {
          Keyboard.dismiss();
          onCalculate();
        }}
      />
      {!online ? (
        <Text style={styles.problem} accessibilityRole="alert">
          Offline. Search and calculation need a connection. A loaded estimate remains available for
          review.
        </Text>
      ) : !available ? (
        <Text style={styles.problem} accessibilityRole="alert">
          Route requests are temporarily unavailable. Loaded directions remain available.
        </Text>
      ) : null}
      {failure ? (
        <Text style={styles.problem} accessibilityRole="alert">
          {failure}
        </Text>
      ) : null}
      {state.noRoute ? (
        <Text style={styles.notice} accessibilityRole="alert">
          No route was found between these places for this mode.
        </Text>
      ) : null}
      {state.route && option ? (
        <View style={styles.result}>
          <Text style={styles.heading} accessibilityRole="header">
            Route estimates
          </Text>
          {state.route.routes.map((route, index) => (
            <Action
              key={index}
              label={`Route ${index + 1}: ${(route.distanceMetres / 1000).toFixed(1)} km · about ${Math.max(1, Math.round(route.durationSeconds / 60))} min`}
              selected={state.alternative === index}
              onPress={() => onAlternative(index)}
            />
          ))}
          <Text style={styles.notice}>
            Last calculated {new Date(state.route.calculatedAt).toLocaleString()}.{' '}
            {state.route.provider === 'valhalla' ? 'Valhalla' : 'Mapbox'} estimate. Check road signs
            and conditions; no live traffic updates.
          </Text>
          {state.failure || !ready ? (
            <Text style={styles.notice}>
              Showing the last loaded estimate for these places. Recalculate explicitly when ready.
            </Text>
          ) : null}
          <Text style={styles.label}>Directions</Text>
          {currentStep ? (
            <>
              <Text style={styles.step} accessibilityRole="alert">
                Step {state.step + 1} of {steps.length}: {currentStep.instruction} ·{' '}
                {Math.round(currentStep.distanceMetres)} m
              </Text>
              <View style={styles.modeRow}>
                <Action
                  label="Previous step"
                  disabled={state.step === 0}
                  onPress={() => onStep(state.step - 1)}
                />
                <Action
                  label="Next step"
                  disabled={state.step >= steps.length - 1}
                  onPress={() => onStep(state.step + 1)}
                />
              </View>
            </>
          ) : (
            <Text style={styles.notice}>Turn instructions are unavailable for this estimate.</Text>
          )}
          <Text style={styles.notice}>
            Keep this screen open to review loaded directions offline. No offline map or automatic
            rerouting is downloaded.
          </Text>
        </View>
      ) : null}
    </View>
  );
}

export function NativeRoutePlanner({
  accountId,
  available,
}: {
  accountId: string;
  available: boolean;
}) {
  const session = useNativeAccount();
  const live = useRef({ session, available });
  live.current = { session, available };
  const foreground = useRef(
    AppState.currentState !== 'background' && AppState.currentState !== 'inactive',
  );
  const focused = useRef(false);
  const navigationFocused = useRef(false);
  const [visible, setVisible] = useState(false);
  const [state, setState] = useState<NativeRoutingState>({
    origin: { text: '', selected: null, results: null, attribution: null },
    destination: { text: '', selected: null, results: null, attribution: null },
    mode: 'driving',
    route: null,
    alternative: 0,
    step: 0,
    busy: null,
    failure: null,
    noRoute: false,
  });
  const controller = useRef<ReturnType<typeof createNativeRoutingController> | null>(null);
  const lastEpoch = useRef(session.historyEpoch);
  useEffect(() => {
    const instance = createNativeRoutingController(
      accountId,
      {
        environment: () => ({
          accountId: live.current.session.accountId,
          online: live.current.session.online,
          eligible: live.current.available,
          foreground: foreground.current,
          focused: focused.current,
          sessionEpoch: live.current.session.historyEpoch,
        }),
        search: (query, signal) => live.current.session.searchPlaces(query, signal),
        calculate: (request, signal) => live.current.session.calculateRoute(request, signal),
      },
      setState,
    );
    controller.current = instance;
    return () => {
      instance.dispose();
      controller.current = null;
    };
  }, [accountId]);
  useEffect(() => {
    controller.current?.environmentChanged();
  }, [session.accountId, session.online, available]);
  useEffect(() => {
    if (lastEpoch.current !== session.historyEpoch) {
      lastEpoch.current = session.historyEpoch;
      controller.current?.sessionChanged();
    }
  }, [session.historyEpoch]);
  useFocusEffect(
    useCallback(() => {
      navigationFocused.current = true;
      focused.current = true;
      setVisible(true);
      return () => {
        navigationFocused.current = false;
        focused.current = false;
        setVisible(false);
        controller.current?.suspend();
      };
    }, []),
  );
  useEffect(() => {
    const change = AppState.addEventListener('change', (value) => {
      foreground.current = value !== 'background' && value !== 'inactive';
      if (!foreground.current) controller.current?.suspend();
      setVisible(foreground.current && focused.current);
    });
    const blur =
      Platform.OS === 'android'
        ? AppState.addEventListener('blur', () => {
            focused.current = false;
            setVisible(false);
            controller.current?.suspend();
          })
        : null;
    const focus =
      Platform.OS === 'android'
        ? AppState.addEventListener('focus', () => {
            focused.current = navigationFocused.current;
            setVisible(foreground.current && focused.current);
          })
        : null;
    return () => {
      change.remove();
      blur?.remove();
      focus?.remove();
    };
  }, []);
  if (session.accountId !== accountId) return null;
  return (
    <NativeRoutePlannerView
      state={state}
      online={session.online}
      available={available && visible}
      onEdit={(end, value) => controller.current?.edit(end, value)}
      onSearch={(end) => void controller.current?.search(end)}
      onSelect={(end, id) => controller.current?.select(end, id)}
      onMode={(mode) => controller.current?.mode(mode)}
      onSwap={() => controller.current?.swap()}
      onCalculate={() => void controller.current?.calculate()}
      onAlternative={(index) => controller.current?.alternative(index)}
      onStep={(index) => controller.current?.step(index)}
    />
  );
}

const styles = StyleSheet.create({
  section: { marginTop: 20, paddingTop: 16, borderTopWidth: 1, borderTopColor: c.line },
  heading: { color: c.ink, fontSize: 18, fontWeight: '600', marginBottom: 8 },
  label: { color: c.ink, fontSize: 15, fontWeight: '600', marginTop: 12, marginBottom: 5 },
  notice: { color: c.muted, fontSize: 14, lineHeight: 21, marginTop: 8 },
  problem: { color: c.danger, fontSize: 14, lineHeight: 21, marginTop: 10 },
  attribution: { color: c.muted, fontSize: 12, lineHeight: 18, marginTop: 8 },
  endpoint: { marginTop: 10 },
  input: {
    minHeight: tokens.touchTarget,
    color: c.ink,
    backgroundColor: c.surface,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 8,
    fontSize: 16,
  },
  action: {
    minHeight: tokens.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    padding: 10,
    marginTop: 8,
    flexGrow: 1,
  },
  selected: { backgroundColor: c.soft, borderColor: c.accent },
  disabled: { opacity: 0.5 },
  actionText: { color: c.ink, fontSize: 14, fontWeight: '600', textAlign: 'center' },
  modeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  result: { marginTop: 18 },
  step: { color: c.ink, fontSize: 15, lineHeight: 23, marginTop: 8 },
});
