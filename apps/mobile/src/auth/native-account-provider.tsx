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
import type { JourneyKind } from '@routiqo/shared';
import { NativeHttpStatus } from './safe-transport';
import { nativeTransport } from './android-transport';
import { nativeGoogle } from './native-google';
import { createNativeAccount } from './native-account';
import { nativeSessionVault } from './secure-session';
import { createNativeJourneys } from '../features/journey/native-journeys';
import {
  clearJourneyPartition,
  queueMobileJourney,
  readMobileJourneyPartition,
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
  signIn(): Promise<void>;
  signOut(): Promise<void>;
  reauthenticateForDeletion(): Promise<void>;
  deleteAccount(): Promise<void>;
  retryDeletionCleanup(): Promise<void>;
  restore(): Promise<void>;
  sync(): Promise<void>;
  start(kind: JourneyKind): Promise<void>;
  complete(id: string): Promise<void>;
}
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

  async function load(account: string) {
    const value = await readMobileJourneyPartition(db, account);
    if (identity.activeAccount() === account) setPartition(value);
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
    working.current = true;
    setRestoring(true);
    setAccountId(null);
    setPartition(null);
    try {
      const account = await identity.restore();
      setAccountId(account);
      if (account) {
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
    working.current = true;
    setBusy(true);
    setError('');
    setAccountId(null);
    setPartition(null);
    try {
      const account = await identity.signIn();
      if (account) {
        setAccountId(account);
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
    working.current = true;
    setBusy(true);
    setError('');
    setAccountId(null);
    setPartition(null);
    try {
      await identity.logout();
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
      working.current = false;
    }
    if (retryPending || syncAfter) void sync();
  }
  async function action(
    command:
      | { journeyId: string; action: 'start'; kind: JourneyKind }
      | { journeyId: string; action: 'complete' },
  ) {
    const account = identity.activeAccount();
    if (!account || working.current) return;
    working.current = true;
    setBusy(true);
    setError('');
    try {
      await queueMobileJourney(db, account, command, Date.now());
      await load(account);
    } catch {
      setError('Could not save the journey action on this device. Try again.');
      return;
    } finally {
      working.current = false;
      setBusy(false);
    }
    if (onlineRef.current && foregroundRef.current) void sync();
  }
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
        signIn,
        signOut,
        reauthenticateForDeletion,
        deleteAccount,
        retryDeletionCleanup,
        restore,
        sync,
        start: (kind) => action({ journeyId: randomUUID(), action: 'start', kind }),
        complete: (journeyId) => action({ journeyId, action: 'complete' }),
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
