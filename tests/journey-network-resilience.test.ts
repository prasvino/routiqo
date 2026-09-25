import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { enqueueJourneyCommand, type JourneyCommand } from '../packages/shared/src/journey-outbox';
import {
  dispatchBrowserJourneyBatch,
  dispatchBrowserJourneyOnce,
} from '../apps/web/lib/journey-dispatch';
import {
  readBrowserJourneyPartition,
  updateBrowserJourneyOutbox,
} from '../apps/web/lib/journey-storage';

// End-to-end browser outbox scenarios against a synthetic server that follows the core API's
// journey rules: a start is idempotent for the same id and kind and conflicts (409) for another
// kind; a finish is idempotent once completed and 404 for a journey the account does not have.

const accountA = '00000000-0000-4000-8000-00000000000a';
const accountB = '00000000-0000-4000-8000-00000000000b';
const first = '00000000-0000-4000-8000-000000000101';
const second = '00000000-0000-4000-8000-000000000102';
const other = '00000000-0000-4000-8000-000000000201';

/** How the network treats one journey write. "after" faults happen once the server has applied it. */
type Fault =
  | 'ok'
  | 'drop-before'
  | 'drop-after'
  | 'unavailable'
  | 'throttled'
  | 'malformed-after'
  | 'hold-after'
  | 'storage-fails-after';

type Stored = { kind: 'trip' | 'commute'; status: 'active' | 'completed' };

class SyntheticServer {
  readonly journeys = new Map<string, Map<string, Stored>>();
  /** Every write the server actually applied or replayed, in arrival order. */
  readonly applied: string[] = [];
  /** Every write the client attempted, with the client clock at send time. */
  readonly attempts: { account: string; operation: string; at: number }[] = [];
  readonly faults: Fault[] = [];
  readonly held: Array<() => void> = [];
  session: string | null = accountA;
  now = 1_780_000_000_000;

  fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    if (url.endsWith('/auth/session')) {
      return this.session
        ? Response.json({ accountId: this.session })
        : new Response(null, { status: 401 });
    }
    if (url.endsWith('/auth/csrf'))
      return Response.json({ token: 'synthetic-csrf-value-for-test' });
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const account = headers['X-Routiqo-Account'] ?? '';
    const complete = /^\/api\/v1\/journeys\/([0-9a-f-]{36})\/complete$/.exec(url);
    const body = JSON.parse(String(init?.body ?? '{}')) as {
      id?: string;
      kind?: 'trip' | 'commute';
    };
    const operation = complete ? `complete:${complete[1]}` : `start:${body.id}:${body.kind}`;
    this.attempts.push({ account, operation, at: this.now });
    const fault = this.faults.shift() ?? 'ok';
    if (fault === 'drop-before') throw new TypeError('Failed to fetch');
    if (fault === 'unavailable') return new Response(null, { status: 503 });
    if (fault === 'throttled') return new Response(null, { status: 429 });
    const response = complete ? this.complete(account, complete[1]!) : this.start(account, body);
    if (response.status === 200) this.applied.push(`${account}:${operation}`);
    if (fault === 'drop-after') throw new TypeError('Failed to fetch');
    if (fault === 'malformed-after') return new Response('{"id":', { status: 200 });
    if (fault === 'storage-fails-after')
      vi.stubGlobal('indexedDB', {
        open: () => {
          throw new Error('storage unavailable');
        },
      });
    if (fault === 'hold-after') {
      await new Promise<void>((release) => this.held.push(release));
    }
    return response;
  });

  private owned(account: string) {
    let journeys = this.journeys.get(account);
    if (!journeys) this.journeys.set(account, (journeys = new Map()));
    return journeys;
  }
  private start(account: string, body: { id?: string; kind?: 'trip' | 'commute' }) {
    const journeys = this.owned(account);
    const existing = journeys.get(body.id!);
    if (existing && existing.kind !== body.kind) return new Response(null, { status: 409 });
    const journey = existing ?? { kind: body.kind!, status: 'active' as const };
    journeys.set(body.id!, journey);
    return this.render(body.id!, journey);
  }
  private complete(account: string, id: string) {
    const journey = this.owned(account).get(id);
    if (!journey) return new Response(null, { status: 404 });
    journey.status = 'completed';
    return this.render(id, journey);
  }
  private render(id: string, journey: Stored) {
    return Response.json({
      id,
      kind: journey.kind,
      status: journey.status,
      startedAt: '2026-09-25T08:00:00Z',
      completedAt: journey.status === 'completed' ? '2026-09-25T09:00:00Z' : null,
    });
  }
}

let server: SyntheticServer;

