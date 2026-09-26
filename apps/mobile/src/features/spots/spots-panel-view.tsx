import { Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import type { SpotRowView, SpotsPanelSize, SpotsPanelViewModel } from './spots-model';

const c = tokens.colors;

export interface SpotsPanelViewProps extends SpotsPanelViewModel {
  onSelect(id: string | null): void;
  onResize(size: SpotsPanelSize): void;
}

function SpotRow({ row, onSelect }: { row: SpotRowView; onSelect(id: string | null): void }) {
  return (
    <View style={[styles.row, row.selected && styles.rowSelected]}>
      <Pressable
        style={styles.rowButton}
        accessibilityRole="button"
        accessibilityLabel={row.accessibilityLabel}
        accessibilityState={{ expanded: row.selected }}
        accessibilityHint={row.selected ? 'Hides the Spot details' : 'Shows the Spot details'}
        onPress={() => onSelect(row.selected ? null : row.id)}
      >
        <View style={styles.rowText}>
          <Text style={styles.name}>{row.name}</Text>
          <Text style={styles.nameTa}>{row.nameTa}</Text>
          <Text style={styles.meta}>
            {row.kind} · {row.distance}
          </Text>
        </View>
        {row.stateLabel ? (
          <Text style={[styles.chip, row.state === 'live' ? styles.chipLive : styles.chipMuted]}>
            {row.stateLabel}
          </Text>
        ) : null}
      </Pressable>
      {row.detail ? (
        <View style={styles.detail}>
          {row.detail.lines.map((line) => (
            <Text key={line} style={styles.detailLine}>
              {line}
            </Text>
          ))}
        </View>
      ) : null}
    </View>
  );
}

/** Spots-ahead panel: text-first, no gestures, every control at least 48 dp. */
export function SpotsPanelView(props: SpotsPanelViewProps) {
  return (
    <View style={styles.panel} accessibilityLabel="Spots ahead">
      <Text style={styles.heading} accessibilityRole="header">
        Spots ahead
      </Text>
      {props.notice ? (
        <Text style={styles.notice} accessibilityLiveRegion="polite">
          {props.notice}
        </Text>
      ) : null}
      {props.freshness ? <Text style={styles.freshness}>{props.freshness}</Text> : null}
      {props.rows.map((row) => (
        <SpotRow key={row.id} row={row} onSelect={props.onSelect} />
      ))}
      {props.sizeAction ? (
        <Pressable
          style={styles.button}
          accessibilityRole="button"
          onPress={() => props.onResize(props.sizeAction!.next)}
        >
          <Text style={styles.buttonText}>{props.sizeAction.label}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {
    backgroundColor: c.surface,
    borderRadius: tokens.radius.sm,
    borderWidth: 1,
    borderColor: c.line,
    padding: 16,
    marginTop: 12,
  },
  heading: { color: c.ink, fontSize: 18, fontWeight: '700' },
  notice: { color: c.muted, fontSize: 15, lineHeight: 22, marginTop: 6 },
  freshness: { color: c.muted, fontSize: 14, lineHeight: 20, marginTop: 4 },
  row: { borderTopWidth: 1, borderTopColor: c.line, marginTop: 8 },
  rowSelected: { backgroundColor: c.soft },
  rowButton: {
    minHeight: tokens.touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingVertical: 10,
  },
  rowText: { flex: 1 },
  name: { color: c.ink, fontSize: 16, fontWeight: '600', lineHeight: 22 },
  nameTa: { color: c.ink, fontSize: 15, lineHeight: 22 },
  meta: { color: c.muted, fontSize: 15, lineHeight: 22 },
  chip: {
    fontSize: 14,
    fontWeight: '600',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: tokens.radius.sm,
    overflow: 'hidden',
    maxWidth: 140,
    textAlign: 'center',
  },
  chipLive: { backgroundColor: c.accent, color: c.surface },
  chipMuted: { backgroundColor: c.sand, color: c.ink },
  detail: { paddingBottom: 12, gap: 4 },
  detailLine: { color: c.ink, fontSize: 15, lineHeight: 22 },
  button: {
    minHeight: tokens.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    padding: 12,
    marginTop: 12,
  },
  buttonText: { color: c.ink, fontSize: 16, fontWeight: '600' },
});
