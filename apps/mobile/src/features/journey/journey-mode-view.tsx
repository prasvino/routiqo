import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import type { PositionCopy } from './journey-mode-model';

const c = tokens.colors;

function Button({
  label,
  onPress,
  disabled = false,
  primary = false,
  hint,
}: {
  label: string;
  onPress(): void;
  disabled?: boolean;
  primary?: boolean;
  hint?: string;
}) {
  return (
    <Pressable
      style={[styles.button, primary && styles.primary, disabled && styles.disabled]}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      accessibilityHint={hint}
      disabled={disabled}
      onPress={onPress}
    >
      <Text style={[styles.buttonText, primary && styles.primaryText]}>{label}</Text>
    </Pressable>
  );
}

export interface JourneyModeViewProps {
  title: string;
  elapsed: string | null;
  startNote: string | null;
  routeNote: string;
  position: PositionCopy;
  /** The map element, or null when the map is unavailable. */
  map: ReactNode;
  mapNotice: string | null;
  follow: boolean | null;
  /** Spots-ahead panel; null hides the slot (Spots flag off). */
  spotsPanel: ReactNode;
  canComplete: boolean;
  confirmingComplete: boolean;
  busy: boolean;
  error: string;
  onClose(): void;
  onAskComplete(): void;
  onCancelComplete(): void;
  onConfirmComplete(): void;
  onPositionAction(): void;
  onToggleFollow(): void;
}

/** Full-screen Journey mode. No text entry; every control is at least 48 dp. */
export function JourneyModeView(props: JourneyModeViewProps) {
  const positionLabel =
    props.position.action === 'settings'
      ? 'Open location settings'
      : props.position.action === 'retry'
        ? 'Try position again'
        : 'Show my position';
  return (
    <View style={styles.screen}>
      <View style={styles.header}>
        <Text style={styles.title} accessibilityRole="header">
          {props.title}
        </Text>
        {props.elapsed ? <Text style={styles.meta}>{props.elapsed} so far</Text> : null}
        {props.startNote ? (
          <Text style={styles.meta} accessibilityLiveRegion="polite">
            {props.startNote}
          </Text>
        ) : null}
      </View>
      <View style={styles.mapArea}>
        {props.map}
        {props.mapNotice ? <Text style={styles.notice}>{props.mapNotice}</Text> : null}
        {props.follow !== null ? (
          <Button
            label={props.follow ? 'Following you' : 'Follow me'}
            onPress={props.onToggleFollow}
            hint="Keeps your position in the centre of the map"
          />
        ) : null}
      </View>
      <Text style={styles.route}>{props.routeNote}</Text>
      <Text
        style={props.position.alert ? styles.problem : styles.notice}
        accessibilityRole={props.position.alert ? 'alert' : undefined}
      >
        {props.position.text}
      </Text>
      {props.position.action ? (
        <Button label={positionLabel} onPress={props.onPositionAction} />
      ) : null}
      {props.spotsPanel}
      {props.error ? (
        <Text style={styles.problem} accessibilityRole="alert">
          {props.error}
        </Text>
      ) : null}
      <View style={styles.actions}>
        {props.confirmingComplete ? (
          <>
            <Text style={styles.notice} accessibilityLiveRegion="polite">
              Complete this journey? Its stored route is removed from this phone.
            </Text>
            <Button
              label="Yes, complete journey"
              primary
              disabled={props.busy}
              onPress={props.onConfirmComplete}
            />
            <Button label="Keep going" onPress={props.onCancelComplete} />
          </>
        ) : (
          <>
            <Button
              label="Close"
              onPress={props.onClose}
              hint="Returns to the app; the journey continues"
            />
            <Button
              label="Complete journey"
              disabled={!props.canComplete || props.busy}
              onPress={props.onAskComplete}
            />
          </>
        )}
      </View>
    </View>
  );
}

export interface JourneyReturnBarProps {
  title: string;
  elapsed: string | null;
  onOpen(): void;
}

/** Persistent "Back to journey" bar shown on every tab while a journey is current. */
export function JourneyReturnBar({ title, elapsed, onOpen }: JourneyReturnBarProps) {
  return (
    <Pressable
      style={styles.bar}
      accessibilityRole="button"
      accessibilityLabel={`Back to journey. ${title}${elapsed ? `, ${elapsed} so far` : ''}`}
      onPress={onOpen}
    >
      <Text style={styles.barTitle}>Back to journey</Text>
      <Text style={styles.barMeta}>
        {title}
        {elapsed ? ` · ${elapsed}` : ''}
      </Text>
    </Pressable>
  );
}

/** Home card for the current journey. */
export function ActiveJourneyCard({ title, elapsed, onOpen }: JourneyReturnBarProps) {
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>{title}</Text>
      {elapsed ? <Text style={styles.notice}>{elapsed} so far</Text> : null}
      <Button label="Open journey map" primary onPress={onOpen} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flexGrow: 1, padding: 16, backgroundColor: c.canvas },
  header: { marginBottom: 8 },
  title: { color: c.ink, fontSize: 22, fontWeight: '700' },
  meta: { color: c.muted, fontSize: 15, lineHeight: 22 },
  mapArea: { marginVertical: 8 },
  route: { color: c.ink, fontSize: 16, lineHeight: 24, marginTop: 8 },
  notice: { color: c.muted, fontSize: 15, lineHeight: 22, marginTop: 6 },
  problem: { color: c.danger, fontSize: 15, lineHeight: 22, marginTop: 6 },
  actions: { marginTop: 16, gap: 8 },
  button: {
    minHeight: tokens.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    padding: 12,
    marginTop: 8,
  },
  primary: { backgroundColor: c.ink, borderColor: c.ink },
  disabled: { opacity: 0.5 },
  buttonText: { color: c.ink, fontSize: 16, fontWeight: '600', textAlign: 'center' },
  primaryText: { color: c.surface },
  bar: {
    minHeight: tokens.touchTarget,
    backgroundColor: c.ink,
    paddingHorizontal: 16,
    paddingVertical: 10,
    justifyContent: 'center',
  },
  barTitle: { color: c.surface, fontSize: 16, fontWeight: '700' },
  barMeta: { color: c.surface, fontSize: 14 },
  card: {
    backgroundColor: c.surface,
    borderRadius: tokens.radius.sm,
    padding: 16,
    marginBottom: 16,
  },
  cardTitle: { color: c.ink, fontSize: 18, fontWeight: '600' },
});
