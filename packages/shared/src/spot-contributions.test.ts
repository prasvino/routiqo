import { describe, expect, it } from 'vitest';
import {
  SPOT_OUTBOX_MAX_ENTRIES,
  SpotOutboxFull,
  agoLabel,
  defaultSpotPostType,
  dropExpiredSpotContributions,
  enqueueSpotContribution,
  nextSpotContribution,
  readSpotContributionReceipt,
  readSpotOutbox,
  readSpotReportReceipt,
  readSpotVoteResult,
  settleSpotContribution,
  spotSignalSummaryLabel,
  validateSpotPostText,
  type NewSpotContribution,
} from './spot-contributions';

const account = '00000000-0000-4000-8000-000000000001';
const id = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const NOW = Date.parse('2026-11-05T06:30:00Z');
const journey = id(500);

const signal = (
  n: number,
  category = 'traffic',
  value = 'slow',
  spot = id(1),
): NewSpotContribution =>
  ({
    kind: 'signal',
    clientKey: id(n),
    spotId: spot,
    journeyId: journey,
    capturedAt: new Date(NOW).toISOString(),
    category,
    value,
  }) as NewSpotContribution;
const post = (
  n: number,
  text = 'Lane 3 moving',
  type: 'traffic' | 'place' = 'traffic',
): NewSpotContribution => ({
  kind: 'post',
  clientKey: id(n),
  spotId: id(1),
  journeyId: journey,
  capturedAt: new Date(NOW).toISOString(),
  type,
  text,
});

describe('post text mirrors the server rules', () => {
  it('accepts Tamil, emoji, ZWJ and ordinary numbers', () => {
    expect(validateSpotPostText('  Toll queue moving fast  ')).toEqual({
      ok: true,
      text: 'Toll queue moving fast',
    });
    for (const good of [
      'சுங்கச்சாவடியில் நீண்ட வரிசை',
      'Semma rush da 😅 👨‍👩‍👧',
      'ஸ்ரீ‍ராம் hotel good',
      'NH 32 km 45, gate 2: 3 lanes open',
      'அ'.repeat(200),
    ])
      expect(validateSpotPostText(good).ok).toBe(true);
  });

  it('refuses what the server refuses, with the reason', () => {
    const problem = (text: string) => {
      const result = validateSpotPostText(text);
      return result.ok ? null : result.problem;
    };
    expect(problem('')).toBe('empty');
    expect(problem('   ')).toBe('empty');
    expect(problem('a'.repeat(201))).toBe('too-long');
    for (const bad of ['line one\nline two', 'tab\u0007bell', '\uD800 lone', 'rtl‮override'])
      expect(problem(bad)).toBe('characters');
    for (const spam of [
      'Call 9876543210',
      'Call 98765 43210',
      'call 044-2345-678',
      'visit www.example.in',
      'see https://x.co',
      'best biryani at example.com',
      'mail me a@b.co',
      'Tamil digits ௯௮௭௬௫௪௩',
    ])
      expect(problem(spam)).toBe('contact');
  });
});

describe('labels', () => {
  it('shows counts and freshness, never reporters', () => {
    expect(
      spotSignalSummaryLabel(
        {
          ref: id(9),
          category: 'traffic',
          value: 'slow',
          values: [
            { value: 'slow', reports: 2 },
            { value: 'moving', reports: 1 },
          ],
          latestAt: '2026-11-05T06:18:00Z',
          stillTrue: 0,
          viewerVote: null,
        },
        NOW,
      ),
    ).toBe('Slow · 2 reports, Moving · 1 · latest 12 min ago');
    expect(agoLabel(NOW - 30_000, NOW)).toBe('just now');
    expect(agoLabel(NOW - 3 * 3_600_000, NOW)).toBe('3 h ago');
    expect(agoLabel(NOW - 3 * 86_400_000, NOW)).toBe('3 days ago');
    expect(defaultSpotPostType('toll')).toBe('traffic');
    expect(defaultSpotPostType('eatery')).toBe('place');
  });
});

describe('receipts', () => {
  it('reads only the documented shapes', () => {
    expect(
      readSpotContributionReceipt({
        ref: id(3),
        status: 'active',
        expiresAt: '2026-11-05T07:30:00Z',
        alias: 'Calm Auto',
      }).alias,
    ).toBe('Calm Auto');
    expect(() =>
      readSpotContributionReceipt({
        ref: id(3),
        status: 'posted',
        expiresAt: '2026-11-05T07:30:00Z',
      }),
    ).toThrow();
    expect(
      readSpotVoteResult({
        ref: id(3),
        status: 'expired',
        expiresAt: '2026-11-05T07:30:00Z',
        stillTrue: 2,
      }).stillTrue,
    ).toBe(2);
    expect(() =>
      readSpotVoteResult({
        ref: id(3),
        status: 'active',
        expiresAt: '2026-11-05T07:30:00Z',
        stillTrue: -1,
      }),
    ).toThrow();
    expect(
      readSpotReportReceipt({
        receivedAt: '2026-11-05T06:30:00Z',
        receiptExpiresAt: '2026-11-12T06:30:00Z',
      }).receivedAt,
    ).toBe('2026-11-05T06:30:00Z');
    expect(() => readSpotReportReceipt({ receivedAt: 'now' })).toThrow();
  });
});

