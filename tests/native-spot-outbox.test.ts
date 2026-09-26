import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import {
  RATE_LIMIT_WAIT_MS,
  createSpotOutboxController,
  type SpotOutboxState,
} from '../apps/mobile/src/features/spots/spot-outbox-controller';
import { NativeSpotsError } from '../apps/mobile/src/features/spots/native-spots';
import {
  readSpotOutbox,
  type NewSpotContribution,
  type QueuedSpotContribution,
  type SpotOutbox,
} from '../packages/shared/src/spot-contributions';

const account = '00000000-0000-4000-8000-000000000001';
const id = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const journey = id(500);
const START = Date.parse('2026-11-05T06:30:00Z');

const post = (n: number, capturedAt = START): NewSpotContribution => ({
  kind: 'post',
  clientKey: id(n),
  spotId: id(1),
  journeyId: journey,
  capturedAt: new Date(capturedAt).toISOString(),
  type: 'traffic',
  text: `Post ${n}`,
});

function harness(options: { onServer?: boolean } = {}) {
  let stored: SpotOutbox = readSpotOutbox(null, account);
  let now = START;
  let eligible = true;
  let onServer = options.onServer ?? true;
  let refuseStorage = false;
  const timers: { run: () => void; at: number }[] = [];
  const sent: string[] = [];
  const replies: (() => Promise<unknown>)[] = [];
  const signals: AbortSignal[] = [];
  const states: SpotOutboxState[] = [];
  const controller = createSpotOutboxController(
    {
      load: async () => stored,
      update: async (change) => {
        if (refuseStorage) throw new Error('Ghost Mode is on.');
        stored = change(stored);
        return stored;
      },
      send: async (entry: QueuedSpotContribution, signal) => {
        sent.push(entry.clientKey);
        signals.push(signal);
        const reply = replies.shift();
        if (reply) return (await reply()) as never;
        return { ref: id(900), status: 'active', expiresAt: '2026-11-05T08:00:00Z' };
      },
      eligible: () => eligible,
      journeyOnServer: () => onServer,
      now: () => now,
      random: () => 1,
      schedule: (run, delay) => {
        const timer = { run, at: now + delay };
        timers.push(timer);
        return () => timers.splice(timers.indexOf(timer), 1);
      },
    },
    (state) => states.push(state),
  );
  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));
  return {
    controller,
    sent,
    replies,
    signals,
    timers,
    states,
    settle,
    get stored() {
      return stored;
    },
    set now(value: number) {
      now = value;
    },
    get now() {
      return now;
    },
    set eligible(value: boolean) {
      eligible = value;
    },
    set onServer(value: boolean) {
      onServer = value;
    },
    set refuseStorage(value: boolean) {
      refuseStorage = value;
    },
    last: () => states.at(-1)!,
  };
}

