import type { SpotCategory, SpotKind, SpotPostType, SpotSignalSummary } from './spots';

/**
 * Contributions on Spots (POSTS_AND_SIGNALS_SPEC, ADRs 0071-0073): signal values and labels, the
 * post text rules the server applies, receipts, and the device outbox model for signals and posts.
 * The server stays authoritative; these checks only tell the author early.
 */

export const SPOT_SIGNAL_VALUES: Readonly<Record<SpotCategory, readonly string[]>> = {
  traffic: ['moving', 'slow', 'stopped'],
  queue: ['under_5', '5_to_15', '15_to_30', 'over_30'],
  food: ['good', 'avoid'],
  fuel: ['available', 'long_queue', 'none'],
  restroom: ['usable', 'busy', 'avoid'],
};

const categoryLabels: Record<SpotCategory, string> = {
  traffic: 'Traffic',
  queue: 'Queue',
  food: 'Food',
  fuel: 'Fuel',
  restroom: 'Restroom',
};

const valueLabels: Record<string, string> = {
  moving: 'Moving',
  slow: 'Slow',
  stopped: 'Stopped',
  under_5: 'Under 5 min',
  '5_to_15': '5–15 min',
  '15_to_30': '15–30 min',
  over_30: 'Over 30 min',
  good: 'Good',
  avoid: 'Avoid',
  available: 'Available',
  long_queue: 'Long queue',
  none: 'None',
  usable: 'Usable',
  busy: 'Busy',
};

export const spotCategoryLabel = (category: SpotCategory) => categoryLabels[category];
/** Known values get their label; an unknown additive value is shown plainly, never dropped. */
export const spotSignalValueLabel = (value: string) =>
  valueLabels[value] ?? value.replace(/_/g, ' ');

/** Base lives (ms): an item older than this on the device clock would be refused as too old. */
const MINUTE = 60_000;
const signalLife: Record<SpotCategory, number> = {
  traffic: 60 * MINUTE,
  queue: 60 * MINUTE,
  food: 24 * 60 * MINUTE,
  fuel: 24 * 60 * MINUTE,
  restroom: 24 * 60 * MINUTE,
};
const postLife: Record<SpotPostType, number> = { traffic: 90 * MINUTE, place: 24 * 60 * MINUTE };

export const spotSignalBaseLifeMs = (category: SpotCategory) => signalLife[category];
export const spotPostBaseLifeMs = (type: SpotPostType) => postLife[type];

/** Traffic posts for places where traffic is the point; everything else defaults to a place tip. */
export function defaultSpotPostType(kind: SpotKind): SpotPostType {
  return kind === 'toll' || kind === 'junction' ? 'traffic' : 'place';
}

export const SPOT_POST_MAX_CODE_POINTS = 200;
export type SpotPostTextProblem = 'empty' | 'too-long' | 'characters' | 'contact';

export const spotPostTextMessages: Record<SpotPostTextProblem, string> = {
  empty: 'Write something first.',
  'too-long': 'Posts can be at most 200 characters.',
  characters: 'Posts must be one paragraph without special characters.',
  contact: "Links and phone numbers aren't allowed in posts.",
};

// Mirrors PostText.java: controls, surrogates, unassigned, private use, line and paragraph
// separators and format characters are refused, except ZWNJ and ZWJ used in Tamil and emoji.
const disallowed = /(?![‌‍])[\p{Cc}\p{Cs}\p{Cn}\p{Co}\p{Zl}\p{Zp}\p{Cf}]/u;
const contact = new RegExp(
  [
    String.raw`\p{Nd}(?:[ \-]?\p{Nd}){6,}`,
    String.raw`https?:\/\/|www\.|[\p{L}\p{Nd}._%+\-]+@[\p{L}\p{Nd}.\-]+\.\p{L}{2,}`,
    String.raw`\b[\p{L}\p{Nd}\-]+\.(?:com|in|net|org|co|io|me|info|biz|app|link|ly)\b`,
  ].join('|'),
  'iu',
);

