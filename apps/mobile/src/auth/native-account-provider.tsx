import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { AppState } from 'react-native';
import NetInfo from '@react-native-community/netinfo';
import { randomUUID } from 'expo-crypto';
import { useSQLiteContext } from 'expo-sqlite';
import { buildJourneyRoute } from '@routiqo/shared';
import type {
  JourneyKind,
  JourneyRoute,
  JourneyRouteSource,
  PlaceResults,
  RouteRequest,
  RouteResult,
} from '@routiqo/shared';
import type { SpotActivity, TripJournal, TripJournalWrite } from '@routiqo/shared';
import { NativeHttpStatus } from './safe-transport';
import { nativeTransport } from './android-transport';
import { nativeGoogle } from './native-google';
import { createNativeAccount, NativeSessionRequired } from './native-account';
import { nativeSessionVault } from './secure-session';
import {
  NativeSpotsError,
  fetchNativeSpotActivity,
  fetchNativeSpotCatalog,
  type SpotCatalogFetch,
} from '../features/spots/native-spots';
import { createNativeJourneys } from '../features/journey/native-journeys';
import {
  readNativeJourneyPage,
  type NativeHistoryCursor,
  type NativeHistoryPage,
} from '../features/journey/native-history';
import { readNativeJournal, writeNativeJournal } from '../features/journey/native-journal';
import {
  calculateNativeRoute,
  searchNativePlaces,
  NativeRoutingError,
} from '../features/journey/native-routing';
import {
  readNativeConsent,
  submitNativeConsent,
  type NativeLiveConsent,
} from '../features/live/native-consent';
import {
  bindNativeRouteContext,
  readNativeRouteContext,
  NativeRouteContextError,
  type NativeRouteBindingInput,
  type NativeRouteBindingResult,
  type NativeRouteContextRead,
} from '../features/live/native-route-context';
import {
  acknowledgeNativeJournalDraft,
  cacheNativeTripJournal,
  discardNativeJournalDraft,
  listNativeStoredJournals,
  readNativeStoredJournal,
  saveNativeJournalDraft,
  type NativeJournalDraft,
} from '../storage/journal-storage';
import {
  clearJourneyPartition,
  clearMobileJourneyRoutes,
  queueMobileJourney,
  readMobileJourneyPartition,
  readMobileJourneyRoute,
} from '../storage/journey-outbox';

