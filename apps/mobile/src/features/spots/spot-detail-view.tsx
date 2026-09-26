import { useState } from 'react';
import { Modal, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import {
  SPOT_POST_MAX_CODE_POINTS,
  SPOT_REPORT_REASONS,
  spotPostTextMessages,
  validateSpotPostText,
  type SpotPostType,
  type SpotReportReason,
  type SpotVote,
} from '@routiqo/shared';
import type { PostView, SignalSummaryView, SpotDetailView } from './spot-detail-model';

const c = tokens.colors;

export interface SpotDetailActions {
  onVote(ref: string, vote: SpotVote): void;
  onReport(ref: string): void;
  onBlock(ref: string): void;
  onDelete(ref: string): void;
  onSignal(category: string, value: string): void;
  onWrite(): void;
}

function Action(props: {
  label: string;
  onPress(): void;
  disabled?: boolean;
  selected?: boolean;
  accessibilityLabel?: string;
}) {
  return (
    <Pressable
      style={[
        styles.action,
        props.selected && styles.actionSelected,
        props.disabled && styles.disabled,
      ]}
      accessibilityRole="button"
      accessibilityLabel={props.accessibilityLabel ?? props.label}
      accessibilityState={{ disabled: !!props.disabled, selected: !!props.selected }}
      disabled={props.disabled}
      onPress={props.onPress}
    >
      <Text style={[styles.actionText, props.selected && styles.actionTextSelected]}>
        {props.label}
      </Text>
    </Pressable>
  );
}

function Votes(props: {
  item: { ref: string; viewerVote: SpotVote | null; canAct: boolean };
  what: string;
  busy: boolean;
  onVote(ref: string, vote: SpotVote): void;
}) {
  const { item } = props;
  return (
    <>
      <Action
        label="Still true"
        accessibilityLabel={`Still true: ${props.what}`}
        selected={item.viewerVote === 'still_true'}
        disabled={!item.canAct || props.busy}
        onPress={() => props.onVote(item.ref, 'still_true')}
      />
      <Action
        label="No longer true"
        accessibilityLabel={`No longer true: ${props.what}`}
        selected={item.viewerVote === 'no_longer_true'}
        disabled={!item.canAct || props.busy}
        onPress={() => props.onVote(item.ref, 'no_longer_true')}
      />
    </>
  );
}

function Summary(props: { item: SignalSummaryView; busy: boolean } & SpotDetailActions) {
  const { item } = props;
  return (
    <View style={styles.item}>
      <Text style={styles.itemTitle}>{item.category}</Text>
      <Text style={styles.itemText}>{item.summary}</Text>
      <Text style={styles.meta}>{item.stillTrue}</Text>
      <View style={styles.actions}>
        <Votes
          item={item}
          what={`${item.category} report`}
          busy={props.busy}
          onVote={props.onVote}
        />
        <Action
          label="Report"
          accessibilityLabel={`Report the ${item.category} summary`}
          disabled={!item.canAct || props.busy}
          onPress={() => props.onReport(item.ref)}
        />
      </View>
    </View>
  );
}

function Post(props: { item: PostView; busy: boolean } & SpotDetailActions) {
  const { item } = props;
  return (
    <View style={styles.item} accessible={false}>
      <Text style={styles.meta}>{item.byline}</Text>
      <Text style={styles.itemText} accessibilityLabel={item.accessibilityLabel}>
        {item.text}
      </Text>
      <Text style={styles.meta}>{item.stillTrue}</Text>
      <View style={styles.actions}>
        {item.mine ? (
          // Deleting your own post stays available in Ghost Mode: it only removes content.
          <Action
            label="Delete my post"
            disabled={props.busy}
            onPress={() => props.onDelete(item.ref)}
          />
        ) : (
          <>
            <Votes item={item} what="this post" busy={props.busy} onVote={props.onVote} />
            <Action
              label="Report"
              accessibilityLabel="Report this post"
              disabled={!item.canAct || props.busy}
              onPress={() => props.onReport(item.ref)}
            />
            <Action
              label="Block"
              accessibilityLabel="Block the author of this post"
              disabled={!item.canAct || props.busy}
              onPress={() => props.onBlock(item.ref)}
            />
          </>
        )}
      </View>
    </View>
  );
}

/** A Spot's content and contribution controls, text-first; every control is at least 48 dp. */
export function SpotDetailContent(
  props: SpotDetailView & SpotDetailActions & { busy: boolean; message: string | null },
) {
  const contribute = props.contribute;
  const actions: SpotDetailActions = {
    onVote: props.onVote,
    onReport: props.onReport,
    onBlock: props.onBlock,
    onDelete: props.onDelete,
    onSignal: props.onSignal,
    onWrite: props.onWrite,
  };
  return (
    <View style={styles.detail}>
      {props.message ? (
        <Text style={styles.message} accessibilityRole="alert" accessibilityLiveRegion="polite">
          {props.message}
        </Text>
      ) : null}
      {props.signals.map((item) => (
        <Summary key={item.ref} item={item} busy={props.busy} {...actions} />
      ))}
      {props.posts.map((item) => (
        <Post key={item.ref} item={item} busy={props.busy} {...actions} />
      ))}
      {props.postsNote ? <Text style={styles.meta}>{props.postsNote}</Text> : null}
      {props.highlights.map((highlight) => (
        <View key={`${highlight.when}:${highlight.text}`} style={styles.item}>
          <Text style={styles.meta}>{highlight.when}</Text>
          <Text style={styles.itemText}>{highlight.text}</Text>
        </View>
      ))}
      {contribute ? (
        <View style={styles.contribute}>
          <Text style={styles.itemTitle} accessibilityRole="header">
            What is it like here?
          </Text>
          {contribute.notice ? <Text style={styles.meta}>{contribute.notice}</Text> : null}
          {contribute.choices.map((choice) => (
            <View key={choice.category}>
              <Text style={styles.meta}>{choice.label}</Text>
              <View style={styles.actions}>
                {choice.values.map((value) => (
                  <Action
                    key={value.value}
                    label={value.label}
                    accessibilityLabel={value.accessibilityLabel}
                    disabled={!contribute.enabled}
                    onPress={() => props.onSignal(choice.category, value.value)}
                  />
                ))}
              </View>
            </View>
          ))}
          <Action label="Write a post" disabled={!contribute.enabled} onPress={props.onWrite} />
          {contribute.waiting.map((line) => (
            <Text key={line} style={styles.meta}>
              {line}
            </Text>
          ))}
        </View>
      ) : null}
    </View>
  );
}

/** Ghost Mode (ADR 0073): device-wide; turning it on stops all Spot sending and clears the queue. */
export function GhostModeSwitch(props: { ghost: boolean | null; onChange(on: boolean): void }) {
  return (
    <View style={styles.ghost}>
      <View style={styles.ghostText}>
        <Text style={styles.itemTitle}>Ghost Mode</Text>
        <Text style={styles.meta}>
          {props.ghost
            ? 'On: nothing is sent from this phone. You can still read Spots.'
            : 'Off. Turn on to stop sending posts, updates, votes and reports, and clear anything waiting to send.'}
        </Text>
      </View>
      <Switch
        accessibilityLabel="Ghost Mode"
        value={props.ghost === true}
        disabled={props.ghost === null}
        onValueChange={props.onChange}
      />
    </View>
  );
}

/** Explicit post composer; never opened automatically. */
export function SpotPostComposer(props: {
  visible: boolean;
  spotName: string;
  defaultType: SpotPostType;
  onSubmit(text: string, type: SpotPostType): Promise<string | null>;
  onClose(): void;
}) {
  const [text, setText] = useState('');
  const [type, setType] = useState<SpotPostType>(props.defaultType);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const count = [...text.trim()].length;
  const close = () => {
    setText('');
    setProblem(null);
    setType(props.defaultType);
    props.onClose();
  };
  const submit = async () => {
    const checked = validateSpotPostText(text);
    if (!checked.ok) {
      setProblem(spotPostTextMessages[checked.problem]);
      return;
    }
    setBusy(true);
    const failure = await props.onSubmit(checked.text, type);
    setBusy(false);
    if (failure) setProblem(failure);
    else close();
  };
  return (
    <Modal
      visible={props.visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={close}
    >
      <View style={styles.sheet}>
        <Text style={styles.sheetTitle} accessibilityRole="header">
          Post at {props.spotName}
        </Text>
        <Text style={styles.meta}>Your post shows a short alias for today, never your name.</Text>
        <View style={styles.actions}>
          <Action
            label="Traffic"
            selected={type === 'traffic'}
            onPress={() => setType('traffic')}
          />
          <Action label="Place tip" selected={type === 'place'} onPress={() => setType('place')} />
        </View>
        <TextInput
          style={styles.input}
          value={text}
          onChangeText={(value) => {
            setText(value);
            setProblem(null);
          }}
          accessibilityLabel="Post text"
          placeholder="What is it like here right now?"
          maxLength={SPOT_POST_MAX_CODE_POINTS * 2}
          multiline={false}
        />
        <Text style={styles.meta}>
          {count}/{SPOT_POST_MAX_CODE_POINTS}
        </Text>
        {problem ? (
          <Text style={styles.message} accessibilityRole="alert">
            {problem}
          </Text>
        ) : null}
        <View style={styles.actions}>
          <Action label="Cancel" onPress={close} />
          <Action label="Post" disabled={busy} onPress={() => void submit()} />
        </View>
      </View>
    </Modal>
  );
}

/** Report reasons (ADR 0072); the report is never shown publicly. */
export function SpotReportSheet(props: {
  visible: boolean;
  onPick(reason: SpotReportReason): void;
  onClose(): void;
}) {
  return (
    <Modal
      visible={props.visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={props.onClose}
    >
      <View style={styles.sheet}>
        <Text style={styles.sheetTitle} accessibilityRole="header">
          Why are you reporting this?
        </Text>
        <Text style={styles.meta}>Moderators review reports. Nobody else sees them.</Text>
        {SPOT_REPORT_REASONS.map((entry) => (
          <Action
            key={entry.reason}
            label={entry.label}
            onPress={() => props.onPick(entry.reason)}
          />
        ))}
        <Action label="Cancel" onPress={props.onClose} />
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  detail: { paddingBottom: 12, gap: 8 },
  item: { borderTopWidth: 1, borderTopColor: c.line, paddingTop: 8, gap: 2 },
  itemTitle: { color: c.ink, fontSize: 16, fontWeight: '600', lineHeight: 22 },
  itemText: { color: c.ink, fontSize: 15, lineHeight: 22 },
  meta: { color: c.muted, fontSize: 14, lineHeight: 20 },
  message: { color: c.ink, fontSize: 15, lineHeight: 22, fontWeight: '600' },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 4 },
  action: {
    minHeight: tokens.touchTarget,
    minWidth: tokens.touchTarget,
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    paddingHorizontal: 12,
  },
  actionSelected: { backgroundColor: c.accent, borderColor: c.accent },
  actionText: { color: c.ink, fontSize: 15, fontWeight: '600' },
  actionTextSelected: { color: c.surface },
  disabled: { opacity: 0.5 },
  contribute: { borderTopWidth: 1, borderTopColor: c.line, paddingTop: 8, gap: 4 },
  ghost: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: tokens.touchTarget,
    paddingVertical: 8,
  },
  ghostText: { flex: 1 },
  sheet: { flex: 1, backgroundColor: c.surface, padding: 20, gap: 12 },
  sheetTitle: { color: c.ink, fontSize: 20, fontWeight: '700' },
  input: {
    minHeight: tokens.touchTarget,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    paddingHorizontal: 12,
    fontSize: 16,
    color: c.ink,
  },
});
