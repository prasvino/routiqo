import { describe, expect, it } from 'vitest';
import {
  claimJourneyCommand as claim,
  emptyJourneyOutbox as empty,
  enqueueJourneyCommand as enqueue,
  readJourneyOutbox as read,
  resumeJourneyAuthentication as resume,
  settleJourneyCommand as settle,
} from './journey-outbox';
const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
function head(state: ReturnType<typeof empty>) {
  const entry = state.entries[0];
  if (!entry) throw new Error('Expected a queued action');
  return entry;
}
const owner = id(1);
const start = { journeyId: id(2), action: 'start', kind: 'trip' } as const;
const complete = { journeyId: id(2), action: 'complete' } as const;
describe('journey outbox', () => {
  it('deduplicates retries and rejects changed starts without mutation', () => {
    const state = enqueue(empty(owner), start, 0);
    expect(enqueue(state, start, 100)).toBe(state);
    expect(() => enqueue(state, { ...start, kind: 'commute' }, 0)).toThrow();
    expect(state.entries).toHaveLength(1);
    expect(read(JSON.stringify(state), owner)).toEqual(state);
  });
  it('keeps completion behind its start across restart and lease recovery', () => {
    const state = enqueue(enqueue(empty(owner), start, 0), complete, 0);
    const first = claim(state, 0, id(3));
    expect(claim(first, 29_999, id(4))).toBe(first);
    const restarted = read(JSON.stringify(first), owner);
    const recovered = claim(restarted, 30_000, id(4));
    expect(head(recovered).command).toEqual(start);
    expect(head(recovered).attempts).toBe(2);
    expect(settle(recovered, id(3), 'success', 30_001)).toBe(recovered);
    const ack = settle(recovered, id(4), 'success', 30_002);
    expect(head(claim(ack, 30_003, id(5))).command).toEqual(complete);
  });
  it('backs off transient failures, caps retries and validates jitter', () => {
    let state = enqueue(empty(owner), start, 0);
    let now = 0;
    for (let attempt = 1; attempt <= 40; attempt++) {
      state = claim(state, now, id(attempt + 10));
      state = settle(state, id(attempt + 10), 'transient', now, 1);
      const next = head(state).nextAttemptAt;
      expect(next - now).toBe(Math.min(300_000, 1000 * 2 ** (attempt - 1)));
      expect(claim(state, next - 1, id(99))).toBe(state);
      now = next;
    }
    expect(head(state).attempts).toBe(31);
    expect(() => settle(state, id(99), 'transient', now, NaN)).toThrow();
  });
  it('pauses for auth and refuses to resume another account or a conflict', () => {
    const state = claim(enqueue(enqueue(empty(owner), start, 0), complete, 0), 0, id(3));
    const blocked = settle(state, id(3), 'authentication', 1);
    expect(claim(blocked, 99_999, id(4))).toBe(blocked);
    expect(() => resume(blocked, id(9), 1)).toThrow();
    const retried = claim(resume(blocked, owner, 2), 2, id(4));
    const conflict = settle(retried, id(4), 'conflict', 3);
    expect(resume(conflict, owner, 4)).toBe(conflict);
    expect(claim(conflict, 999_999, id(5))).toBe(conflict);
    expect(conflict.entries).toHaveLength(2);
  });
  it('rejects corrupt, oversized, duplicate, cross-account and unsupported payloads', () => {
    const state = enqueue(empty(owner), start, 0);
    for (const value of [
      { ...state, version: 2 },
      { ...state, accountId: id(9) },
      { ...state, entries: [state.entries[0], state.entries[0]] },
      { ...state, entries: [{ ...state.entries[0], attempts: -1 }] },
      { ...state, entries: [{ ...state.entries[0], lease: {} }] },
    ])
      expect(() => read(JSON.stringify(value), owner)).toThrow();
    expect(() => read('{', owner)).toThrow();
    expect(() => read(' '.repeat(256 * 1024 + 1), owner)).toThrow();
    expect(() => read('€'.repeat(90_000), owner)).toThrow();
    expect(() => empty('not-an-account')).toThrow();
    expect(() => enqueue(state, start, NaN)).toThrow();
    expect(() => claim(state, 8e15, id(3))).toThrow();
    expect(read(JSON.stringify({ ...state, accessToken: 'ignored' }), owner)).toEqual(state);
  });
  it('bounds pending work without dropping entries, while allowing duplicate enqueue at capacity', () => {
    let state = empty(owner);
    for (let n = 100; n < 200; n++) state = enqueue(state, { ...start, journeyId: id(n) }, n);
    expect(() => enqueue(state, start, 200)).toThrow();
    expect(enqueue(state, { ...start, journeyId: id(100) }, 200)).toBe(state);
    expect(state.entries).toHaveLength(100);
  });
});
