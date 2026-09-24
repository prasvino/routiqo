import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { randomUUID } from 'expo-crypto';
import { tokens } from '@routiqo/design-tokens';
import type { TripJournal } from '@routiqo/shared';
import { useNativeAccount } from '../../auth/native-account-provider';
import { NativeSessionRequired } from '../../auth/native-account';
import {
  createNativeJournalEditorController,
  type NativeJournalEditorState,
} from './native-journal-editor-controller';

const c = tokens.colors;
export interface NativeJournalEditorViewProps {
  state: NativeJournalEditorState;
  online: boolean;
  onTitle(value: string): void;
  onNotes(value: string): void;
  onClose(): void;
  onSaveDraft(): void;
  onSend(): void;
  onReview(): void;
  onUseAccount(): void;
}
function Action({
  label,
  disabled = false,
  onPress,
}: {
  label: string;
  disabled?: boolean;
  onPress(): void;
}) {
  return (
    <Pressable
      style={styles.action}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
    >
      <Text style={styles.actionText}>{label}</Text>
    </Pressable>
  );
}
export function NativeJournalEditorView({
  state,
  online,
  onTitle,
  onNotes,
  onClose,
  onSaveDraft,
  onSend,
  onReview,
  onUseAccount,
}: NativeJournalEditorViewProps) {
  return (
    <SafeAreaView style={styles.safe}>
      <KeyboardAvoidingView
        style={styles.fill}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <Action label="Close editor" onPress={onClose} />
          <Text style={styles.heading} accessibilityRole="header">
            Edit trip journal
          </Text>
          <Text style={styles.notice}>
            Save a draft on this device, then send it to your account when ready.
          </Text>
          <Text style={styles.label}>Title</Text>
          <TextInput
            style={styles.input}
            accessibilityLabel="Journal title"
            value={state.title}
            onChangeText={onTitle}
            editable={!state.busy}
            maxLength={120}
            autoCapitalize="sentences"
          />
          <Text style={styles.label}>Notes</Text>
          <TextInput
            style={[styles.input, styles.notes]}
            accessibilityLabel="Journal notes"
            value={state.notes}
            onChangeText={onNotes}
            editable={!state.busy}
            maxLength={4000}
            multiline
            textAlignVertical="top"
          />
          {state.draft ? (
            <Text style={styles.notice}>
              {state.dirty
                ? 'A device draft is saved. Your latest changes are unsaved.'
                : 'Draft saved on this device.'}
            </Text>
          ) : null}
          {state.settled ? (
            <Text style={styles.notice} accessibilityRole="alert">
              Saved to your account.
            </Text>
          ) : null}
          {!online ? (
            <Text style={styles.notice} accessibilityRole="alert">
              Offline · Save a device draft now. Account delivery needs a connection.
            </Text>
          ) : null}
          {state.busy ? (
            <Text style={styles.notice} accessibilityRole="alert">
              Working on your journal…
            </Text>
          ) : null}
          {state.failure === 'invalid' ? (
            <Text style={styles.error} accessibilityRole="alert">
              Check the title and notes for unsupported characters or length.
            </Text>
          ) : null}
          {state.failure === 'storage' ? (
            <Text style={styles.error} accessibilityRole="alert">
              {state.draft
                ? 'Could not finish saving on this device. Your previous draft remains available for retry.'
                : 'Could not save this journal on the device. Try again before leaving.'}
            </Text>
          ) : null}
          {state.failure === 'full' ? (
            <Text style={styles.error} accessibilityRole="alert">
              Device journal storage is full. Your current text has not been saved.
            </Text>
          ) : null}
          {state.failure === 'offline' ? (
            <Text style={styles.error} accessibilityRole="alert">
              Connect before saving this journal to your account.
            </Text>
          ) : null}
          {state.failure === 'session' ? (
            <Text style={styles.error} accessibilityRole="alert">
              Sign in again from Profile to continue.
            </Text>
          ) : null}
          {state.failure === 'missing' ? (
            <Text style={styles.error} accessibilityRole="alert">
              This trip journal is no longer available on your account.
            </Text>
          ) : null}
          {state.failure === 'unavailable' ? (
            <Text style={styles.error} accessibilityRole="alert">
              Account journal is unavailable. Retry when connected.
            </Text>
          ) : null}
          {state.failure === 'conflict' ? (
            <Text style={styles.error} accessibilityRole="alert">
              Your account journal changed. Review the latest version before deciding what to keep.
            </Text>
          ) : null}
          <Action label="Save draft on device" disabled={state.busy} onPress={onSaveDraft} />
          <Action label="Save to account" disabled={state.busy || !online} onPress={onSend} />
          {state.failure === 'conflict' ? (
            <Action
              label="Review latest account journal"
              disabled={state.busy || !online}
              onPress={onReview}
            />
          ) : null}
          {state.reviewed ? (
            <View style={styles.review}>
              <Text style={styles.label}>Latest account version</Text>
              <Text style={styles.reviewText}>
                {state.reviewed.annotation.title.trim()
                  ? state.reviewed.annotation.title
                  : 'Trip journal'}
              </Text>
              <Text style={styles.reviewText}>
                {state.reviewed.annotation.notes.trim()
                  ? state.reviewed.annotation.notes
                  : 'No notes added.'}
              </Text>
              <Action label="Use account version" disabled={state.busy} onPress={onUseAccount} />
            </View>
          ) : null}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

export function NativeJournalEditor({
  journal,
  onClosed,
  onChanged,
}: {
  journal: TripJournal;
  onClosed(): void;
  onChanged(journal: TripJournal | null): void;
}) {
  const session = useNativeAccount();
  const live = useRef(session);
  live.current = session;
  const ownerAccount = useRef(session.accountId).current;
  const currentSession = useCallback(() => {
    if (!ownerAccount || live.current.accountId !== ownerAccount) throw new NativeSessionRequired();
    return live.current;
  }, [ownerAccount]);
  const [state, setState] = useState<NativeJournalEditorState>({
    open: false,
    busy: false,
    title: '',
    notes: '',
    journal: null,
    draft: null,
    reviewed: null,
    failure: null,
    dirty: false,
    settled: false,
  });
  const controller = useRef<ReturnType<typeof createNativeJournalEditorController> | null>(null);
  const lastEpoch = useRef(session.historyEpoch);
  useEffect(() => {
    const instance = createNativeJournalEditorController(
      {
        online: () => currentSession().online,
        mutationId: randomUUID,
        cache: (input) => currentSession().cacheJournal(input),
        stored: (id) => currentSession().storedJournal(id),
        save: (draft, previous) => currentSession().saveJournalDraft(draft, previous),
        write: (id, input, signal) => currentSession().writeJournal(id, input, signal),
        read: (id, signal) => currentSession().readJournal(id, signal),
        acknowledge: (id, mutationId, response) =>
          currentSession().acknowledgeJournalDraft(id, mutationId, response),
        discard: (id, mutationId, reviewed) =>
          currentSession().discardJournalDraft(id, mutationId, reviewed),
      },
      setState,
    );
    controller.current = instance;
    void instance.open(journal);
    return () => {
      instance.dispose();
      if (controller.current === instance) controller.current = null;
    };
  }, [journal, currentSession]);
  useEffect(() => {
    if (lastEpoch.current !== session.historyEpoch) {
      lastEpoch.current = session.historyEpoch;
      controller.current?.sessionChanged();
    }
  }, [session.historyEpoch]);
  const close = () => {
    const instance = controller.current;
    if (!instance) return;
    if (instance.close()) {
      onClosed();
      return;
    }
    if (instance.state().busy) {
      Alert.alert('Journal action in progress', 'Wait for the current save to finish.');
      return;
    }
    Alert.alert('Discard unsaved text?', 'The saved device draft will remain available.', [
      { text: 'Keep editing', style: 'cancel' },
      {
        text: 'Discard unsaved text',
        style: 'destructive',
        onPress: () => {
          if (instance.abandon()) onClosed();
        },
      },
    ]);
  };
  const changed = async (action: () => Promise<void>) => {
    const instance = controller.current;
    await action();
    if (instance && controller.current === instance && live.current.accountId === ownerAccount)
      onChanged(instance.state().journal);
  };
  return (
    <Modal
      visible={state.open}
      animationType="none"
      onRequestClose={close}
      presentationStyle="fullScreen"
    >
      <NativeJournalEditorView
        state={state}
        online={session.online}
        onTitle={(title) => controller.current?.edit({ title })}
        onNotes={(notes) => controller.current?.edit({ notes })}
        onClose={close}
        onSaveDraft={() => void changed(() => controller.current?.saveDraft() ?? Promise.resolve())}
        onSend={() => void changed(() => controller.current?.send() ?? Promise.resolve())}
        onReview={() => void controller.current?.reviewLatest()}
        onUseAccount={() =>
          Alert.alert(
            'Use the account version?',
            'This discards your saved device draft and unsaved changes.',
            [
              { text: 'Keep my draft', style: 'cancel' },
              {
                text: 'Use account version',
                style: 'destructive',
                onPress: () =>
                  void changed(() => controller.current?.useAccountVersion() ?? Promise.resolve()),
              },
            ],
          )
        }
      />
    </Modal>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: c.canvas },
  fill: { flex: 1 },
  content: { padding: 22, paddingBottom: 44 },
  heading: { color: c.ink, fontSize: 26, fontWeight: '600', marginTop: 16, marginBottom: 10 },
  notice: { color: c.muted, fontSize: 14, lineHeight: 21, marginBottom: 12 },
  error: { color: c.danger, fontSize: 14, lineHeight: 21, marginBottom: 12 },
  label: { color: c.ink, fontSize: 16, fontWeight: '600', marginBottom: 7 },
  input: {
    minHeight: tokens.touchTarget,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    backgroundColor: c.surface,
    color: c.ink,
    fontSize: 16,
    padding: 12,
    marginBottom: 18,
  },
  notes: { minHeight: 210 },
  action: {
    minHeight: tokens.touchTarget,
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: tokens.radius.sm,
    marginTop: 8,
    padding: 12,
  },
  actionText: { color: c.ink, fontSize: 16, fontWeight: '600' },
  review: { marginTop: 20, paddingTop: 18, borderTopWidth: 1, borderTopColor: c.line },
  reviewText: { color: c.ink, fontSize: 16, lineHeight: 24, marginBottom: 10 },
});
