import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { AppState } from 'react-native';
import { randomUUID } from 'expo-crypto';
import { useSQLiteContext } from 'expo-sqlite';
import type { NewSpotContribution, SpotReportReason, SpotVote } from '@routiqo/shared';
import { useNativeAccount } from '../../auth/native-account-provider';
import {
  clearSpotOutbox,
  readGhostMode,
  readSpotOutboxFor,
  setGhostMode,
  updateSpotOutbox,
} from '../../storage/spot-outbox';
import { NativeSpotsError } from './native-spots';
import {
  createSpotOutboxController,
  type SpotOutboxController,
  type SpotOutboxState,
} from './spot-outbox-controller';
import { spotContributionsEnabled, spotsEnabled } from './spots-model';

const enabled = spotsEnabled() && spotContributionsEnabled();
const DELIVERY_INTERVAL_MS = 30_000;
const idle: SpotOutboxState = { entries: [], notices: [], sending: false, paused: false };

export class GhostModeOn extends Error {
  constructor() {
    super('Ghost Mode is on.');
    this.name = 'GhostModeOn';
  }
}

export interface SpotContributions {
  /** Device-wide Ghost Mode (ADR 0073); null until read from storage. */
  ghost: boolean | null;
  setGhost(on: boolean): Promise<void>;
  outbox: SpotOutboxState;
  /** Queues a signal or post (no network); throws while Ghost Mode is on or the queue is full. */
  queue(contribution: NewSpotContribution): Promise<void>;
  dismiss(id: number): void;
  vote(ref: string, vote: SpotVote): Promise<void>;
  deletePost(ref: string): Promise<void>;
  report(ref: string, reason: SpotReportReason): Promise<void>;
  block(ref: string): Promise<void>;
  /** Clear local data: drops every queued contribution on this device. */
  clearQueue(): Promise<void>;
}

const Context = createContext<SpotContributions | null>(null);

/**
 * Spot contributions on the device: Ghost Mode, the `spot_outbox_v1` sender for signals and posts,
 * and the online-only actions (vote, delete, report, block). With either flag off nothing is
 * created, read or sent. Ghost Mode stops sending first, then clears the queue in storage.
 */
export function SpotContributionsProvider({ children }: { children: ReactNode }) {
  if (!enabled) return <>{children}</>;
  return <EnabledProvider>{children}</EnabledProvider>;
}

function EnabledProvider({ children }: { children: ReactNode }) {
  const db = useSQLiteContext();
  const session = useNativeAccount();
  const accountId = session.accountId;
  const [ghost, setGhostState] = useState<boolean | null>(null);
  const [outbox, setOutbox] = useState<SpotOutboxState>(idle);
  const [controller, setController] = useState<SpotOutboxController | null>(null);

  const ghostRef = useRef<boolean | null>(null);
  ghostRef.current = ghost;
  const onlineRef = useRef(session.online);
  onlineRef.current = session.online;
  const foregroundRef = useRef(AppState.currentState === 'active');
  const partitionRef = useRef(session.partition);
  partitionRef.current = session.partition;
  const sessionRef = useRef(session);
  sessionRef.current = session;
  // A report's requestId is reused for the same item and reason until it is accepted or refused.
  const reportIds = useRef(new Map<string, string>());

  useEffect(() => {
    let live = true;
    void readGhostMode(db)
      .then((on) => live && setGhostState(on))
      .catch(() => live && setGhostState(true)); // Unknown state fails closed.
    return () => {
      live = false;
    };
  }, [db]);

  // One sender per signed-in account; switching or signing out drops it and its in-flight send.
  useEffect(() => {
    if (!accountId) {
      setController(null);
      setOutbox(idle);
      return;
    }
    const created = createSpotOutboxController(
      {
        load: () => readSpotOutboxFor(db, accountId),
        update: (change) => updateSpotOutbox(db, accountId, change),
        send: (entry, signal) => sessionRef.current.submitSpotContribution(entry, signal),
        eligible: () =>
          ghostRef.current === false &&
          onlineRef.current &&
          foregroundRef.current &&
          sessionRef.current.accountId === accountId,
        journeyOnServer: (journeyId) =>
          partitionRef.current?.snapshots.journeys.some((journey) => journey.id === journeyId) ??
          false,
        now: Date.now,
        random: Math.random,
        schedule: (run, delay) => {
          const timer = setTimeout(run, delay);
          return () => clearTimeout(timer);
        },
      },
      setOutbox,
    );
    setController(created);
    void created.kick();
    reportIds.current.clear();
    return () => created.dispose();
  }, [db, accountId]);

  useEffect(() => {
    if (ghost === false) void controller?.kick();
  }, [controller, ghost, session.online, session.partition]);

  useEffect(() => {
    const change = AppState.addEventListener('change', (state) => {
      foregroundRef.current = state === 'active';
      if (foregroundRef.current) void controller?.kick();
      else controller?.stop();
    });
    const delivery = setInterval(() => void controller?.kick(), DELIVERY_INTERVAL_MS);
    return () => {
      change.remove();
      clearInterval(delivery);
    };
  }, [controller]);

  const setGhost = useCallback(
    async (on: boolean) => {
      if (on) {
        // Stop sending before anything else, so no queued item can leave after the switch.
        ghostRef.current = true;
        setGhostState(true);
        controller?.stop();
        await setGhostMode(db, true);
        await controller?.kick(); // reloads the now-empty queue
        return;
      }
      await setGhostMode(db, false);
      setGhostState(false);
    },
    [controller, db],
  );

  const online = useCallback(
    async <T,>(allowedInGhost: boolean, run: (signal: AbortSignal) => Promise<T>): Promise<T> => {
      if (!allowedInGhost && ghostRef.current !== false) throw new GhostModeOn();
      return run(new AbortController().signal);
    },
    [],
  );

  const value = useMemo<SpotContributions>(
    () => ({
      ghost,
      setGhost,
      outbox,
      async queue(contribution) {
        if (ghostRef.current !== false || !controller) throw new GhostModeOn();
        await controller.enqueue(contribution);
      },
      dismiss: (id) => controller?.dismiss(id),
      vote: (ref, vote) =>
        online(false, (signal) => sessionRef.current.voteSpotItem(ref, vote, signal)).then(
          () => undefined,
        ),
      // Removing your own post is not sending new content, so it stays available in Ghost Mode.
      deletePost: (ref) =>
        online(true, (signal) => sessionRef.current.deleteSpotPost(ref, signal)).then(
          () => undefined,
        ),
      async report(ref, reason) {
        const key = `${ref}:${reason}`;
        const requestId = reportIds.current.get(key) ?? randomUUID();
        reportIds.current.set(key, requestId);
        try {
          await online(false, (signal) =>
            sessionRef.current.reportSpotItem(ref, requestId, reason, signal),
          );
          reportIds.current.delete(key);
        } catch (error) {
          // Keep the id only for a retry that could still succeed with the same request.
          if (
            error instanceof NativeSpotsError &&
            error.code !== 'unavailable' &&
            error.code !== 'rate-limited'
          )
            reportIds.current.delete(key);
          throw error;
        }
      },
      block: (ref) => online(false, (signal) => sessionRef.current.blockSpotAuthor(ref, signal)),
      async clearQueue() {
        controller?.stop();
        await clearSpotOutbox(db, 'all');
        await controller?.kick();
      },
    }),
    [ghost, setGhost, outbox, controller, online, db],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

/** Contributions, or null when either Spots flag is off. */
export const useSpotContributions = () => useContext(Context);
