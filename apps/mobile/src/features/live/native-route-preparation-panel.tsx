import { useCallback, useEffect, useRef, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import { AppState, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import { useNativeAccount } from '../../auth/native-account-provider';
import {
  createNativeRoutePreparationController,
  type NativePreparationState,
} from './native-route-preparation-controller';
import type { createNativeRoutePreparationCoordinator } from './native-route-preparation-coordinator';

const c = tokens.colors;
type Coordinator = ReturnType<typeof createNativeRoutePreparationCoordinator>;
function displayInstant(value: string): string {
  const normalized = value.replace(
    /\.(\d{1,9})Z$/,
    (_, fraction: string) => `.${fraction.padEnd(3, '0').slice(0, 3)}Z`,
  );
  return new Date(normalized).toLocaleString();
}
function Action({
  label,
  disabled,
  onPress,
}: {
  label: string;
  disabled: boolean;
  onPress(): void;
}) {
  return (
    <Pressable
      style={[styles.action, disabled && styles.disabled]}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
    >
      <Text style={styles.actionText}>{label}</Text>
    </Pressable>
  );
}
export interface NativeRoutePreparationViewProps {
  state: NativePreparationState;
  online: boolean;
  available: boolean;
  hasConsent: boolean;
  hasSelection: boolean;
  onCheck(): void;
  onPrepare(): void;
}
export function NativeRoutePreparationView({
  state,
  online,
  available,
  hasConsent,
  hasSelection,
  onCheck,
  onPrepare,
}: NativeRoutePreparationViewProps) {
  const ready = online && available && hasConsent && hasSelection && !state.busy;
  const observed = state.observed !== undefined;
  const problem = !online
    ? 'Offline. Connect before checking or preparing a private route.'
    : !available
      ? 'Private route preparation is temporarily unavailable.'
      : !hasConsent
        ? 'Check and allow private contributions for this journey first.'
        : !hasSelection
          ? 'Calculate a route and select an alternative first.'
          : state.failure === 'conflict'
            ? 'Private route settings changed. Check again before preparing.'
            : state.failure === 'rate_limited'
              ? 'Too many requests. Wait before trying again.'
              : state.failure === 'session'
                ? 'Your session needs verification. Sign in from Profile.'
                : state.failure === 'forbidden'
                  ? 'Private route preparation is unavailable for this account.'
                  : state.failure === 'missing'
                    ? 'This journey is no longer available.'
                    : state.failure === 'expired'
                      ? 'The last observation expired. Check again.'
                      : state.failure === 'no_route'
                        ? 'A fresh route could not be found. Check again before retrying.'
                        : state.failure === 'no_anchors'
                          ? 'No eligible private route area was found. Check again before retrying.'
                          : state.failure === 'unavailable'
                            ? 'Private route preparation could not be confirmed. Check again.'
                            : null;
  let status = 'Private route status is unknown. Check explicitly before preparing.';
  if (state.busy === 'checking') status = 'Checking private route context…';
  else if (state.busy === 'preparing') status = 'Preparing private route…';
  else if (state.acknowledged)
    status = `Private route preparation was confirmed for this selected route. Expires ${displayInstant(state.acknowledged.expiresAt)}.`;
  else if (state.observed === null) status = 'Last checked: no private route context was found.';
  else if (state.observed)
    status =
      'Last checked: a private route context exists. This does not confirm the displayed route was prepared.';
  return (
    <View style={styles.section}>
      <Text style={styles.heading} accessibilityRole="header">
        Private route preparation
      </Text>
      <Text style={styles.notice}>
        This sends your selected endpoints again for a fresh calculation that may differ from the
        displayed estimate. It does not enable contributions, publish a location or make you
        discoverable.
      </Text>
      <Text style={styles.status} accessibilityRole="alert">
        {status}
      </Text>
      {problem ? (
        <Text style={styles.problem} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
      <Action label="Check private route" disabled={!ready} onPress={onCheck} />
      <Action
        label="Prepare private route"
        disabled={!ready || !observed || !!state.acknowledged}
        onPress={onPrepare}
      />
    </View>
  );
}

export function NativeRoutePreparationPanel({
  accountId,
  journeyId,
  available,
  coordinator,
}: {
  accountId: string;
  journeyId: string;
  available: boolean;
  coordinator: Coordinator;
}) {
  const session = useNativeAccount();
  const live = useRef({ session, available, coordinator });
  live.current = { session, available, coordinator };
  const foreground = useRef(
    AppState.currentState !== 'background' && AppState.currentState !== 'inactive',
  );
  const focused = useRef(false);
  const navigationFocused = useRef(false);
  const [visible, setVisible] = useState(false);
  const [state, setState] = useState<NativePreparationState>({
    observed: undefined,
    acknowledged: null,
    busy: null,
    failure: null,
  });
  const controller = useRef<ReturnType<typeof createNativeRoutePreparationController> | null>(null);
  const lastEpoch = useRef(session.historyEpoch);
  useEffect(() => {
    const instance = createNativeRoutePreparationController(
      accountId,
      journeyId,
      {
        environment: () => ({
          accountId: live.current.session.accountId,
          journeyId,
          sessionEpoch: live.current.session.historyEpoch,
          online: live.current.session.online,
          eligible: live.current.available,
          foreground: foreground.current,
          focused: focused.current,
        }),
        consent: () => live.current.coordinator.consent(accountId, journeyId),
        selection: () => live.current.coordinator.selection(),
        read: (signal) => live.current.session.readRouteContext(journeyId, signal),
        bind: (input, signal) => live.current.session.bindRouteContext(journeyId, input, signal),
      },
      setState,
    );
    controller.current = instance;
    const unsubscribe = coordinator.subscribe(() => instance.authorityChanged());
    return () => {
      unsubscribe();
      instance.dispose();
      controller.current = null;
    };
  }, [accountId, journeyId, coordinator]);
  useEffect(() => {
    controller.current?.environmentChanged();
  }, [session.accountId, session.online, available]);
  useEffect(() => {
    if (lastEpoch.current !== session.historyEpoch) {
      lastEpoch.current = session.historyEpoch;
      controller.current?.sessionChanged();
    }
  }, [session.historyEpoch]);
  useEffect(() => {
    const delay = controller.current?.nextExpiryMs();
    if (delay === null || delay === undefined) return;
    const value = state.acknowledged ?? state.observed;
    if (!value) return;
    const timer = setTimeout(
      () => controller.current?.expireFromTimer(value.contextId, value.expiresAt),
      delay,
    );
    return () => clearTimeout(timer);
  }, [state.observed, state.acknowledged]);
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
  const consent = coordinator.consent(accountId, journeyId);
  const selectionSnapshot = coordinator.selection();
  const hasConsent = consent?.sessionEpoch === session.historyEpoch;
  const hasSelection =
    selectionSnapshot.sessionEpoch === session.historyEpoch && selectionSnapshot.selection !== null;
  const current =
    hasConsent &&
    hasSelection &&
    available &&
    session.online &&
    visible &&
    lastEpoch.current === session.historyEpoch;
  return (
    <NativeRoutePreparationView
      state={
        current
          ? state
          : {
              observed: undefined,
              acknowledged: null,
              busy: null,
              failure: null,
            }
      }
      online={session.online}
      available={available && visible}
      hasConsent={hasConsent}
      hasSelection={hasSelection}
      onCheck={() => void controller.current?.check()}
      onPrepare={() => void controller.current?.prepare()}
    />
  );
}

const styles = StyleSheet.create({
  section: { marginTop: 18, paddingTop: 16, borderTopWidth: 1, borderTopColor: c.line },
  heading: { color: c.ink, fontSize: 18, fontWeight: '600', marginBottom: 8 },
  notice: { color: c.muted, fontSize: 14, lineHeight: 21, marginBottom: 12 },
  status: { color: c.ink, fontSize: 14, lineHeight: 21, marginBottom: 8 },
  problem: { color: c.danger, fontSize: 14, lineHeight: 21, marginBottom: 8 },
  action: {
    minHeight: tokens.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: tokens.radius.sm,
    borderWidth: 1,
    borderColor: c.line,
    marginTop: 8,
    padding: 12,
  },
  disabled: { opacity: 0.5 },
  actionText: { color: c.ink, fontSize: 15, fontWeight: '600', textAlign: 'center' },
});