/** The trimmed text to send, or the first problem the server would also refuse. */
export function validateSpotPostText(
  raw: string,
): { ok: true; text: string } | { ok: false; problem: SpotPostTextProblem } {
  const text = raw.trim();
  const length = [...text].length;
  if (length === 0) return { ok: false, problem: 'empty' };
  if (length > SPOT_POST_MAX_CODE_POINTS) return { ok: false, problem: 'too-long' };
  if (disallowed.test(text)) return { ok: false, problem: 'characters' };
  if (contact.test(text)) return { ok: false, problem: 'contact' };
  return { ok: true, text };
}

/** "just now", "12 min ago", "3 h ago" or "2 days ago". */
export function agoLabel(at: string | number, now: number): string {
  const when = typeof at === 'number' ? at : Date.parse(at);
  const minutes = Math.max(0, Math.floor((now - when) / MINUTE));
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.floor(hours / 24)} days ago`;
}

/** "Slow · 2 reports, Moving · 1 · latest 12 min ago": counts only, never who reported. */
export function spotSignalSummaryLabel(summary: SpotSignalSummary, now: number): string {
  const counts = summary.values
    .map(
      (entry, index) =>
        `${spotSignalValueLabel(entry.value)} · ${entry.reports}${
          index === 0 ? (entry.reports === 1 ? ' report' : ' reports') : ''
        }`,
    )
    .join(', ');
  return `${counts} · latest ${agoLabel(summary.latestAt, now)}`;
}

export const stillTrueLabel = (count: number) => `Still true · ${count}`;

export const SPOT_REPORT_REASONS = [
  { reason: 'false_alarm', label: 'Not accurate' },
  { reason: 'abuse', label: 'Abuse or hate' },
  { reason: 'spam', label: 'Spam or advertising' },
  { reason: 'personal_data', label: 'Personal information' },
  { reason: 'unsafe', label: 'Unsafe' },
] as const;
export type SpotReportReason = (typeof SPOT_REPORT_REASONS)[number]['reason'];

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const instant = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

function receiptInvalid(): never {
  throw new Error('The Spot receipt is not valid.');
}
function object(input: unknown): Record<string, unknown> {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) receiptInvalid();
  return input as Record<string, unknown>;
}
function ref(input: unknown): string {
  if (typeof input !== 'string' || !uuid.test(input)) receiptInvalid();
  return input;
}
function at(input: unknown): string {
  if (typeof input !== 'string' || !instant.test(input)) receiptInvalid();
  return input;
}

export interface SpotContributionReceipt {
  ref: string;
  status: 'active' | 'replaced' | 'deleted' | 'expired';
  expiresAt: string;
  alias?: string;
}

export function readSpotContributionReceipt(input: unknown): SpotContributionReceipt {
  const root = object(input);
  const status = root.status;
  if (status !== 'active' && status !== 'replaced' && status !== 'deleted' && status !== 'expired')
    receiptInvalid();
  const receipt: SpotContributionReceipt = {
    ref: ref(root.ref),
    status,
    expiresAt: at(root.expiresAt),
  };
  if (root.alias !== undefined) {
    if (typeof root.alias !== 'string' || root.alias.length === 0 || root.alias.length > 40)
      receiptInvalid();
    receipt.alias = root.alias;
  }
  return receipt;
}

export interface SpotVoteResult {
  ref: string;
  status: 'active' | 'expired';
  expiresAt: string;
  stillTrue: number;
}

export function readSpotVoteResult(input: unknown): SpotVoteResult {
  const root = object(input);
  if (root.status !== 'active' && root.status !== 'expired') receiptInvalid();
  if (
    typeof root.stillTrue !== 'number' ||
    !Number.isSafeInteger(root.stillTrue) ||
    root.stillTrue < 0
  )
    receiptInvalid();
  return {
    ref: ref(root.ref),
    status: root.status,
    expiresAt: at(root.expiresAt),
    stillTrue: root.stillTrue,
  };
}

export function readSpotReportReceipt(input: unknown): {
  receivedAt: string;
  receiptExpiresAt: string;
} {
  const root = object(input);
  return { receivedAt: at(root.receivedAt), receiptExpiresAt: at(root.receiptExpiresAt) };
}

// ---------------------------------------------------------------------------------------------
// Device outbox for signals and posts (`spot_outbox_v1`): account-partitioned, in order, bounded.

export const SPOT_OUTBOX_MAX_ENTRIES = 20;
export const SPOT_OUTBOX_MAX_BYTES = 32 * 1024;
export const SPOT_OUTBOX_MAX_BACKOFF_MS = 5 * MINUTE;

interface QueuedBase {
  clientKey: string;
  spotId: string;
  journeyId: string;
  /** Device capture time; the server only ever shortens life from it. */
  capturedAt: string;
  attempts: number;
  nextAttemptAt: number;
}
export type QueuedSpotSignal = QueuedBase & {
  kind: 'signal';
  category: SpotCategory;
  value: string;
};
export type QueuedSpotPost = QueuedBase & { kind: 'post'; type: SpotPostType; text: string };
export type QueuedSpotContribution = QueuedSpotSignal | QueuedSpotPost;

export interface SpotOutbox {
  accountId: string;
  entries: QueuedSpotContribution[];
}

export class SpotOutboxFull extends Error {
  constructor() {
    super('Too many items are waiting to send. Try again once they have gone.');
    this.name = 'SpotOutboxFull';
  }
}

const categories: ReadonlySet<string> = new Set(Object.keys(SPOT_SIGNAL_VALUES));

function queued(input: unknown): QueuedSpotContribution {
  const entry = object(input);
  const base: QueuedBase = {
    clientKey: ref(entry.clientKey),
    spotId: ref(entry.spotId),
    journeyId: ref(entry.journeyId),
    capturedAt: at(entry.capturedAt),
    attempts:
      typeof entry.attempts === 'number' &&
      Number.isSafeInteger(entry.attempts) &&
      entry.attempts >= 0
        ? Math.min(entry.attempts, 31)
        : receiptInvalid(),
    nextAttemptAt:
      typeof entry.nextAttemptAt === 'number' && Number.isFinite(entry.nextAttemptAt)
        ? entry.nextAttemptAt
        : receiptInvalid(),
  };
  if (entry.kind === 'signal') {
    if (typeof entry.category !== 'string' || !categories.has(entry.category)) receiptInvalid();
    const category = entry.category as SpotCategory;
    if (typeof entry.value !== 'string' || !SPOT_SIGNAL_VALUES[category].includes(entry.value))
      receiptInvalid();
    return { ...base, kind: 'signal', category, value: entry.value };
  }
  if (entry.kind === 'post') {
    if (entry.type !== 'traffic' && entry.type !== 'place') receiptInvalid();
    if (typeof entry.text !== 'string') receiptInvalid();
    const checked = validateSpotPostText(entry.text);
    if (!checked.ok || checked.text !== entry.text) receiptInvalid();
    return { ...base, kind: 'post', type: entry.type, text: entry.text };
  }
  return receiptInvalid();
}

/** A stored outbox for this account; anything malformed or foreign reads as empty. */
export function readSpotOutbox(input: unknown, accountId: string): SpotOutbox {
  if (!uuid.test(accountId)) throw new Error('Invalid account.');
  const empty = { accountId, entries: [] };
  if (input === null || input === undefined) return empty;
  try {
    const root = object(input);
    if (root.accountId !== accountId || !Array.isArray(root.entries)) return empty;
    // One unreadable entry (for example after a rule change) is dropped, not the whole queue.
    const seen = new Set<string>();
    const entries = root.entries.slice(0, SPOT_OUTBOX_MAX_ENTRIES).flatMap((value) => {
      try {
        const entry = queued(value);
        if (seen.has(entry.clientKey)) return [];
        seen.add(entry.clientKey);
        return [entry];
      } catch {
        return [];
      }
    });
    return { accountId, entries };
  } catch {
    return empty;
  }
}

const bytes = (outbox: SpotOutbox) => new TextEncoder().encode(JSON.stringify(outbox)).length;

export type NewSpotContribution =
  | Omit<QueuedSpotSignal, 'attempts' | 'nextAttemptAt'>
  | Omit<QueuedSpotPost, 'attempts' | 'nextAttemptAt'>;

/**
 * Adds a contribution at the end. A new signal replaces a queued one for the same Spot and
 * category, as the server would. Throws {@link SpotOutboxFull} beyond 20 entries or 32 KiB.
 */
export function enqueueSpotContribution(
  outbox: SpotOutbox,
  contribution: NewSpotContribution,
  now: number,
): SpotOutbox {
  const entry = queued({ ...contribution, attempts: 0, nextAttemptAt: now });
  const kept = outbox.entries.filter(
    (existing) =>
      existing.clientKey !== entry.clientKey &&
      !(
        entry.kind === 'signal' &&
        existing.kind === 'signal' &&
        existing.spotId === entry.spotId &&
        existing.category === entry.category
      ),
  );
  const next = { accountId: outbox.accountId, entries: [...kept, entry] };
  if (next.entries.length > SPOT_OUTBOX_MAX_ENTRIES || bytes(next) > SPOT_OUTBOX_MAX_BYTES)
    throw new SpotOutboxFull();
  return next;
}

export function spotContributionBaseLifeMs(entry: QueuedSpotContribution): number {
  return entry.kind === 'signal' ? signalLife[entry.category] : postLife[entry.type];
}

/** Drops entries whose base life has passed on the device clock; they would be refused anyway. */
export function dropExpiredSpotContributions(
  outbox: SpotOutbox,
  now: number,
): { outbox: SpotOutbox; dropped: QueuedSpotContribution[] } {
  const dropped: QueuedSpotContribution[] = [];
  const entries = outbox.entries.filter((entry) => {
    const alive = Date.parse(entry.capturedAt) + spotContributionBaseLifeMs(entry) > now;
    if (!alive) dropped.push(entry);
    return alive;
  });
  return { outbox: { accountId: outbox.accountId, entries }, dropped };
}

/**
 * The next entry to send: in order within each kind, once due and once its journey is known on the
 * server. A post waiting out a rate limit never holds up signals (and the reverse), but a later
 * post never overtakes an earlier post.
 */
export function nextSpotContribution(
  outbox: SpotOutbox,
  now: number,
  journeyOnServer: (journeyId: string) => boolean,
): QueuedSpotContribution | null {
  const waiting = new Set<QueuedSpotContribution['kind']>();
  for (const entry of outbox.entries) {
    if (waiting.has(entry.kind)) continue;
    if (entry.nextAttemptAt <= now && journeyOnServer(entry.journeyId)) return entry;
    waiting.add(entry.kind);
  }
  return null;
}

/** When the next entry could be sent: the earliest due time among the first entry of each kind. */
export function nextSpotAttemptAt(
  outbox: SpotOutbox,
  journeyOnServer: (journeyId: string) => boolean,
): number | null {
  const heads = new Map<QueuedSpotContribution['kind'], QueuedSpotContribution>();
  for (const entry of outbox.entries) if (!heads.has(entry.kind)) heads.set(entry.kind, entry);
  const due = [...heads.values()]
    .filter((entry) => journeyOnServer(entry.journeyId))
    .map((entry) => entry.nextAttemptAt);
  return due.length > 0 ? Math.min(...due) : null;
}

export type SpotSendOutcome =
  { kind: 'sent' } | { kind: 'refused' } | { kind: 'retry'; retryAfterMs?: number };

/** Removes a sent or refused entry; a retry backs off (2^n s, at most 5 min, with jitter). */
export function settleSpotContribution(
  outbox: SpotOutbox,
  clientKey: string,
  outcome: SpotSendOutcome,
  now: number,
  jitter = 0.5,
): SpotOutbox {
  if (!Number.isFinite(jitter) || jitter < 0 || jitter > 1)
    throw new Error('Invalid retry jitter.');
  const entries = outbox.entries.flatMap((entry) => {
    if (entry.clientKey !== clientKey) return [entry];
    if (outcome.kind !== 'retry') return [];
    const attempts = Math.min(entry.attempts + 1, 31);
    const backoff = Math.floor(
      Math.min(SPOT_OUTBOX_MAX_BACKOFF_MS, 1000 * 2 ** (attempts - 1)) * (0.5 + jitter / 2),
    );
    const delay = Math.max(backoff, Math.min(outcome.retryAfterMs ?? 0, 60 * MINUTE));
    return [{ ...entry, attempts, nextAttemptAt: now + delay }];
  });
  return { accountId: outbox.accountId, entries };
}
