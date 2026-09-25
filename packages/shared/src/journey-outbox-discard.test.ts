import { describe, expect, it } from 'vitest';
import {
  claimJourneyCommand as claim,
  discardBlockedJourneyCommand as discard,
  emptyJourneyOutbox as empty,
  enqueueJourneyCommand as enqueue,
  settleJourneyCommand as settle,
  type JourneyCommand,
  type JourneyOutbox,
  type OutboxOutcome,
} from './journey-outbox';

const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const owner = id(1);
const start = { journeyId: id(2), action: 'start', kind: 'trip' } as const;
const complete = { journeyId: id(2), action: 'complete' } as const;
const otherStart = { journeyId: id(5), action: 'start', kind: 'commute' } as const;
const otherComplete = { journeyId: id(5), action: 'complete' } as const;

function blocked(commands: JourneyCommand[], outcome: OutboxOutcome = 'conflict'): JourneyOutbox {
  let state = empty(owner);
  for (const command of commands) state = enqueue(state, command, 0);
  const token = id(9);
  return settle(claim(state, 0, token), token, outcome, 0);
}

describe('discarding a blocked journey action', () => {
  it('discards a refused start whose journey the server does not have, with its dependent finish', () => {
    const state = blocked([start, complete, otherStart, otherComplete]);
    const result = discard(state, start, null);
    expect(result.discarded).toEqual([start, complete]);
    expect(result.outbox.entries.map((entry) => entry.command)).toEqual([
      otherStart,
      otherComplete,
    ]);
    expect(
      result.outbox.entries.every((entry) => entry.blocked === null && entry.lease === null),
    ).toBe(true);
    expect(state.entries).toHaveLength(4);
  });

  it('discards a refused start when the server has that journey with a different kind', () => {
    const result = discard(blocked([start, complete]), start, {
      kind: 'commute',
      status: 'active',
    });
    expect(result.discarded).toEqual([start, complete]);
    expect(result.outbox.entries).toEqual([]);
  });

  it('discards only a refused finish when the server journey is still active or absent', () => {
    for (const observation of [{ kind: 'trip', status: 'active' } as const, null]) {
      const state = blocked([complete, otherStart], 'rejected');
      const result = discard(state, complete, observation);
      expect(result.discarded).toEqual([complete]);
      expect(result.outbox.entries.map((entry) => entry.command)).toEqual([otherStart]);
    }
  });

  it('refuses when the server already applied the action, so reconciliation must be used', () => {
    expect(() => discard(blocked([start]), start, { kind: 'trip', status: 'active' })).toThrow(
      /Reconcile/,
    );
    expect(() => discard(blocked([start]), start, { kind: 'trip', status: 'completed' })).toThrow(
      /Reconcile/,
    );
    expect(() =>
      discard(blocked([complete]), complete, { kind: 'trip', status: 'completed' }),
    ).toThrow(/Reconcile/);
  });

  it('refuses a head that changed, is not refused, is leased or needs sign-in', () => {
    const state = blocked([start, otherStart]);
    expect(() => discard(state, otherStart, null)).toThrow(/changed/);
    expect(() => discard(state, { ...start, kind: 'commute' }, null)).toThrow(/changed/);
    expect(() => discard(state, complete, null)).toThrow(/changed/);
    expect(() => discard(empty(owner), start, null)).toThrow(/changed/);

    const waiting = enqueue(empty(owner), start, 0);
    expect(() => discard(waiting, start, null)).toThrow(/refused/);
    const leased = claim(waiting, 0, id(9));
    expect(() => discard(leased, start, null)).toThrow(/refused/);
    expect(() => discard(blocked([start], 'authentication'), start, null)).toThrow(/refused/);
    expect(() => discard(blocked([start], 'transient'), start, null)).toThrow(/refused/);
  });

  it('rejects malformed commands and observations without changing the outbox', () => {
    const state = blocked([start]);
    expect(() =>
      discard(state, { journeyId: 'not-a-uuid', action: 'start', kind: 'trip' }, null),
    ).toThrow();
    expect(() =>
      discard(state, start, { kind: 'bus', status: 'active' } as unknown as {
        kind: 'trip';
        status: 'active';
      }),
    ).toThrow(/observation/);
    expect(state.entries).toHaveLength(1);
    expect(state.entries[0]?.blocked).toBe('conflict');
  });
});
