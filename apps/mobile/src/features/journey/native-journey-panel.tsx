import { Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import { useNativeAccount } from '../../auth/native-account-provider';
import { NativeMapPreview } from './native-map';

const c = tokens.colors;
export function NativeJourneyPanel() {
  const session = useNativeAccount();
  const active = session.partition?.snapshots.journeys.find((item) => item.status === 'active');
  const pending = session.partition?.outbox.entries ?? [];
  return (
    <View style={styles.section}>
      <Text style={styles.heading}>Active journey</Text>
      {!session.configured ? (
        <Text style={styles.notice}>
          Server journeys are unavailable in this build. Your plans stay on this device.
        </Text>
      ) : session.restoring ? (
        <Text style={styles.notice}>Verifying your session…</Text>
      ) : !session.accountId ? (
        <Text style={styles.notice}>Sign in from Profile to start and recover a journey.</Text>
      ) : (
        <>
          <Text style={styles.notice}>
            {session.online ? 'Connected' : 'Offline'} · Journey actions are saved on this device
            before delivery. Plans below remain separate.
          </Text>
          {active ? (
            <View style={styles.item}>
              <Text style={styles.itemTitle}>
                {active.kind === 'commute' ? 'Daily commute' : 'Trip'} in progress
              </Text>
              <Text style={styles.notice}>
                Started {new Date(active.startedAt).toLocaleString()}
              </Text>
              <Pressable
                style={styles.secondary}
                accessibilityRole="button"
                disabled={session.busy}
                onPress={() => void session.complete(active.id)}
              >
                <Text style={styles.secondaryText}>Complete journey</Text>
              </Pressable>
            </View>
          ) : pending.length === 0 ? (
            <View style={styles.actions}>
              <Pressable
                style={styles.primary}
                accessibilityRole="button"
                disabled={session.busy}
                onPress={() => void session.start('trip')}
              >
                <Text style={styles.primaryText}>Start trip now</Text>
              </Pressable>
              <Pressable
                style={styles.secondary}
                accessibilityRole="button"
                disabled={session.busy}
                onPress={() => void session.start('commute')}
              >
                <Text style={styles.secondaryText}>Start commute now</Text>
              </Pressable>
            </View>
          ) : null}
          {pending.length > 0 ? (
            <Text style={styles.notice} accessibilityRole="alert">
              {pending.length} journey action{pending.length === 1 ? '' : 's'} saved ·
              {pending[0]?.blocked
                ? ` Needs attention: ${pending[0].blocked}.`
                : session.online
                  ? ' Sending or waiting to retry.'
                  : ' Will retry when connected.'}
            </Text>
          ) : null}
          {session.error ? (
            <Text style={styles.error} accessibilityRole="alert">
              {session.error}
            </Text>
          ) : null}
          <Pressable
            style={styles.secondary}
            accessibilityRole="button"
            disabled={session.busy || !session.online}
            onPress={() => void session.sync()}
          >
            <Text style={styles.secondaryText}>
              {session.busy ? 'Updating…' : 'Refresh journeys'}
            </Text>
          </Pressable>
          <NativeMapPreview />
          <Text style={styles.subheading}>Recent journeys</Text>
          {session.partition?.snapshots.journeys.length ? (
            session.partition.snapshots.journeys.slice(0, 10).map((item) => (
              <Text key={item.id} style={styles.history}>
                {item.kind === 'commute' ? 'Commute' : 'Trip'} ·{' '}
                {item.status === 'active' ? 'Active' : 'Completed'}
                {' · '}
                {new Date(item.startedAt).toLocaleDateString()}
              </Text>
            ))
          ) : (
            <Text style={styles.notice}>No server journeys yet.</Text>
          )}
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    paddingTop: 8,
    paddingBottom: 22,
    borderBottomWidth: 1,
    borderBottomColor: c.line,
    marginBottom: 22,
  },
  heading: { color: c.ink, fontSize: 23, fontWeight: '600', marginBottom: 7 },
  subheading: { color: c.ink, fontSize: 18, fontWeight: '600', marginTop: 12 },
  notice: { color: c.muted, fontSize: 14, lineHeight: 21, marginBottom: 12 },
  error: { color: c.danger, fontSize: 14, lineHeight: 21, marginBottom: 12 },
  item: { backgroundColor: c.surface, borderRadius: tokens.radius.sm, padding: 16, marginTop: 6 },
  itemTitle: { color: c.ink, fontSize: 17, fontWeight: '600', marginBottom: 5 },
  actions: { marginTop: 9 },
  primary: {
    minHeight: tokens.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: tokens.radius.sm,
    backgroundColor: c.ink,
    marginBottom: 10,
    padding: 12,
  },
  primaryText: { color: c.surface, fontSize: 15, fontWeight: '600' },
  secondary: {
    minHeight: tokens.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: tokens.radius.sm,
    borderWidth: 1,
    borderColor: c.line,
    marginTop: 8,
    padding: 12,
  },
  secondaryText: { color: c.ink, fontSize: 15, fontWeight: '600' },
  history: {
    color: c.ink,
    fontSize: 14,
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: c.line,
  },
});
