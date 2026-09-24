import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View, type LayoutChangeEvent } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import type { TripJournal } from '@routiqo/shared';
import { useNativeAccount } from '../../auth/native-account-provider';
import type { NativeJournalDraft } from '../../storage/journal-storage';
import { NativeJournalEditor } from './native-journal-editor';
import type { NativeHistoryPage } from './native-history';
import {
  createNativeJournalController,
  type NativeJournalState,
} from './native-journal-controller';
import {
  createNativeHistoryController,
  type NativeHistoryState,
} from './native-history-controller';

const c = tokens.colors;
const journalTitle = (title: string) => (title.trim() ? title : 'Trip journal');
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
  journal: NativeJournalState;
  onOpenJournal(id: string): void;
  onCloseJournal(): void;
  onRetryJournal(): void;
  onEditJournal(journal: TripJournal): void;
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
  journal,
  onOpenJournal,
  onCloseJournal,
  onRetryJournal,
  onEditJournal,
}: NativeJourneyHistoryViewProps) {
  if (journal.selectedId !== null) {
    const value = journal.journal;
    return (
      <View style={styles.section}>
        <HistoryButton label="Back to history" disabled={false} onPress={onCloseJournal} />
        <Text style={styles.heading} accessibilityRole="header">
          {value?.annotation.title.trim() ? value.annotation.title : 'Trip journal'}
        </Text>
        {offline ? (
          <Text style={styles.notice} accessibilityRole="alert">
            {value
              ? 'Offline · Showing the journal already loaded in this session.'
              : 'Offline · Connect to view this journal.'}
          </Text>
        ) : null}
        {value ? (
          <View>
            <Text style={styles.notice}>
              Started {new Date(value.journey.startedAt).toLocaleString()}
            </Text>
            {value.journey.completedAt ? (
              <Text style={styles.notice}>
                Completed {new Date(value.journey.completedAt).toLocaleString()}
              </Text>
            ) : null}
            {value.annotation.updatedAt ? (
              <Text style={styles.notice}>
                Journal updated {new Date(value.annotation.updatedAt).toLocaleString()}
              </Text>
            ) : null}
            <Text style={styles.journalNotes}>
              {value.annotation.notes.trim()
                ? value.annotation.notes
                : 'No notes have been added to this trip journal.'}
            </Text>
          </View>
        ) : null}
        {value ? (
          <HistoryButton
            label="Edit journal"
            disabled={journal.busy}
            onPress={() => onEditJournal(value)}
          />
        ) : null}
        {journal.busy ? (
          <Text accessibilityRole="alert" style={styles.notice}>
            Loading trip journal…
          </Text>
        ) : null}
        {journal.failure === 'session' || authenticationRequired ? (
          <Text accessibilityRole="alert" style={styles.error}>
            Sign in again from Profile to view this journal.
          </Text>
        ) : null}
        {journal.failure === 'missing' ? (
          <Text accessibilityRole="alert" style={styles.error}>
            This trip journal is no longer available.
          </Text>
        ) : null}
        {journal.failure === 'unavailable' ? (
          <Text accessibilityRole="alert" style={styles.error}>
            Trip journal is unavailable. Check your connection and retry.
          </Text>
        ) : null}
        {!offline && !journal.busy && journal.failure === 'unavailable' ? (
          <HistoryButton label="Retry trip journal" disabled={false} onPress={onRetryJournal} />
        ) : null}
        {!offline && !journal.busy && !value && !journal.failure ? (
          <HistoryButton label="Load trip journal" disabled={false} onPress={onRetryJournal} />
        ) : null}
      </View>
    );
  }
  return (
    <View style={styles.section}>
      <Text style={styles.heading} accessibilityRole="header">
        Account journey history
      </Text>
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
          {journey.kind === 'trip' && journey.status === 'completed' ? (
            <HistoryButton
              label="View journal"
              accessibilityLabel={`View journal for trip started ${new Date(journey.startedAt).toLocaleString()}`}
              disabled={false}
              onPress={() => onOpenJournal(journey.id)}
            />
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
  accessibilityLabel,
  disabled,
  onPress,
}: {
  label: string;
  accessibilityLabel?: string;
  disabled: boolean;
  onPress(): void;
}) {
  return (
    <Pressable
      style={styles.button}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
    >
      <Text style={styles.buttonText}>{label}</Text>
    </Pressable>
  );
}

interface HistoryPositionProps {
  onLayout?: ((event: LayoutChangeEvent) => void) | undefined;
  onNavigate?: (() => void) | undefined;
}

function AccountHistory({
  onLayout,
  onNavigate,
  onEditJournal,
  updatedJournal,
}: HistoryPositionProps & {
  onEditJournal(journal: TripJournal): void;
  updatedJournal: TripJournal | null;
}) {
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
  const journalController = useRef<ReturnType<typeof createNativeJournalController> | null>(null);
  const [journal, setJournal] = useState<NativeJournalState>({
    selectedId: null,
    journal: null,
    busy: false,
    failure: null,
  });
  useEffect(() => {
    const instance = createNativeHistoryController(
      (before) => live.current.readHistory(before),
      () => live.current.online,
      setState,
    );
    controller.current = instance;
    const journalInstance = createNativeJournalController(
      (id, signal) => live.current.readJournal(id, signal),
      () => live.current.online,
      setJournal,
    );
    journalController.current = journalInstance;
    return () => {
      instance.dispose();
      journalInstance.dispose();
      if (controller.current === instance) controller.current = null;
      if (journalController.current === journalInstance) journalController.current = null;
    };
  }, []);
  useEffect(() => {
    if (!session.online) journalController.current?.offline();
  }, [session.online]);
  useEffect(() => {
    if (updatedJournal) journalController.current?.replace(updatedJournal);
  }, [updatedJournal]);
  return (
    <View onLayout={onLayout}>
      <NativeJourneyHistoryView
        {...state}
        offline={!session.online}
        journal={journal}
        onOpenJournal={(id) => {
          void journalController.current?.open(id, state.page);
          onNavigate?.();
        }}
        onCloseJournal={() => {
          journalController.current?.close();
          onNavigate?.();
        }}
        onRetryJournal={() => void journalController.current?.retry()}
        onEditJournal={onEditJournal}
        onLatest={() => void controller.current?.latest()}
        onEarlier={() => void controller.current?.earlier()}
        onRetry={() => void controller.current?.retry()}
      />
    </View>
  );
}

function VerifiedHistory({ onLayout, onNavigate }: HistoryPositionProps) {
  const session = useNativeAccount();
  const live = useRef(session);
  live.current = session;
  const ownerAccount = useRef(session.accountId).current;
  const [entries, setEntries] = useState<
    { draft: NativeJournalDraft | null; journal: TripJournal }[]
  >([]);
  const [libraryBusy, setLibraryBusy] = useState(false);
  const [libraryError, setLibraryError] = useState(false);
  const [editing, setEditing] = useState<TripJournal | null>(null);
  const [updatedJournal, setUpdatedJournal] = useState<TripJournal | null>(null);
  const listRevision = useRef(0);
  const refreshLibrary = useCallback(async () => {
    if (!ownerAccount || live.current.accountId !== ownerAccount) return;
    const request = ++listRevision.current;
    setLibraryBusy(true);
    setLibraryError(false);
    try {
      const result = await live.current.storedJournals();
      if (request === listRevision.current && live.current.accountId === ownerAccount)
        setEntries(result);
    } catch {
      if (request === listRevision.current) {
        setEntries([]);
        setLibraryError(true);
      }
    } finally {
      if (request === listRevision.current) setLibraryBusy(false);
    }
  }, [ownerAccount]);
  useEffect(() => {
    const revisions = listRevision;
    void refreshLibrary();
    return () => {
      revisions.current++;
    };
  }, [session.historyEpoch, refreshLibrary]);
  return (
    <>
      <View style={styles.section}>
        <Text style={styles.heading} accessibilityRole="header">
          Journals on this device
        </Text>
        <Text style={styles.notice}>Private drafts and journals for this signed-in account.</Text>
        {libraryBusy ? (
          <Text style={styles.notice} accessibilityRole="alert">
            Loading saved journals…
          </Text>
        ) : null}
        {libraryError ? (
          <Text style={styles.error} accessibilityRole="alert">
            Saved journals are unavailable on this device.
          </Text>
        ) : null}
        {!libraryBusy && !libraryError && entries.length === 0 ? (
          <Text style={styles.notice}>No journals saved on this device yet.</Text>
        ) : null}
        {entries.map(({ draft, journal }) => (
          <View key={journal.journey.id} style={styles.row}>
            <Text style={styles.rowTitle}>
              {journalTitle(draft?.title ?? journal.annotation.title)}
            </Text>
            <Text style={styles.notice}>
              {draft ? 'Device draft saved' : 'Account journal saved'} · Started{' '}
              {new Date(journal.journey.startedAt).toLocaleString()}
            </Text>
            <HistoryButton
              label="Edit saved journal"
              accessibilityLabel={`Edit saved journal for trip started ${new Date(journal.journey.startedAt).toLocaleString()}`}
              disabled={false}
              onPress={() => setEditing(journal)}
            />
          </View>
        ))}
        <HistoryButton
          label="Refresh device journals"
          disabled={libraryBusy}
          onPress={() => void refreshLibrary()}
        />
      </View>
      <AccountHistory
        key={`${session.accountId}:${session.historyEpoch}`}
        onLayout={onLayout}
        onNavigate={onNavigate}
        onEditJournal={setEditing}
        updatedJournal={updatedJournal}
      />
      {editing ? (
        <NativeJournalEditor
          journal={editing}
          onClosed={() => setEditing(null)}
          onChanged={(current) => {
            if (current) setUpdatedJournal(current);
            void refreshLibrary();
          }}
        />
      ) : null}
    </>
  );
}

export function NativeJourneyHistory({ onLayout, onNavigate }: HistoryPositionProps) {
  const session = useNativeAccount();
  if (!session.configured) return null;
  if (!session.accountId)
    return (
      <View style={styles.section} onLayout={onLayout}>
        <Text style={styles.heading}>Account journey history</Text>
        <Text style={styles.notice}>Sign in from Profile to browse account history.</Text>
      </View>
    );
  return <VerifiedHistory key={session.accountId} onLayout={onLayout} onNavigate={onNavigate} />;
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
  journalNotes: { color: c.ink, fontSize: 16, lineHeight: 24, marginTop: 12 },
});