describe('spot outbox', () => {
  const empty = readSpotOutbox(null, account);

  it('keeps order, replaces a queued signal for the same slot and enforces the caps', () => {
    let outbox = enqueueSpotContribution(empty, signal(10), NOW);
    outbox = enqueueSpotContribution(outbox, post(11), NOW);
    outbox = enqueueSpotContribution(outbox, signal(12, 'traffic', 'moving'), NOW);
    expect(outbox.entries.map((entry) => entry.clientKey)).toEqual([id(11), id(12)]);
    for (let n = 13; n < 13 + SPOT_OUTBOX_MAX_ENTRIES - 2; n++)
      outbox = enqueueSpotContribution(outbox, post(n, `Tip ${n}`, 'place'), NOW);
    expect(outbox.entries).toHaveLength(SPOT_OUTBOX_MAX_ENTRIES);
    expect(() => enqueueSpotContribution(outbox, post(99, 'One more'), NOW)).toThrow(
      SpotOutboxFull,
    );
    // The 32 KiB cap is a backstop: twenty longest posts of 4-byte emoji still fit under it.
    let big = empty;
    for (let n = 100; n < 120; n++)
      big = enqueueSpotContribution(big, post(n, '😅'.repeat(200)), NOW);
    expect(big.entries).toHaveLength(SPOT_OUTBOX_MAX_ENTRIES);
    expect(() => enqueueSpotContribution(empty, post(5, 'call 9876543210'), NOW)).toThrow();
    expect(() => enqueueSpotContribution(empty, signal(6, 'traffic', 'busy'), NOW)).toThrow();
  });

  it('round-trips through storage and reads malformed or foreign data as empty', () => {
    const outbox = enqueueSpotContribution(empty, post(20), NOW);
    expect(readSpotOutbox(JSON.parse(JSON.stringify(outbox)), account)).toEqual(outbox);
    expect(readSpotOutbox({ ...outbox, accountId: id(2) }, account).entries).toEqual([]);
    expect(
      readSpotOutbox({ accountId: account, entries: [{ kind: 'post' }] }, account).entries,
    ).toEqual([]);
    expect(readSpotOutbox('garbage', account).entries).toEqual([]);
  });

  it('drops entries past their base life on the device clock', () => {
    let outbox = enqueueSpotContribution(empty, signal(30), NOW);
    outbox = enqueueSpotContribution(outbox, post(31, 'Good dosa', 'place'), NOW);
    const later = dropExpiredSpotContributions(outbox, NOW + 61 * 60_000);
    expect(later.dropped.map((entry) => entry.clientKey)).toEqual([id(30)]);
    expect(later.outbox.entries.map((entry) => entry.clientKey)).toEqual([id(31)]);
  });

  it('sends strictly in order once due and once the journey is on the server', () => {
    let outbox = enqueueSpotContribution(empty, post(40), NOW);
    outbox = enqueueSpotContribution(outbox, signal(41), NOW);
    expect(nextSpotContribution(outbox, NOW, () => false)).toBeNull();
    expect(nextSpotContribution(outbox, NOW, () => true)?.clientKey).toBe(id(40));
    const retried = settleSpotContribution(outbox, id(40), { kind: 'retry' }, NOW, 1);
    expect(retried.entries[0]).toMatchObject({ attempts: 1, nextAttemptAt: NOW + 1000 });
    expect(nextSpotContribution(retried, NOW + 999, () => true)).toBeNull();
    const limited = settleSpotContribution(
      outbox,
      id(40),
      { kind: 'retry', retryAfterMs: 120_000 },
      NOW,
      0,
    );
    expect(limited.entries[0]!.nextAttemptAt).toBe(NOW + 120_000);
    const sent = settleSpotContribution(outbox, id(40), { kind: 'sent' }, NOW);
    expect(nextSpotContribution(sent, NOW, () => true)?.clientKey).toBe(id(41));
    expect(settleSpotContribution(sent, id(41), { kind: 'refused' }, NOW).entries).toEqual([]);
    let capped = outbox;
    for (let n = 0; n < 40; n++)
      capped = settleSpotContribution(capped, id(40), { kind: 'retry' }, NOW, 1);
    expect(capped.entries[0]!.nextAttemptAt - NOW).toBe(5 * 60_000);
  });
});