describe('Spot outbox sender', () => {
  it('sends queued items in order and removes each once accepted', async () => {
    const h = harness();
    h.eligible = false;
    await h.controller.enqueue(post(1));
    await h.controller.enqueue(post(2));
    expect(h.sent).toEqual([]);
    expect(h.last().entries).toHaveLength(2);
    h.eligible = true;
    await h.controller.kick();
    await h.settle();
    expect(h.sent).toEqual([id(1), id(2)]);
    expect(h.stored.entries).toEqual([]);
    expect(h.last()).toMatchObject({ entries: [], sending: false });
  });

  it('waits until the journey is known on the server', async () => {
    const h = harness({ onServer: false });
    await h.controller.enqueue(post(1));
    await h.settle();
    expect(h.sent).toEqual([]);
    expect(h.timers).toHaveLength(0);
    h.onServer = true;
    await h.controller.kick();
    expect(h.sent).toEqual([id(1)]);
  });

  it('backs off on failures, waits a minute when rate limited, and retries on the timer', async () => {
    const h = harness();
    h.replies.push(async () => {
      throw new NativeSpotsError('unavailable', 503);
    });
    await h.controller.enqueue(post(1));
    await h.settle();
    expect(h.stored.entries[0]).toMatchObject({ attempts: 1, nextAttemptAt: START + 1000 });
    expect(h.timers.map((timer) => timer.at)).toEqual([START + 1000]);
    h.replies.push(async () => {
      throw new NativeSpotsError('rate-limited', 429);
    });
    h.now = START + 1000;
    h.timers[0]!.run();
    await h.settle();
    expect(h.stored.entries[0]!.nextAttemptAt).toBe(START + 1000 + RATE_LIMIT_WAIT_MS);
    h.now = START + 1000 + RATE_LIMIT_WAIT_MS;
    h.timers.at(-1)!.run();
    await h.settle();
    expect(h.sent).toEqual([id(1), id(1), id(1)]);
    expect(h.stored.entries).toEqual([]);
  });

  it('drops a refused item with one notice and carries on with the next', async () => {
    const h = harness();
    h.eligible = false;
    await h.controller.enqueue(post(1));
    await h.controller.enqueue(post(2));
    h.replies.push(async () => {
      throw new NativeSpotsError('too-old', 410);
    });
    h.eligible = true;
    await h.controller.kick();
    await h.settle();
    expect(h.sent).toEqual([id(1), id(2)]);
    expect(h.last().notices.map((notice) => notice.message)).toEqual([
      "Post: Too old to post; it wasn't sent.",
    ]);
    h.controller.dismiss(h.last().notices[0]!.id);
    expect(h.last().notices).toEqual([]);
  });

  it('pauses on a session refusal and resumes after a session check', async () => {
    const h = harness();
    h.replies.push(async () => {
      throw new NativeSpotsError('session', 401);
    });
    await h.controller.enqueue(post(1));
    await h.settle();
    expect(h.last().paused).toBe(true);
    expect(h.stored.entries).toHaveLength(1);
    await h.controller.kick();
    expect(h.sent).toEqual([id(1)]);
    h.now = START + 5_000;
    h.controller.resume();
    await h.settle();
    expect(h.sent).toEqual([id(1), id(1)]);
    expect(h.stored.entries).toEqual([]);
  });

  it('stop aborts the send in flight and leaves the entry untouched for a later replay', async () => {
    const h = harness();
    let release!: () => void;
    h.replies.push(
      () =>
        new Promise((_, reject) => {
          release = () => {
            const error = new Error('cancelled');
            error.name = 'AbortError';
            reject(error);
          };
        }),
    );
    await h.controller.enqueue(post(1));
    await h.settle();
    expect(h.last().sending).toBe(true);
    h.controller.stop();
    expect(h.signals[0]!.aborted).toBe(true);
    release();
    await h.settle();
    expect(h.stored.entries[0]).toMatchObject({ clientKey: id(1), attempts: 0 });
    expect(h.last().sending).toBe(false);
    await h.controller.kick();
    expect(h.sent).toEqual([id(1), id(1)]);
  });

  it('drops items past their life without sending, and stays idle when storage refuses', async () => {
    const h = harness();
    h.eligible = false;
    await h.controller.enqueue(post(1, START - 60 * 60_000));
    await h.settle();
    expect(h.last().entries).toHaveLength(1);
    h.now = START + 31 * 60_000; // past the traffic post's 90-minute base life
    h.eligible = true;
    await h.controller.kick();
    await h.settle();
    expect(h.sent).toEqual([]);
    expect(h.last().notices.map((notice) => notice.message)).toEqual([
      "Post waited too long and wasn't sent.",
    ]);
    h.refuseStorage = true;
    await expect(h.controller.enqueue(post(2))).rejects.toThrow('Ghost Mode is on.');
    await h.controller.kick();
    expect(h.sent).toEqual([]);
  });

  it('does nothing after dispose', async () => {
    const h = harness();
    h.eligible = false;
    await h.controller.enqueue(post(1));
    h.controller.dispose();
    h.eligible = true;
    await h.controller.kick();
    expect(h.sent).toEqual([]);
    expect(vi.isFakeTimers()).toBe(false);
  });
});

describe('Spot queue clearing (source scan; the provider has no render harness)', () => {
  const provider = readFileSync('apps/mobile/src/auth/native-account-provider.tsx', 'utf8');
  it('clears other accounts on sign-in and restore, and the leaving account on sign-out', () => {
    expect(provider.match(/await clearSpotOutbox\(db, \{ except: account \}\);/g)).toHaveLength(2);
    expect(provider).toContain(
      'await clearSpotOutbox(db, { only: leaving }).catch(() => undefined);',
    );
    const deletion = readFileSync('apps/mobile/src/storage/journey-outbox.ts', 'utf8');
    expect(deletion).toContain(
      "await tx.runAsync('DELETE FROM spot_outbox_v1 WHERE account_id = ?', accountId);",
    );
  });
});