async function queue(account: string, ...commands: JourneyCommand[]) {
  for (const command of commands)
    await updateBrowserJourneyOutbox(account, (current) =>
      enqueueJourneyCommand(current, command, server.now),
    );
}
const head = async (account: string) =>
  (await readBrowserJourneyPartition(account)).outbox.entries[0];
const writes = (account: string) => server.attempts.filter((a) => a.account === account);

/** Wait until the synthetic server holds a response, without relying on wall-clock timers. */
async function untilHeld(count: number) {
  for (let turn = 0; turn < 200 && server.held.length < count; turn++)
    await new Promise((resolve) => setTimeout(resolve, 0));
  expect(server.held).toHaveLength(count);
}

beforeEach(() => {
  server = new SyntheticServer();
  vi.stubGlobal('indexedDB', new IDBFactory());
  vi.stubGlobal('fetch', server.fetch);
  vi.spyOn(Date, 'now').mockImplementation(() => server.now);
});
afterEach(() => {
  for (const release of server.held.splice(0)) release();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('journey sync under delayed, duplicated and flapping networks', () => {
  it('recovers a response lost after the server applied the start by replaying the same identity', async () => {
    await queue(
      accountA,
      { journeyId: first, action: 'start', kind: 'trip' },
      {
        journeyId: first,
        action: 'complete',
      },
    );
    server.faults.push('drop-after');

    expect(await dispatchBrowserJourneyOnce(accountA)).toBe('deferred');
    const deferred = await head(accountA);
    expect(deferred).toMatchObject({ attempts: 1, lease: null, blocked: null });
    expect(server.journeys.get(accountA)?.get(first)).toEqual({ kind: 'trip', status: 'active' });

    server.now = deferred!.nextAttemptAt;
    expect(await dispatchBrowserJourneyBatch(accountA)).toEqual({
      acknowledged: 2,
      reason: 'idle',
    });

    expect(writes(accountA).map((a) => a.operation)).toEqual([
      `start:${first}:trip`,
      `start:${first}:trip`,
      `complete:${first}`,
    ]);
    expect(server.journeys.get(accountA)?.size).toBe(1);
    const saved = await readBrowserJourneyPartition(accountA);
    expect(saved.outbox.entries).toEqual([]);
    expect(saved.snapshots.journeys).toMatchObject([{ id: first, status: 'completed' }]);
  });

  it('treats a response delayed past its deadline as lost, and its late settle cannot disturb a newer tab', async () => {
    await queue(
      accountA,
      { journeyId: first, action: 'start', kind: 'trip' },
      {
        journeyId: first,
        action: 'complete',
      },
    );
    server.faults.push('hold-after');

    const slowTab = dispatchBrowserJourneyOnce(accountA);
    await untilHeld(1);
    // The request deadline (12 s) and the 30 s lease both pass while the response is held.
    server.now += 31_000;

    const freshTab = await dispatchBrowserJourneyOnce(accountA);
    expect(freshTab).toBe('acknowledged');
    const afterFresh = await readBrowserJourneyPartition(accountA);
    expect(afterFresh.outbox.entries).toEqual([
      expect.objectContaining({
        command: { journeyId: first, action: 'complete' },
        attempts: 0,
        lease: null,
        blocked: null,
      }),
    ]);

    server.held.shift()!();
    expect(await slowTab).toBe('deferred');
    // The slow tab's lease was replaced, so its transient settle is ignored rather than
    // pushing a backoff onto the next action.
    expect(await readBrowserJourneyPartition(accountA)).toEqual(afterFresh);
    expect(server.applied).toEqual([
      `${accountA}:start:${first}:trip`,
      `${accountA}:start:${first}:trip`,
    ]);
    expect(server.journeys.get(accountA)?.size).toBe(1);
  });

  it('collapses repeated taps and competing tabs into one queued action and one write each', async () => {
    const start = { journeyId: first, action: 'start', kind: 'commute' } as const;
    const finish = { journeyId: first, action: 'complete' } as const;
    await Promise.all([queue(accountA, start), queue(accountA, start), queue(accountA, start)]);
    await Promise.all([queue(accountA, finish), queue(accountA, finish)]);
    expect((await readBrowserJourneyPartition(accountA)).outbox.entries).toHaveLength(2);

    const results = await Promise.all([
      dispatchBrowserJourneyBatch(accountA),
      dispatchBrowserJourneyBatch(accountA),
      dispatchBrowserJourneyBatch(accountA),
    ]);
    expect(results.reduce((total, result) => total + result.acknowledged, 0)).toBe(2);
    expect(writes(accountA).map((a) => a.operation)).toEqual([
      `start:${first}:commute`,
      `complete:${first}`,
    ]);
    expect(server.journeys.get(accountA)?.get(first)).toEqual({
      kind: 'commute',
      status: 'completed',
    });
  });

  it('keeps FIFO order through a network flap, never sends early and converges', async () => {
    await queue(
      accountA,
      { journeyId: first, action: 'start', kind: 'trip' },
      { journeyId: first, action: 'complete' },
      { journeyId: second, action: 'start', kind: 'commute' },
    );
    server.faults.push(
      'drop-before',
      'unavailable',
      'drop-after',
      'ok',
      'throttled',
      'malformed-after',
      'ok',
      'drop-before',
      'ok',
    );

    let rounds = 0;
    for (; rounds < 30; rounds++) {
      const pending = await head(accountA);
      if (!pending) break;
      const before = writes(accountA).length;
      if (pending.nextAttemptAt > server.now) {
        // A dispatch before the persisted backoff ends is idle and sends nothing.
        server.now = pending.nextAttemptAt - 1;
        expect(await dispatchBrowserJourneyOnce(accountA)).toBe('idle');
        expect(writes(accountA)).toHaveLength(before);
        server.now = pending.nextAttemptAt;
      }
      const result = await dispatchBrowserJourneyOnce(accountA);
      expect(['acknowledged', 'deferred']).toContain(result);
      expect(writes(accountA)).toHaveLength(before + 1);
    }
    expect(rounds).toBe(9);
    expect(server.faults).toEqual([]);

    // Replays repeat an operation, but the order in which operations first applied is FIFO.
    expect([...new Set(server.applied)]).toEqual([
      `${accountA}:start:${first}:trip`,
      `${accountA}:complete:${first}`,
      `${accountA}:start:${second}:commute`,
    ]);
    const saved = await readBrowserJourneyPartition(accountA);
    expect(saved.outbox.entries).toEqual([]);
    expect(saved.snapshots.journeys.map(({ id, kind, status }) => ({ id, kind, status }))).toEqual(
      expect.arrayContaining([
        { id: first, kind: 'trip', status: 'completed' },
        { id: second, kind: 'commute', status: 'active' },
      ]),
    );
    expect(saved.snapshots.journeys).toHaveLength(2);
  });

  it('records a response delayed across an account switch only for the account that sent it', async () => {
    await queue(accountA, { journeyId: first, action: 'start', kind: 'trip' });
    await queue(accountB, { journeyId: other, action: 'start', kind: 'commute' });
    server.faults.push('hold-after');

    const slow = dispatchBrowserJourneyOnce(accountA);
    await untilHeld(1);
    server.session = accountB;
    const untouchedB = await readBrowserJourneyPartition(accountB);

    server.held.shift()!();
    expect(await slow).toBe('acknowledged');
    const savedA = await readBrowserJourneyPartition(accountA);
    expect(savedA.outbox.entries).toEqual([]);
    expect(savedA.snapshots.journeys.map((journey) => journey.id)).toEqual([first]);
    expect(await readBrowserJourneyPartition(accountB)).toEqual(untouchedB);

    // Account A's device work stops while B is signed in; B sends only its own action.
    await queue(accountA, { journeyId: first, action: 'complete' });
    expect(await dispatchBrowserJourneyOnce(accountA)).toBe('idle');
    expect(await dispatchBrowserJourneyOnce(accountB)).toBe('acknowledged');
    expect(writes(accountB).map((a) => a.operation)).toEqual([`start:${other}:commute`]);
    expect(server.journeys.get(accountB)?.has(first)).toBeFalsy();
    expect((await head(accountA))?.attempts).toBe(0);
  });

  it('keeps a server-applied action when local storage fails during acknowledgement and replays it after the lease', async () => {
    await queue(accountA, { journeyId: first, action: 'start', kind: 'trip' });
    const storage = globalThis.indexedDB;
    server.faults.push('storage-fails-after');

    await expect(dispatchBrowserJourneyOnce(accountA)).rejects.toThrow();
    expect(server.journeys.get(accountA)?.get(first)).toEqual({ kind: 'trip', status: 'active' });

    vi.stubGlobal('indexedDB', storage);
    const leased = await head(accountA);
    expect(leased?.lease).not.toBeNull();
    expect(leased?.command).toEqual({ journeyId: first, action: 'start', kind: 'trip' });
    // The interrupted worker's lease still guards the action until it expires.
    expect(await dispatchBrowserJourneyOnce(accountA)).toBe('idle');

    server.now = leased!.lease!.expiresAt;
    expect(await dispatchBrowserJourneyOnce(accountA)).toBe('acknowledged');
    expect(server.applied).toEqual([
      `${accountA}:start:${first}:trip`,
      `${accountA}:start:${first}:trip`,
    ]);
    expect(server.journeys.get(accountA)?.size).toBe(1);
    expect((await readBrowserJourneyPartition(accountA)).snapshots.journeys).toMatchObject([
      { id: first, status: 'active' },
    ]);
  });
});
