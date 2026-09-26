import {
  dropExpiredSpotContributions,
  enqueueSpotContribution,
  nextSpotAttemptAt,
  nextSpotContribution,
  settleSpotContribution,
  type NewSpotContribution,
  type QueuedSpotContribution,
  type SpotContributionReceipt,
  type SpotOutbox,
  type SpotSendOutcome,
} from '@routiqo/shared';
import { NativeSpotsError } from './native-spots';

/**
 * Sends one account's queued Spot signals and posts (`spot_outbox_v1`, ADR 0073): strictly in
 * order, one request at a time, only while `eligible()` (online, foreground, signed in, Ghost Mode
 * off) and once the entry's journey is known on the server. Refusals remove the entry and tell the
 * author once; everything else backs off. `stop()` aborts the request in flight (Ghost Mode,
 * background, sign-out) without settling it, so nothing is marked sent that was not confirmed.
 */
export interface SpotOutboxPorts {
  load(): Promise<SpotOutbox>;
  update(change: (current: SpotOutbox) => SpotOutbox): Promise<SpotOutbox>;
  send(entry: QueuedSpotContribution, signal: AbortSignal): Promise<SpotContributionReceipt>;
  eligible(): boolean;
  journeyOnServer(journeyId: string): boolean;
  now(): number;
  random(): number;
  schedule(run: () => void, delayMs: number): () => void;
}

export interface SpotOutboxNotice {
  id: number;
  message: string;
}

export interface SpotOutboxState {
  entries: QueuedSpotContribution[];
  notices: SpotOutboxNotice[];
  sending: boolean;
  /** The session was refused; this controller sends nothing more (sign-in creates a new one). */
  paused: boolean;
}

export const RATE_LIMIT_WAIT_MS = 60_000;
const MAX_NOTICES = 3;

const refusal: Partial<Record<NativeSpotsError['code'], string>> = {
  'too-old': "Too old to post; it wasn't sent.",
  'contact-details': "Links and phone numbers aren't allowed in posts.",
  forbidden: "You can't post right now, so it wasn't sent.",
  'not-found': "That Spot or journey isn't available, so it wasn't sent.",
  // 409 covers both a changed replay and a journey that was not active at capture.
  conflict: "It couldn't be sent for this journey.",
  invalid: "It couldn't be sent.",
};

function outcomeOf(error: unknown): { outcome: SpotSendOutcome; notice?: string; pause?: boolean } {
  if (error instanceof NativeSpotsError) {
    // A 2xx whose receipt could not be read was still accepted; the clientKey makes it final.
    if (error.code === 'invalid' && error.status === undefined)
      return { outcome: { kind: 'sent' } };
    if (error.code === 'session') return { outcome: { kind: 'retry' }, pause: true };
    if (error.code === 'rate-limited')
      return { outcome: { kind: 'retry', retryAfterMs: RATE_LIMIT_WAIT_MS } };
    const message = refusal[error.code];
    if (message) return { outcome: { kind: 'refused' }, notice: message };
  }
  return { outcome: { kind: 'retry' } };
}

const what = (entry: QueuedSpotContribution) => (entry.kind === 'post' ? 'Post' : 'Update');