type Partition = Awaited<ReturnType<typeof readMobileJourneyPartition>>;
interface NativeAccountContext {
  configured: boolean;
  accountId: string | null;
  partition: Partition | null;
  online: boolean;
  busy: boolean;
  restoring: boolean;
  error: string;
  recentRequired: boolean;
  deletionCleanupPending: boolean;
  historyEpoch: number;
  readHistory(before: NativeHistoryCursor | null): Promise<NativeHistoryPage>;
  readJournal(journeyId: string, signal: AbortSignal): Promise<TripJournal>;
  writeJournal(
    journeyId: string,
    input: TripJournalWrite,
    signal: AbortSignal,
  ): Promise<TripJournal>;
  storedJournal(
    journeyId: string,
  ): Promise<{ draft: NativeJournalDraft | null; journal: TripJournal | null }>;
  storedJournals(): Promise<{ draft: NativeJournalDraft | null; journal: TripJournal }[]>;
  cacheJournal(journal: TripJournal): Promise<TripJournal>;
  saveJournalDraft(
    draft: NativeJournalDraft,
    previousMutationId: string | null,
  ): Promise<NativeJournalDraft>;
  acknowledgeJournalDraft(
    journeyId: string,
    mutationId: string,
    response: TripJournal,
  ): Promise<boolean>;
  discardJournalDraft(
    journeyId: string,
    mutationId: string,
    reviewed: TripJournal,
  ): Promise<TripJournal>;
  readLiveConsent(journeyId: string, signal: AbortSignal): Promise<NativeLiveConsent>;
  submitLiveConsent(
    journeyId: string,
    input: { expectedGeneration: string; sharing: boolean },
    signal: AbortSignal,
  ): Promise<NativeLiveConsent>;
  searchPlaces(query: string, signal: AbortSignal): Promise<PlaceResults>;
  calculateRoute(request: RouteRequest, signal: AbortSignal): Promise<RouteResult>;
  /** Spot catalog revalidation (ETag) and activity reads; only Spot IDs are ever sent. */
  fetchSpotCatalog(ifNoneMatch: string | null, signal: AbortSignal): Promise<SpotCatalogFetch>;
  fetchSpotActivity(spotIds: readonly string[], signal: AbortSignal): Promise<SpotActivity>;
  readRouteContext(journeyId: string, signal: AbortSignal): Promise<NativeRouteContextRead>;
  bindRouteContext(
    journeyId: string,
    input: NativeRouteBindingInput,
    signal: AbortSignal,
  ): Promise<NativeRouteBindingResult>;
  signIn(): Promise<void>;
  signOut(): Promise<void>;
  reauthenticateForDeletion(): Promise<void>;
  deleteAccount(): Promise<void>;
  retryDeletionCleanup(): Promise<void>;
  restore(): Promise<void>;
  sync(): Promise<void>;
  /** Resolves true once the start is saved on this device. */
  start(kind: JourneyKind): Promise<boolean>;
  /** Start with the calculated route; the route stays on this device only (ADR 0067). */
  startWithRoute(kind: JourneyKind, route: JourneyRouteInput): Promise<boolean>;
  complete(id: string): Promise<void>;
  /** Device-only route of the current journey, or null. */
  journeyRoute: JourneyRoute | null;
  clearJourneyRoutes(): Promise<void>;
}
export type JourneyRouteInput = Omit<JourneyRouteSource, 'journeyId'>;
const Context = createContext<NativeAccountContext | null>(null);

