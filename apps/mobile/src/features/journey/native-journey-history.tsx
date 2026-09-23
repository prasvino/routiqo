import { useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import { useNativeAccount } from '../../auth/native-account-provider';
import type { NativeHistoryPage } from './native-history';
import {
  createNativeHistoryController,
  type NativeHistoryState,
} from './native-history-controller';

const c = tokens.colors;
export interface NativeJourneyHistoryViewProps {
  page: NativeHistoryPage | null;
  pageLabel?: 'Latest journeys' | 'Earlier journeys';
  offline: boolean;
  busy: boolean;
  error: boolean;
  authenticationRequired: boolean;
  onLatest(): void;
  onEarlier(): void;
  onRetry(): void;
}

export function NativeJourneyHistoryView({
  page,
  pageLabel = 'Latest journeys',
  offline,
  busy,
  error,
  authenticationRequired,
  onLatest,
  onEarlier,
  onRetry,
}: NativeJourneyHistoryViewProps) {
  return (
    <View style={styles.section}>
      <Text style={styles.heading}>Account journey history</Text>
      <Text style={styles.notice}>
        Browse trips and commutes saved to your account. Local plans stay on this device.
      </Text>
      {page !== null ? <Text style={styles.pageLabel}>{pageLabel}</Text> : null}
      {page !== null && offline ? (
        <Text style={styles.notice}>
          Offline · Showing the retained {pageLabel.toLowerCase()} page.
        </Text>
      ) : null}
      {authenticationRequired ? (
        <Text accessibilityRole="alert" style={styles.error}>
          Sign in again to load account history.
        </Text>
      ) : page === null && !busy ? (
        <Text style={styles.notice}>Load your latest journeys when connected.</Text>
      ) : null}
      {page?.journeys.length === 0 ? (
        <Text style={styles.notice}>No journeys on this page.</Text>
      ) : null}
      {page?.journeys.map((journey) => (
        <View key={journey.id} style={styles.row}>
          <Text style={styles.rowTitle}>
            {journey.kind === 'trip' ? 'Trip' : 'Daily commute'} ·{' '}
            {journey.status === 'completed' ? 'Completed' : 'Active'}
          </Text>
          <Text style={styles.notice}>Started {new Date(journey.startedAt).toLocaleString()}</Text>
          {journey.completedAt ? (
            <Text style={styles.notice}>
              Completed {new Date(journey.completedAt).toLocaleString()}
            </Text>
          ) : null}
        </View>
      ))}
      {busy ? (
        <Text accessibilityRole="alert" style={styles.notice}>
          Loading account history…
        </Text>
      ) : null}
      {error ? (
        <Text accessibilityRole="alert" style={styles.error}>
          Account history is unavailable. Check your connection and retry.
        </Text>
      ) : null}
      {error && !authenticationRequired ? (
        <HistoryButton label="Retry account history" disabled={busy || offline} onPress={onRetry} />
      ) : null}
      {!authenticationRequired ? (
        <HistoryButton
          label={page === null ? 'Load account history' : 'Latest journeys'}
          disabled={busy || offline}
          onPress={onLatest}
        />
      ) : null}
      {page?.next ? (
        <HistoryButton label="Earlier journeys" disabled={busy || offline} onPress={onEarlier} />
      ) : null}
    </View>
  );
}

function HistoryButton({
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
      style={styles.button}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
    >
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>
  );
}

function AccountHistory() {
  const session = useNativeAccount();
  const live = useRef(session);
  live.current = session;
  const [state, setState] = useState<NativeHistoryState>({
    page: null,
    pageLabel: 'Latest journeys',
    busy: false,
    error: false,
    authenticationRequired: false,
  });
  const controller = useRef<ReturnType<typeof createNativeHistoryController> | null>(null);
  useEffect(() => {
    const instance = createNativeHistoryController(
      (before) => live.current.readHistory(before),
      () => live.current.online,
      setState,
    );
    controller.current = instance;
    return () => {
      instance.dispose();
      if (controller.current === instance) controller.current = null;
    };
  }, []);
  return (
    <NativeJourneyHistoryView
      {...state}
      offline={!session.online}
      onLatest={() => void controller.current?.latest()}
      onEarlier={() => void controller.current?.earlier()}
      onRetry={() => void controller.current?.retry()}
    />
  );
}

export function NativeJourneyHistory() {
  const session = useNativeAccount();
  if (!session.configured) return null;
  if (!session.accountId)
    return (
      <View style={styles.section}>
        <Text style={styles.heading}>Account journey history</Text>
        <Text style={styles.notice}>Sign in from Profile to browse account history.</Text>
      </View>
    );
  return <AccountHistory key={`${session.accountId}:${session.historyEpoch}`} />;
}

const styles = StyleSheet.create({
  section: {
    paddingTop: 12,
    paddingBottom: 20,
    borderBottomWidth: 1,
    borderBottomColor: c.line,
    marginBottom: 20,
  },
  heading: { color: c.ink, fontSize: 20, fontWeight: '600', marginBottom: 8 },
  notice: { color: c.muted, fontSize: 14, lineHeight: 21, marginBottom: 7 },
  error: { color: c.danger, fontSize: 14, lineHeight: 21, marginBottom: 8 },
  row: { paddingVertical: 11, borderBottomWidth: 1, borderBottomColor: c.line },
  rowTitle: { color: c.ink, fontSize: 16, fontWeight: '600', marginBottom: 4 },
  pageLabel: { color: c.ink, fontSize: 14, fontWeight: '600', marginBottom: 7 },
  button: {
    minHeight: tokens.touchTarget,
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    marginTop: 8,
    padding: 12,
  },
  buttonText: { color: c.ink, fontSize: 15, fontWeight: '600' },
});
