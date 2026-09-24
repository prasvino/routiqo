import { useCallback, useEffect, useRef, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import { AppState, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import { useNativeAccount } from '../../auth/native-account-provider';
import {
  createNativeConsentController,
  type NativeConsentState,
} from './native-consent-controller';

const c = tokens.colors;
const maxGeneration = '9223372036854775807';

export interface NativeConsentViewProps {
  state: NativeConsentState;
  available: boolean;
  online: boolean;
  onCheck(): void;
  onAllow(): void;
  onStop(): void;
}

function ConsentAction({
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

export function NativeConsentView({
  state,
  available,
  online,
  onCheck,
  onAllow,
  onStop,
}: NativeConsentViewProps) {
  const ready = available && online && !state.busy;
  const canAllow =
    ready &&
    !state.uncertain &&
    !state.terminal &&
    state.confirmed?.journeyActive === true &&
    state.confirmed.sharing === false &&
    state.confirmed.generation !== maxGeneration;
  let status = 'Last checked: not yet. Check LIVE settings to see the current account setting.';
  if (state.busy)
    status =
      state.notice === 'checking'
        ? 'Checking LIVE settings…'
        : state.notice === 'allowing'
          ? 'Allow request pending…'
          : 'Stop request pending…';
  else if (state.terminal)
    status = 'Last checked: this journey has ended. Private contributions cannot be enabled here.';
  else if (state.notice === 'allowed')
    status = 'Last checked: private contributions allowed for this journey.';
  else if (state.notice === 'stopped')
    status = 'Last checked: private contributions stopped for this journey.';
  else if (state.confirmed)
    status = state.confirmed.sharing
      ? 'Last checked: private contributions were allowed.'
      : 'Last checked: private contributions were off.';
  if (state.uncertain && !state.busy)
    status += ' A previous change may have reached your account. Use Stop to resolve it.';
  const problem = !online
    ? 'Offline. Reconnect before checking or changing LIVE settings.'
    : !available
      ? 'LIVE settings are temporarily unavailable while this journey updates.'
      : state.failure === 'conflict'
        ? 'The account setting changed. Check again, or use Stop to turn contributions off.'
        : state.failure === 'rate_limited'
          ? 'Too many requests. Wait before trying again.'
          : state.failure === 'session'
            ? 'Your session needs verification. Sign in from Profile.'
            : state.failure === 'missing'
              ? 'This journey is no longer available for LIVE settings.'
              : state.failure === 'unavailable'
                ? 'LIVE settings could not be confirmed. Try again when connected.'
                : null;
  return (
    <View style={styles.section}>
      <Text style={styles.heading} accessibilityRole="header">
        Private LIVE contributions
      </Text>
      <Text style={styles.copy}>
        Allowing private contributions does not publish your location or make you discoverable.
        Stopping does not delete saved private contributions or receipts. Public LIVE is
        unavailable.
      </Text>
      <Text style={styles.status} accessibilityRole="alert">
        {status}
      </Text>
      {problem ? (
        <Text style={styles.problem} accessibilityRole="alert">
          {problem}
        </Text>
      ) : null}
      <ConsentAction label="Check LIVE settings" disabled={!ready} onPress={onCheck} />
      <ConsentAction label="Allow private contributions" disabled={!canAllow} onPress={onAllow} />
      <ConsentAction label="Stop private contributions" disabled={!ready} onPress={onStop} />
    </View>
  );
}

export function NativeConsentPanel({
  accountId,
  journeyId,
  available,
}: {
  accountId: string;
  journeyId: string;
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
  const [state, setState] = useState<NativeConsentState>({
    confirmed: null,
    lastGeneration: '0',
    uncertain: false,
    terminal: false,
    busy: false,
    notice: 'unknown',
    failure: null,
  });
  const controller = useRef<ReturnType<typeof createNativeConsentController> | null>(null);
  const lastEpoch = useRef(session.historyEpoch);
  useEffect(() => {
    const instance = createNativeConsentController(
      accountId,
      journeyId,
      {
        environment: () => ({
          accountId: live.current.session.accountId,
          journeyId,
          online: live.current.session.online,
          eligible: live.current.available,
          foreground: foreground.current,
          focused: focused.current,
        }),
        read: (signal) => live.current.session.readLiveConsent(journeyId, signal),
        submit: (input, signal) => live.current.session.submitLiveConsent(journeyId, input, signal),
      },
      setState,
    );
    controller.current = instance;
    return () => {
      instance.dispose();
      controller.current = null;
    };
  }, [accountId, journeyId]);
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
    <NativeConsentView
      state={
        lastEpoch.current === session.historyEpoch
          ? state
          : {
              ...state,
              confirmed: null,
              notice: state.uncertain ? 'interrupted' : 'unknown',
            }
      }
      available={available && visible}
      online={session.online}
      onCheck={() => void controller.current?.check()}
      onAllow={() => void controller.current?.allow()}
      onStop={() => void controller.current?.stop()}
    />
  );
}

const styles = StyleSheet.create({
  section: { marginTop: 18, paddingTop: 16, borderTopWidth: 1, borderTopColor: c.line },
  heading: { color: c.ink, fontSize: 18, fontWeight: '600', marginBottom: 8 },
  copy: { color: c.muted, fontSize: 14, lineHeight: 21, marginBottom: 12 },
  status: { color: c.ink, fontSize: 14, lineHeight: 21, marginBottom: 8 },
  problem: { color: c.danger, fontSize: 14, lineHeight: 21, marginBottom: 8 },
  action: {
    minHeight: tokens.touchTarget,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 12,
    marginTop: 8,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
  },
  disabled: { opacity: 0.5 },
  actionText: { color: c.ink, fontSize: 15, fontWeight: '600', textAlign: 'center' },
});
