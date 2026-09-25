import { vi } from 'vitest';

/**
 * A synthetic core API for browser journey scenarios. It follows the server's journey rules:
 * - a start is idempotent for the same id and kind, and returns 409 for another kind or while the
 *   account already has a different active journey;
 * - a finish is idempotent once completed, and returns 404 for a journey the account does not have;
 * - reads and writes are owner-scoped by the signed-in session.
 */

/** How the network treats one journey write. "after" faults happen once the server has applied it. */
export type Fault =
  | 'ok'
  | 'drop-before'
  | 'drop-after'
  | 'unavailable'
  | 'throttled'
  | 'unauthorized'
  | 'malformed-after'
  | 'rejected-after'
  | 'hold-after'
  | 'storage-fails-after';

type Stored = {
  kind: 'trip' | 'commute';
  status: 'active' | 'completed';
  startedAt: number;
  completedAt: number | null;
};

export class SyntheticJourneyServer {
  readonly journeys = new Map<string, Map<string, Stored>>();
  /** Every write the server actually applied or replayed, in arrival order. */
  readonly applied: string[] = [];
  /** Every write the client attempted, with the client clock at send time. */
  readonly attempts: { account: string; operation: string; at: number }[] = [];
  readonly faults: Fault[] = [];
  readonly held: Array<() => void> = [];
  now = 1_780_000_000_000;

  constructor(public session: string | null) {}

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
    // The account header must match the signed-in session, as the real proxy requires.
    if (account !== this.session) return new Response(null, { status: 401 });
    if ((init?.method ?? 'GET') === 'GET') return this.read(account, url);

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
    if (fault === 'unauthorized') return new Response(null, { status: 401 });
    const response = complete ? this.complete(account, complete[1]!) : this.start(account, body);
    if (response.status === 200) this.applied.push(`${account}:${operation}`);
    if (fault === 'drop-after') throw new TypeError('Failed to fetch');
    if (fault === 'malformed-after') return new Response('{"id":', { status: 200 });
    if (fault === 'rejected-after') return new Response(null, { status: 400 });
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

  /** Journeys the server holds for one account, as plain values. */
  stored(account: string) {
    return [...this.owned(account)].map(([id, journey]) => ({
      id,
      kind: journey.kind,
      status: journey.status,
    }));
  }

  private read(account: string, url: string) {
    if (url === '/api/v1/journeys?limit=20') {
      const journeys = [...this.owned(account)]
        .sort(([, a], [, b]) => b.startedAt - a.startedAt)
        .slice(0, 20)
        .map(([id, journey]) => this.body(id, journey));
      return Response.json({ journeys });
    }
    const detail = /^\/api\/v1\/journeys\/([0-9a-f-]{36})$/.exec(url);
    const journey = detail ? this.owned(account).get(detail[1]!) : undefined;
    if (!detail || !journey) return new Response(null, { status: 404 });
    return Response.json(this.body(detail[1]!, journey));
  }
  private owned(account: string) {
    let journeys = this.journeys.get(account);
    if (!journeys) this.journeys.set(account, (journeys = new Map()));
    return journeys;
  }
  private start(account: string, body: { id?: string; kind?: 'trip' | 'commute' }) {
    const journeys = this.owned(account);
    const existing = journeys.get(body.id!);
    if (existing) {
      if (existing.kind !== body.kind) return new Response(null, { status: 409 });
      return Response.json(this.body(body.id!, existing));
    }
    if ([...journeys.values()].some((journey) => journey.status === 'active'))
      return new Response(null, { status: 409 });
    const journey: Stored = {
      kind: body.kind!,
      status: 'active',
      startedAt: this.now,
      completedAt: null,
    };
    journeys.set(body.id!, journey);
    return Response.json(this.body(body.id!, journey));
  }
  private complete(account: string, id: string) {
    const journey = this.owned(account).get(id);
    if (!journey) return new Response(null, { status: 404 });
    if (journey.status === 'active') {
      journey.status = 'completed';
      journey.completedAt = Math.max(this.now, journey.startedAt + 1000);
    }
    return Response.json(this.body(id, journey));
  }
  private body(id: string, journey: Stored) {
    return {
      id,
      kind: journey.kind,
      status: journey.status,
      startedAt: new Date(journey.startedAt).toISOString(),
      completedAt:
        journey.completedAt === null ? null : new Date(journey.completedAt).toISOString(),
    };
  }
}