export function NativeAccountProvider({ children }: { children: ReactNode }) {
  const db = useSQLiteContext();
  const identity = useMemo(
    () => createNativeAccount(nativeSessionVault, nativeTransport, nativeGoogle),
    [],
  );
  const journeys = useMemo(() => createNativeJourneys(db, identity), [db, identity]);
  const [accountId, setAccountId] = useState<string | null>(null);
  const [partition, setPartition] = useState<Partition | null>(null);
  const [journeyRoute, setJourneyRoute] = useState<JourneyRoute | null>(null);
  const [online, setOnline] = useState(false);
  const onlineRef = useRef(false);
  // Android may report an unknown state during startup while the app is visible.
  const foregroundRef = useRef(
    AppState.currentState !== 'background' && AppState.currentState !== 'inactive',
  );
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [error, setError] = useState('');
  const [recentRequired, setRecentRequired] = useState(false);
  const [deletedAccountId, setDeletedAccountId] = useState<string | null>(null);
  const deletionPendingRef = useRef<string | null>(null);
  const working = useRef(false);
  const dispatchRef = useRef<AbortController | null>(null);
  const historyEpochRef = useRef(0);
  const [historyEpoch, setHistoryEpoch] = useState(0);
  function invalidateHistory() {
    historyEpochRef.current += 1;
    setHistoryEpoch(historyEpochRef.current);
  }
  async function readHistory(before: NativeHistoryCursor | null): Promise<NativeHistoryPage> {
    const account = identity.activeAccount();
    if (!account || accountId !== account) throw new Error('Sign in to load account history.');
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    try {
      const page = await readNativeJourneyPage(identity, account, before);
      if (
        epoch !== historyEpochRef.current ||
        revision !== identity.revision() ||
        identity.activeAccount() !== account
      )
        throw new Error('Account history session changed.');
      return page;
    } catch (failure) {
      if (
        epoch !== historyEpochRef.current ||
        revision !== identity.revision() ||
        identity.activeAccount() !== account
      )
        throw new Error('Account history session changed.');
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeHttpStatus && (failure.status === 401 || failure.status === 403))
      ) {
        invalidateHistory();
        identity.invalidate();
        setAccountId(null);
        setPartition(null);
        setError('Your session needs verification. Sign in from Profile to continue.');
      }
      throw failure;
    }
  }
  async function readJournal(journeyId: string, signal: AbortSignal): Promise<TripJournal> {
    const account = identity.activeAccount();
    if (!account || accountId !== account || deletionPendingRef.current)
      throw new NativeSessionRequired();
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    try {
      const journal = await readNativeJournal(identity, account, journeyId, signal);
      if (
        epoch !== historyEpochRef.current ||
        revision !== identity.revision() ||
        identity.activeAccount() !== account ||
        accountId !== account
      )
        throw new NativeSessionRequired();
      return journal;
    } catch (failure) {
      if (
        epoch !== historyEpochRef.current ||
        revision !== identity.revision() ||
        identity.activeAccount() !== account ||
        accountId !== account
      )
        throw new NativeSessionRequired();
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeHttpStatus && (failure.status === 401 || failure.status === 403))
      ) {
        invalidateHistory();
        identity.invalidate();
        setAccountId(null);
        setPartition(null);
        setError('Your session needs verification. Sign in from Profile to continue.');
      }
      throw failure;
    }
  }
  function verifiedJournalAccount(): string {
    const account = identity.activeAccount();
    if (!account || accountId !== account || deletionPendingRef.current)
      throw new NativeSessionRequired();
    return account;
  }
  async function writeJournal(
    journeyId: string,
    input: TripJournalWrite,
    signal: AbortSignal,
  ): Promise<TripJournal> {
    const account = verifiedJournalAccount();
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    try {
      const result = await writeNativeJournal(identity, account, journeyId, input, signal);
      if (
        epoch !== historyEpochRef.current ||
        revision !== identity.revision() ||
        identity.activeAccount() !== account ||
        accountId !== account
      )
        throw new NativeSessionRequired();
      return result;
    } catch (failure) {
      if (
        epoch !== historyEpochRef.current ||
        revision !== identity.revision() ||
        identity.activeAccount() !== account ||
        accountId !== account
      )
        throw new NativeSessionRequired();
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeHttpStatus && (failure.status === 401 || failure.status === 403))
      ) {
        invalidateHistory();
        identity.invalidate();
        setAccountId(null);
        setPartition(null);
        setError('Your session needs verification. Sign in from Profile to continue.');
      }
      throw failure;
    }
  }
  async function journalRead<T>(operation: (account: string) => Promise<T>): Promise<T> {
    const account = verifiedJournalAccount();
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    const value = await operation(account);
    if (
      epoch !== historyEpochRef.current ||
      revision !== identity.revision() ||
      identity.activeAccount() !== account ||
      accountId !== account ||
      deletionPendingRef.current
    )
      throw new NativeSessionRequired();
    return value;
  }
  const storedJournal = (journeyId: string) =>
    journalRead((account) => readNativeStoredJournal(db, account, journeyId));
  const storedJournals = () => journalRead((account) => listNativeStoredJournals(db, account));
  const cacheJournal = (journal: TripJournal) =>
    journalRead((account) => cacheNativeTripJournal(db, account, journal));
  const saveJournalDraft = (draft: NativeJournalDraft, previousMutationId: string | null) =>
    saveNativeJournalDraft(db, verifiedJournalAccount(), draft, previousMutationId);
  const acknowledgeJournalDraft = (journeyId: string, mutationId: string, response: TripJournal) =>
    acknowledgeNativeJournalDraft(db, verifiedJournalAccount(), journeyId, mutationId, response);
  const discardJournalDraft = (journeyId: string, mutationId: string, reviewed: TripJournal) =>
    discardNativeJournalDraft(db, verifiedJournalAccount(), journeyId, mutationId, reviewed);
  async function liveConsentRequest(
    operation: (account: string) => Promise<NativeLiveConsent>,
  ): Promise<NativeLiveConsent> {
    const account = identity.activeAccount();
    if (!account || accountId !== account || deletionPendingRef.current)
      throw new NativeSessionRequired();
    if (!onlineRef.current || !foregroundRef.current || working.current || restoring || busy)
      throw new Error('LIVE settings are unavailable.');
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    const current = () =>
      epoch === historyEpochRef.current &&
      revision === identity.revision() &&
      identity.activeAccount() === account &&
      accountId === account &&
      !deletionPendingRef.current;
    try {
      const result = await operation(account);
      if (!current()) throw new NativeSessionRequired();
      if (!onlineRef.current || !foregroundRef.current || working.current || restoring || busy)
        throw new Error('LIVE settings are unavailable.');
      return result;
    } catch (failure) {
      if (!current()) throw new NativeSessionRequired();
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeHttpStatus && (failure.status === 401 || failure.status === 403))
      ) {
        invalidateHistory();
        identity.invalidate();
        setAccountId(null);
        setPartition(null);
        setError('Your session needs verification. Sign in from Profile to continue.');
      }
      throw failure;
    }
  }
  const readLiveConsent = (journeyId: string, signal: AbortSignal) =>
    liveConsentRequest((account) => readNativeConsent(identity, account, journeyId, signal));
  const submitLiveConsent = (
    journeyId: string,
    input: { expectedGeneration: string; sharing: boolean },
    signal: AbortSignal,
  ) =>
    liveConsentRequest((account) =>
      submitNativeConsent(identity, account, journeyId, input, signal),
    );
  async function routingRequest<T>(operation: (account: string) => Promise<T>): Promise<T> {
    const account = identity.activeAccount();
    if (!account || accountId !== account || deletionPendingRef.current)
      throw new NativeSessionRequired();
    if (!onlineRef.current || !foregroundRef.current || working.current || restoring || busy)
      throw new Error('Route planning is unavailable.');
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    const current = () =>
      epoch === historyEpochRef.current &&
      revision === identity.revision() &&
      identity.activeAccount() === account &&
      accountId === account &&
      !deletionPendingRef.current;
    try {
      const result = await operation(account);
      if (!current()) throw new NativeSessionRequired();
      if (!onlineRef.current || !foregroundRef.current || working.current || restoring || busy)
        throw new Error('Route planning is unavailable.');
      return result;
    } catch (failure) {
      if (!current()) throw new NativeSessionRequired();
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeRoutingError &&
          (failure.code === 'session' || failure.status === 401))
      ) {
        invalidateHistory();
        identity.invalidate();
        setAccountId(null);
        setPartition(null);
        setError('Your session needs verification. Sign in from Profile to continue.');
      }
      throw failure;
    }
  }
  async function spotsRequest<T>(operation: (account: string) => Promise<T>): Promise<T> {
    const account = identity.activeAccount();
    if (!account || accountId !== account || deletionPendingRef.current)
      throw new NativeSessionRequired();
    if (!onlineRef.current || !foregroundRef.current || restoring)
      throw new NativeSpotsError('unavailable');
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    const current = () =>
      epoch === historyEpochRef.current &&
      revision === identity.revision() &&
      identity.activeAccount() === account &&
      accountId === account &&
      !deletionPendingRef.current;
    try {
      const result = await operation(account);
      if (!current()) throw new NativeSessionRequired();
      return result;
    } catch (failure) {
      if (!current()) throw new NativeSessionRequired();
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeSpotsError && failure.code === 'session')
      ) {
        invalidateHistory();
        identity.invalidate();
        setAccountId(null);
        setPartition(null);
        setError('Your session needs verification. Sign in from Profile to continue.');
      }
      throw failure;
    }
  }
  const fetchSpotCatalog = (ifNoneMatch: string | null, signal: AbortSignal) =>
    spotsRequest((account) => fetchNativeSpotCatalog(identity, account, ifNoneMatch, signal));
  const fetchSpotActivity = (spotIds: readonly string[], signal: AbortSignal) =>
    spotsRequest((account) => fetchNativeSpotActivity(identity, account, spotIds, signal));
  const searchPlaces = (query: string, signal: AbortSignal) =>
    routingRequest((account) => searchNativePlaces(identity, account, query, signal));
  const calculateRoute = (request: RouteRequest, signal: AbortSignal) =>
    routingRequest((account) => calculateNativeRoute(identity, account, request, signal));
  async function routeContextRequest<T>(
    journeyId: string,
    operation: (account: string) => Promise<T>,
  ): Promise<T> {
    const account = identity.activeAccount();
    if (!account || accountId !== account || deletionPendingRef.current)
      throw new NativeSessionRequired();
    const eligible = () =>
      onlineRef.current &&
      foregroundRef.current &&
      !working.current &&
      !restoring &&
      !busy &&
      partition?.snapshots.journeys.some(
        (item) => item.id === journeyId && item.status === 'active',
      ) &&
      partition.outbox.entries.length === 0;
    if (!eligible()) throw new NativeRouteContextError('unavailable');
    const epoch = historyEpochRef.current;
    const revision = identity.revision();
    const current = () =>
      epoch === historyEpochRef.current &&
      revision === identity.revision() &&
      identity.activeAccount() === account &&
      accountId === account &&
      !deletionPendingRef.current;
    try {
      const result = await operation(account);
      if (!current()) throw new NativeSessionRequired();
      if (!eligible()) throw new NativeRouteContextError('unavailable');
      return result;
    } catch (failure) {
      if (!current()) throw new NativeSessionRequired();
      if (
        failure instanceof NativeSessionRequired ||
        (failure instanceof NativeRouteContextError &&
          (failure.code === 'session' || failure.status === 401))
      ) {
        invalidateHistory();
        identity.invalidate();
        setAccountId(null);
        setPartition(null);
        setError('Your session needs verification. Sign in from Profile to continue.');
      }
      throw failure;
    }
  }
  const readRouteContext = (journeyId: string, signal: AbortSignal) =>
    routeContextRequest(journeyId, (account) =>
      readNativeRouteContext(identity, account, journeyId, signal),
    );
  const bindRouteContext = (
    journeyId: string,
    input: NativeRouteBindingInput,
    signal: AbortSignal,
  ) =>
    routeContextRequest(journeyId, (account) =>
      bindNativeRouteContext(identity, account, journeyId, input, signal),
    );

  async function load(account: string) {
    const value = await readMobileJourneyPartition(db, account);
    let route: JourneyRoute | null = null;
    try {
      route = await readMobileJourneyRoute(db, account);
    } catch {
      route = null; // The map degrades to "no route"; journey actions are unaffected.
    }
    if (identity.activeAccount() === account) {
      setPartition(value);
      setJourneyRoute(route);
    }
  }
  async function sync() {
    const account = identity.activeAccount();
    if (!account || !onlineRef.current || !foregroundRef.current || working.current) return;
    working.current = true;
    setBusy(true);
    let renewAfterFailure = false;
    const dispatch = new AbortController();
    dispatchRef.current = dispatch;
    try {
      await journeys.dispatch(account, dispatch.signal);
      if (dispatch.signal.aborted || !foregroundRef.current) return;
      await journeys.refresh(account);
      await load(account);
      setError('');
    } catch (failure) {
      renewAfterFailure = identity.isAuthenticationError(failure);
      if (identity.activeAccount() === account)
        setError('Journey update is unavailable. Your saved actions remain on this device.');
    } finally {
      if (dispatchRef.current === dispatch) dispatchRef.current = null;
      working.current = false;
      setBusy(false);
    }
    if (renewAfterFailure) void refreshSession(true);
  }
  async function restore() {
    if (!identity.configured || working.current || deletionPendingRef.current) return;
    invalidateHistory();
    working.current = true;
    setRestoring(true);
    setAccountId(null);
    setPartition(null);
    try {
      const account = await identity.restore();
      setAccountId(account);
      if (account) {
        await clearMobileJourneyRoutes(db, { except: account });
        await load(account);
        await journeys.resume(account);
      }
      setError('');
    } catch {
      setError('Could not verify your session. Reconnect and retry. Local plans are available.');
    } finally {
      working.current = false;
      setRestoring(false);
    }
    if (identity.activeAccount() && onlineRef.current && foregroundRef.current) void sync();
  }
  async function signIn() {
    if (working.current || !identity.configured || deletionPendingRef.current) return;
    invalidateHistory();
    working.current = true;
    setBusy(true);
    setError('');
    setAccountId(null);
    setPartition(null);
    try {
      const account = await identity.signIn();
      if (account) {
        setAccountId(account);
        await clearMobileJourneyRoutes(db, { except: account });
        await load(account);
        await journeys.resume(account);
      }
    } catch {
      setError('Sign-in could not finish. Check your connection and try again.');
    } finally {
      working.current = false;
      setBusy(false);
    }
    if (identity.activeAccount() && onlineRef.current && foregroundRef.current) void sync();
  }
  async function signOut() {
    if (working.current) return;
    invalidateHistory();
    working.current = true;
    setBusy(true);
    setError('');
    const leaving = identity.activeAccount();
    setAccountId(null);
    setPartition(null);
    try {
      await identity.logout();
      if (leaving) await clearMobileJourneyRoutes(db, { only: leaving }).catch(() => undefined);
    } catch {
      const current = identity.activeAccount();
      setAccountId(current);
      if (current) {
        try {
          await load(current);
        } catch {
          setPartition(null);
        }
      }
      setError('Sign-out could not finish. Reconnect and verify your session to retry.');
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  async function reauthenticateForDeletion() {
    if (!recentRequired || working.current) return;
    invalidateHistory();
    working.current = true;
    setBusy(true);
    try {
      const account = await identity.reauthenticate();
      setAccountId(account);
      setRecentRequired(false);
      setError('Identity verified. Confirm account deletion again to continue.');
    } catch {
      try {
        const previous = await identity.restore();
        setAccountId(previous);
        if (previous) await load(previous);
      } catch {
        setAccountId(null);
        setPartition(null);
      }
      setError(
        'Google verification failed or selected a different account. Retry verification before deletion.',
      );
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  async function retryDeletionCleanup() {
    const deleted = deletedAccountId;
    if (!deleted || working.current) return;
    working.current = true;
    setBusy(true);
    try {
      await clearJourneyPartition(db, deleted);
      await identity.clearLocalSession();
      deletionPendingRef.current = null;
      setDeletedAccountId(null);
      setError('');
    } catch {
      setError('Local deletion cleanup failed. Retry before using another account.');
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  async function deleteAccount() {
    if (!identity.activeAccount() || working.current) return;
    invalidateHistory();
    let serverDeleted: string | null = null;
    working.current = true;
    setBusy(true);
    setError('');
    try {
      const result = await identity.deleteAccount();
      serverDeleted = result.accountId;
      deletionPendingRef.current = result.accountId;
      setAccountId(null);
      setPartition(null);
      setRecentRequired(false);
      setDeletedAccountId(result.accountId);
      await clearJourneyPartition(db, result.accountId);
      if (!result.credentialCleared) await identity.clearLocalSession();
      deletionPendingRef.current = null;
      setDeletedAccountId(null);
      setError('Routiqo account deleted. Local plans and Google account remain.');
    } catch (failure) {
      if (failure instanceof NativeHttpStatus && failure.status === 428) {
        setRecentRequired(true);
        setError('Sign in with Google again, then confirm deletion once more.');
      } else if (serverDeleted) {
        setError('Server account deleted, but local journey cleanup needs a retry.');
      } else {
        setError('Account deletion could not be confirmed. Check your connection and retry.');
      }
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  async function refreshSession(force = false, syncAfter = false) {
    if (
      !identity.activeAccount() ||
      !onlineRef.current ||
      !foregroundRef.current ||
      working.current
    )
      return;
    working.current = true;
    const beforeRevision = identity.revision();
    let retryPending = false;
    try {
      const account = await identity.renew(force);
      retryPending = await journeys.resume(account);
      setAccountId(account);
      setError('');
    } catch {
      identity.invalidate();
      setAccountId(null);
      setPartition(null);
      setError('Your session needs verification. Sign in again if retry does not work.');
    } finally {
      if (identity.revision() !== beforeRevision) invalidateHistory();
      working.current = false;
    }
    if (retryPending || syncAfter) void sync();
  }
  async function action(
    command:
      | { journeyId: string; action: 'start'; kind: JourneyKind }
      | { journeyId: string; action: 'complete' },
    routeInput: JourneyRouteInput | null = null,
  ): Promise<boolean> {
    const account = identity.activeAccount();
    if (!account || working.current) return false;
    working.current = true;
    setBusy(true);
    setError('');
    try {
      const route = routeInput
        ? buildJourneyRoute({ ...routeInput, journeyId: command.journeyId })
        : null;
      await queueMobileJourney(db, account, command, Date.now(), route);
      await load(account);
    } catch {
      setError('Could not save the journey action on this device. Try again.');
      return false;
    } finally {
      working.current = false;
      setBusy(false);
    }
    if (onlineRef.current && foregroundRef.current) void sync();
    return true;
  }
  async function clearJourneyRoutes() {
    await clearMobileJourneyRoutes(db, 'all');
    setJourneyRoute(null);
  }
  useEffect(() => {
    if (!accountId) setJourneyRoute(null);
  }, [accountId]);
  useEffect(() => {
    if (identity.configured) void restore();
    const network = NetInfo.addEventListener((state) => {
      const connected = state.isConnected === true && state.isInternetReachable !== false;
      const reconnected = connected && !onlineRef.current;
      onlineRef.current = connected;
      setOnline(connected);
      if (reconnected && foregroundRef.current && !deletionPendingRef.current) {
        if (identity.activeAccount()) void sync();
        else void restore();
      }
    });
    const app = AppState.addEventListener('change', (state) => {
      foregroundRef.current = state === 'active';
      if (!foregroundRef.current) dispatchRef.current?.abort();
      if (state === 'active' && onlineRef.current && !deletionPendingRef.current) {
        if (identity.activeAccount()) void refreshSession(false, true);
        else void restore();
      }
    });
    const renewal = setInterval(() => {
      void refreshSession();
    }, 60_000);
    const delivery = setInterval(() => {
      if (onlineRef.current && foregroundRef.current) void sync();
    }, 30_000);
    return () => {
      dispatchRef.current?.abort();
      network();
      app.remove();
      clearInterval(renewal);
      clearInterval(delivery);
      identity.invalidate();
    };
    // One native vault/coordinator belongs to this provider instance.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity]);
  return (
    <Context.Provider
      value={{
        configured: identity.configured,
        accountId,
        partition,
        online,
        busy,
        restoring,
        error,
        recentRequired,
        deletionCleanupPending: deletedAccountId !== null,
        historyEpoch,
        readHistory,
        readJournal,
        writeJournal,
        storedJournal,
        storedJournals,
        cacheJournal,
        saveJournalDraft,
        acknowledgeJournalDraft,
        discardJournalDraft,
        readLiveConsent,
        submitLiveConsent,
        searchPlaces,
        calculateRoute,
        fetchSpotCatalog,
        fetchSpotActivity,
        readRouteContext,
        bindRouteContext,
        signIn,
        signOut,
        reauthenticateForDeletion,
        deleteAccount,
        retryDeletionCleanup,
        restore,
        sync,
        start: (kind) => action({ journeyId: randomUUID(), action: 'start', kind }),
        startWithRoute: (kind, route) =>
          action({ journeyId: randomUUID(), action: 'start', kind }, route),
        complete: async (journeyId) => {
          await action({ journeyId, action: 'complete' });
        },
        journeyRoute,
        clearJourneyRoutes,
      }}
    >
      {children}
    </Context.Provider>
  );
}

export function useNativeAccount(): NativeAccountContext {
  const value = useContext(Context);
  if (!value) throw new Error('Native account provider missing');
  return value;
}
