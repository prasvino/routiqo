import { useState } from 'react';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import {
  createPlanningBackup,
  parsePlanningBackup,
  mergePlanningBackup,
  MAX_BACKUP_BYTES,
  type PlanningBackup,
  type RestoreSummary,
} from '@routiqo/shared';
import { tokens } from '@routiqo/design-tokens';
import { useMobilePlanning } from '../../storage/planning';
const c = tokens.colors;

export function NativePlanningBackup() {
  const { ready, error: storageError, snapshot, update } = useMobilePlanning();
  const [mode, setMode] = useState<'export' | 'restore' | null>(null);
  const [text, setText] = useState('');
  const [backup, setBackup] = useState<PlanningBackup | null>(null);
  const [summary, setSummary] = useState<RestoreSummary | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  function close() {
    if (busy) return;
    setMode(null);
    setText('');
    setBackup(null);
    setSummary(null);
    setError('');
  }
  async function openExport() {
    setMode('export');
    setBusy(true);
    setError('');
    setNotice('');
    try {
      setText(createPlanningBackup(await snapshot()));
    } catch {
      setError('Backup could not be read. Your saved plans have not changed.');
    } finally {
      setBusy(false);
    }
  }
  async function preview() {
    setBusy(true);
    setError('');
    setBackup(null);
    setSummary(null);
    try {
      const parsed = parsePlanningBackup(text);
      const result = mergePlanningBackup(await snapshot(), parsed.data);
      setBackup(parsed);
      setSummary(result.summary);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'This backup could not be read.');
    } finally {
      setBusy(false);
    }
  }
  async function restore() {
    if (!backup || busy) return;
    setBusy(true);
    setError('');
    try {
      let restored: RestoreSummary | undefined;
      await update((current) => {
        const result = mergePlanningBackup(current, backup.data);
        restored = result.summary;
        return result.state;
      });
      setNotice(
        `Restored ${restored?.addedPlans ?? 0} new plans and ${restored?.addedPlaces ?? 0} saved places. Existing plans were kept.`,
      );
      setMode(null);
      setText('');
      setBackup(null);
      setSummary(null);
    } catch {
      setError('Restore could not be saved. Your existing plans have not changed.');
    } finally {
      setBusy(false);
    }
  }
  async function share() {
    setBusy(true);
    setError('');
    try {
      const result = await Share.share(
        { title: 'Routiqo planning backup', message: text },
        { dialogTitle: 'Keep your Routiqo backup' },
      );
      if (result.action !== Share.dismissedAction)
        setNotice('Check your chosen app to make sure the complete backup text was saved.');
    } catch {
      setError('Sharing is unavailable. You can select and copy the backup text below.');
    } finally {
      setBusy(false);
    }
  }
  function action(label: string, onPress: () => void, disabled = false, primary = false) {
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityState={{ disabled }}
        disabled={disabled}
        onPress={onPress}
        style={[s.button, primary && s.primary, disabled && s.disabled]}
      >
        <Text style={[s.buttonText, primary && s.primaryText]}>{label}</Text>
      </Pressable>
    );
  }
  return (
    <View>
      {action(
        'Export planning backup text',
        () => {
          void openExport();
        },
        !ready || Boolean(storageError),
      )}
      {action(
        'Restore from backup text',
        () => {
          setMode('restore');
          setNotice('');
        },
        !ready || Boolean(storageError),
      )}
      {notice ? (
        <Text accessibilityLiveRegion="polite" style={s.copy}>
          {notice}
        </Text>
      ) : null}
      <Modal visible={mode !== null} animationType="slide" onRequestClose={close}>
        <SafeAreaView style={s.safe}>
          <KeyboardAvoidingView
            style={s.safe}
            behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
          >
            <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
              <Text accessibilityRole="header" style={s.title}>
                {mode === 'export' ? 'Keep a planning backup' : 'Restore your plans'}
              </Text>
              <Text style={s.copy}>
                {mode === 'export'
                  ? 'This unencrypted text includes route names, dates, notes and saved places. Keep it somewhere private. Share only with an app you trust.'
                  : 'Paste a complete Routiqo JSON backup, up to 512 KB. Review it before restoring. Existing edits are kept; nothing is uploaded.'}
              </Text>
              {mode === 'export' ? (
                <ScrollView nestedScrollEnabled style={s.preview}>
                  <Text selectable style={s.code}>
                    {text || (busy ? 'Reading saved plans…' : 'No backup text available.')}
                  </Text>
                </ScrollView>
              ) : (
                <TextInput
                  accessibilityLabel="Routiqo backup JSON"
                  multiline
                  value={text}
                  editable={!busy}
                  maxLength={MAX_BACKUP_BYTES}
                  autoCapitalize="none"
                  autoCorrect={false}
                  placeholder="Paste backup JSON here"
                  placeholderTextColor={c.muted}
                  onChangeText={(value) => {
                    setText(value);
                    setBackup(null);
                    setSummary(null);
                    setError('');
                  }}
                  style={s.input}
                />
              )}
              {summary && (
                <Text style={s.copy}>
                  New plans: {summary.addedPlans}. New saved places: {summary.addedPlaces}. Existing
                  plans kept: {summary.keptPlans}. Times use this device’s local timezone.
                </Text>
              )}
              {error ? (
                <Text accessibilityRole="alert" style={s.error}>
                  {error}
                </Text>
              ) : null}
              {busy ? (
                <Text accessibilityLiveRegion="polite" style={s.copy}>
                  Working…
                </Text>
              ) : null}
              {mode === 'export'
                ? action(
                    'Share backup text',
                    () => {
                      void share();
                    },
                    busy || !text,
                    true,
                  )
                : backup
                  ? action(
                      'Restore backup',
                      () => {
                        void restore();
                      },
                      busy,
                      true,
                    )
                  : action(
                      'Review backup',
                      () => {
                        void preview();
                      },
                      busy || !text,
                      true,
                    )}
              {action('Close', close, busy)}
            </ScrollView>
          </KeyboardAvoidingView>
        </SafeAreaView>
      </Modal>
    </View>
  );
}
const s = StyleSheet.create({
  safe: { flex: 1, backgroundColor: c.canvas },
  preview: { maxHeight: 240, marginBottom: 18 },
  content: { padding: 22, paddingBottom: 40 },
  title: { fontSize: 28, color: c.ink, marginBottom: 18 },
  copy: { fontSize: 15, lineHeight: 23, color: c.muted, marginBottom: 18 },
  button: {
    minHeight: 48,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: 10,
    padding: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 16,
  },
  buttonText: { color: c.ink, fontSize: 15 },
  primary: { backgroundColor: c.ink },
  primaryText: { color: '#fff' },
  disabled: { opacity: 0.5 },
  input: {
    minHeight: 180,
    borderWidth: 1,
    borderColor: c.line,
    borderRadius: 10,
    padding: 14,
    fontSize: 16,
    color: c.ink,
    backgroundColor: c.surface,
    textAlignVertical: 'top',
    marginBottom: 18,
  },
  code: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 12,
    color: c.ink,
    padding: 14,
    backgroundColor: c.soft,
    marginBottom: 18,
  },
  error: { color: c.danger, fontSize: 15, lineHeight: 22, marginBottom: 18 },
});
