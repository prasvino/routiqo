import type { JourneyKind } from './planning';

export type JourneyCommand =
  | { journeyId: string; action: 'start'; kind: JourneyKind }
  | { journeyId: string; action: 'complete' };
export type OutboxBlock = 'authentication' | 'conflict' | 'rejected';
export interface OutboxEntry {
  command: JourneyCommand;
  attempts: number;
  nextAttemptAt: number;
  lease: { token: string; expiresAt: number } | null;
  blocked: OutboxBlock | null;
}
export interface JourneyOutbox {
  version: 1;
  accountId: string;
  entries: OutboxEntry[];
}
export type OutboxOutcome = 'success' | 'transient' | OutboxBlock;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const timestamp = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 8e15;
function requireId(id: string) {
  if (!uuid.test(id)) throw new Error('Invalid outbox identity.');
}
function requireTime(now: number) {
  if (!timestamp(now) || now > 8e15 - 300_000) throw new Error('Invalid outbox time.');
}
function commandFrom(value: unknown): JourneyCommand {
  if (!record(value) || typeof value.journeyId !== 'string')
    throw new Error('Invalid journey command.');
  requireId(value.journeyId);
  if (value.action === 'complete') return { journeyId: value.journeyId, action: 'complete' };
  if (value.action === 'start' && (value.kind === 'trip' || value.kind === 'commute'))
    return { journeyId: value.journeyId, action: 'start', kind: value.kind };
  throw new Error('Invalid journey command.');
}
export const journeyCommandKey = (command: JourneyCommand): string =>
  `${command.journeyId}:${command.action}`;
export function emptyJourneyOutbox(accountId: string): JourneyOutbox {
  requireId(accountId);
  return { version: 1, accountId, entries: [] };
}

/** Corruption must surface; callers must not overwrite unreadable pending work. */
export function readJourneyOutbox(raw: string | null, accountId: string): JourneyOutbox {
  requireId(accountId);
  if (raw === null) return emptyJourneyOutbox(accountId);
  if (raw.length > 256 * 1024 || new TextEncoder().encode(raw).length > 256 * 1024)
    throw new Error('Journey outbox exceeds its storage limit.');
  const value: unknown = JSON.parse(raw);
  if (
    !record(value) ||
    value.version !== 1 ||
    value.accountId !== accountId ||
    !Array.isArray(value.entries) ||
    value.entries.length > 100
  )
    throw new Error('Journey outbox cannot be read for this account.');
  const entries: OutboxEntry[] = value.entries.map((entry: unknown) => {
    if (
      !record(entry) ||
      !Number.isInteger(entry.attempts) ||
      typeof entry.attempts !== 'number' ||
      entry.attempts < 0 ||
      entry.attempts > 31 ||
      !timestamp(entry.nextAttemptAt) ||
      ![null, 'authentication', 'conflict', 'rejected'].includes(entry.blocked as string | null)
    )
      throw new Error('Invalid pending journey action.');
    let lease: OutboxEntry['lease'] = null;
    if (entry.lease !== null) {
      if (
        !record(entry.lease) ||
        typeof entry.lease.token !== 'string' ||
        !timestamp(entry.lease.expiresAt) ||
        entry.blocked !== null ||
        entry.attempts === 0
      )
        throw new Error('Invalid pending journey lease.');
      requireId(entry.lease.token);
      lease = { token: entry.lease.token, expiresAt: entry.lease.expiresAt };
    }
    return {
      command: commandFrom(entry.command),
      attempts: entry.attempts,
      nextAttemptAt: entry.nextAttemptAt,
      lease,
      blocked: entry.blocked as OutboxBlock | null,
    };
  });
  if (
    new Set(entries.map((e) => journeyCommandKey(e.command))).size !== entries.length ||
    entries.slice(1).some((e) => e.lease !== null || e.blocked !== null)
  )
    throw new Error('Invalid journey action order.');
  return { version: 1, accountId, entries };
}

export function enqueueJourneyCommand(
  state: JourneyOutbox,
  input: JourneyCommand,
  now: number,
): JourneyOutbox {
  requireTime(now);
  const command = commandFrom(input);
  const existing = state.entries.find(
    (e) => journeyCommandKey(e.command) === journeyCommandKey(command),
  );
  if (existing) {
    if (
      existing.command.action === 'start' &&
      command.action === 'start' &&
      existing.command.kind !== command.kind
    )
      throw new Error('A different start is already pending for this journey.');
    return state;
  }
  if (state.entries.length >= 100) throw new Error('Pending journey actions are full.');
  return {
    ...state,
    entries: [
      ...state.entries,
      { command, attempts: 0, nextAttemptAt: now, lease: null, blocked: null },
    ],
  };
}

export function claimJourneyCommand(
  state: JourneyOutbox,
  now: number,
  token: string,
): JourneyOutbox {
  requireTime(now);
  requireId(token);
  const first = state.entries[0];
  if (
    !first ||
    first.blocked ||
    first.nextAttemptAt > now ||
    (first.lease && first.lease.expiresAt > now)
  )
    return state;
  if (first.lease?.token === token) throw new Error('A retry requires a new lease identity.');
  return {
    ...state,
    entries: [
      {
        ...first,
        attempts: Math.min(31, first.attempts + 1),
        lease: { token, expiresAt: now + 30_000 },
      },
      ...state.entries.slice(1),
    ],
  };
}

export function settleJourneyCommand(
  state: JourneyOutbox,
  token: string,
  outcome: OutboxOutcome,
  now: number,
  jitter = 0.5,
): JourneyOutbox {
  requireTime(now);
  requireId(token);
  if (!Number.isFinite(jitter) || jitter < 0 || jitter > 1)
    throw new Error('Invalid retry jitter.');
  const first = state.entries[0];
  // The response may belong to an expired/replaced worker. Only its current lease can settle.
  if (!first || first.lease?.token !== token) return state;
  if (outcome === 'success') return { ...state, entries: state.entries.slice(1) };
  if (outcome === 'transient') {
    const delay = Math.floor(
      Math.min(300_000, 1000 * 2 ** (first.attempts - 1)) * (0.5 + jitter / 2),
    );
    return {
      ...state,
      entries: [{ ...first, lease: null, nextAttemptAt: now + delay }, ...state.entries.slice(1)],
    };
  }
  if (!['authentication', 'conflict', 'rejected'].includes(outcome))
    throw new Error('Invalid sync outcome.');
  return {
    ...state,
    entries: [{ ...first, lease: null, blocked: outcome }, ...state.entries.slice(1)],
  };
}

/** Call only after this account has a restored, verified session. */
export function resumeJourneyAuthentication(
  state: JourneyOutbox,
  accountId: string,
  now: number,
): JourneyOutbox {
  requireTime(now);
  if (state.accountId !== accountId) throw new Error('Journey outbox account mismatch.');
  const first = state.entries[0];
  if (first?.blocked !== 'authentication') return state;
  return {
    ...state,
    entries: [{ ...first, blocked: null, nextAttemptAt: now }, ...state.entries.slice(1)],
  };
}