export function createSpotOutboxController(
  ports: SpotOutboxPorts,
  onChange: (state: SpotOutboxState) => void,
) {
  let state: SpotOutboxState = { entries: [], notices: [], sending: false, paused: false };
  let running = false;
  let again = false;
  let disposed = false;
  let inFlight: AbortController | null = null;
  let cancelTimer: (() => void) | null = null;
  let noticeId = 0;
  /** Bumped by stop(): a pass that started earlier must not send from its now-stale copy. */
  let epoch = 0;

  const publish = (next: Partial<SpotOutboxState>) => {
    const same =
      next.entries !== undefined &&
      Object.keys(next).length === 1 &&
      JSON.stringify(next.entries) === JSON.stringify(state.entries);
    if (same) return; // Periodic checks re-render nothing when the queue did not change.
    state = { ...state, ...next };
    if (!disposed) onChange(state);
  };
  const notify = (message: string) =>
    publish({ notices: [...state.notices, { id: ++noticeId, message }].slice(-MAX_NOTICES) });
  const commit = async (change: (current: SpotOutbox) => SpotOutbox) => {
    const next = await ports.update(change);
    publish({ entries: next.entries });
    return next;
  };

  async function pass(): Promise<void> {
    cancelTimer?.();
    cancelTimer = null;
    const started = epoch;
    const current = () => !disposed && epoch === started;
    let outbox = await ports.load();
    if (!current()) return;
    publish({ entries: outbox.entries });
    const now = ports.now();
    if (dropExpiredSpotContributions(outbox, now).dropped.length > 0) {
      let dropped: QueuedSpotContribution[] = [];
      outbox = await commit((current) => {
        const result = dropExpiredSpotContributions(current, now);
        dropped = result.dropped;
        return result.outbox;
      });
      for (const entry of dropped) notify(`${what(entry)} waited too long and wasn't sent.`);
    }
    while (current() && !state.paused && ports.eligible()) {
      const entry = nextSpotContribution(outbox, ports.now(), ports.journeyOnServer);
      if (!entry) {
        const at = nextSpotAttemptAt(outbox, ports.journeyOnServer);
        if (at !== null)
          cancelTimer = ports.schedule(() => void kick(), Math.max(0, at - ports.now()));
        return;
      }
      const abort = new AbortController();
      inFlight = abort;
      publish({ sending: true });
      let settled: { outcome: SpotSendOutcome; notice?: string; pause?: boolean };
      try {
        await ports.send(entry, abort.signal);
        settled = { outcome: { kind: 'sent' } };
      } catch (error) {
        if (abort.signal.aborted || (error instanceof Error && error.name === 'AbortError')) {
          publish({ sending: false });
          return; // Stopped on purpose: leave the entry exactly as it was.
        }
        settled = outcomeOf(error);
      } finally {
        if (inFlight === abort) inFlight = null;
      }
      publish({ sending: false });
      if (!current() || abort.signal.aborted) return;
      outbox = await commit((queue) =>
        settleSpotContribution(
          queue,
          entry.clientKey,
          settled.outcome,
          ports.now(),
          ports.random(),
        ),
      );
      if (settled.notice) notify(`${what(entry)}: ${settled.notice}`);
      if (settled.pause) publish({ paused: true });
    }
  }

  function stop() {
    epoch += 1;
    cancelTimer?.();
    cancelTimer = null;
    inFlight?.abort();
  }

  async function kick(): Promise<void> {
    if (disposed) return;
    if (running) {
      again = true;
      return;
    }
    running = true;
    try {
      do {
        again = false;
        try {
          await pass();
        } catch {
          publish({ sending: false }); // Storage refused (Ghost Mode, deleted account): stay idle.
        }
      } while (again && !disposed);
    } finally {
      running = false;
    }
  }

  return {
    getState: () => state,
    /** Queues a contribution (no network) and tries to send it at once when eligible. */
    async enqueue(contribution: NewSpotContribution): Promise<void> {
      await commit((current) => enqueueSpotContribution(current, contribution, ports.now()));
      void kick();
    },
    /** Something that affects sending changed: connectivity, foreground, session, journey. */
    kick,
    /**
     * Aborts the request in flight without settling it (Ghost Mode, background, sign-out) and
     * ends the current pass. The native call cannot be cancelled once on the wire, so this
     * returns whether a send may still reach the server.
     */
    stop(): boolean {
      const sending = inFlight !== null;
      stop();
      return sending;
    },
    dismiss(id: number) {
      publish({ notices: state.notices.filter((notice) => notice.id !== id) });
    },
    dispose() {
      stop();
      disposed = true;
    },
  };
}

export type SpotOutboxController = ReturnType<typeof createSpotOutboxController>;
