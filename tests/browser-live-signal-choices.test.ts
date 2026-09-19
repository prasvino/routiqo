import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  BrowserLiveError,
  issueBrowserLiveExpectedSignalCommand,
  readBrowserLiveSignalChoices,
} from '../apps/web/lib/browser-live';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const contextId = '00000000-0000-4000-8000-000000000003';
const anchorId = '00000000-0000-4000-8000-000000000004';
const commandId = '00000000-0000-4000-8000-000000000005';
const csrfToken = 'C'.repeat(32);
const issuedAt = '2026-09-19T10:00:00.123456789Z';
const snapshotExpiresAt = '2026-09-20T10:00:00.123456789Z';
const grantExpiresAt = '2026-09-19T10:01:30.123456789Z';

const expected = {
  anchorId,
  contextId,
  routeRevision: '9223372036854775807',
  consentGeneration: '9007199254740993',
};

const choice = {
  anchorId,
  displayLabel: 'Central route area',
  categories: ['food_queue', 'parking', 'queue', 'restroom', 'traffic'],
};

const snapshot = {
  contextId,
  routeRevision: expected.routeRevision,
  consentGeneration: expected.consentGeneration,
  issuedAt,
  expiresAt: snapshotExpiresAt,
  choices: [choice],
};

const grant = {
  commandId,
  ...expected,
  categories: ['food_queue', 'parking', 'queue', 'restroom', 'traffic'],
  issuedAt,
  expiresAt: grantExpiresAt,
};

function csrfResponse(): Response {
  return Response.json({ token: csrfToken });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime('2026-09-19T10:00:30Z');
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('private browser signal choice clients', () => {
  it('reads 128 escaped 80-code-point Unicode labels within the dedicated 256 KiB bound', async () => {
    const choices = Array.from({ length: 128 }, (_, index) => ({
      anchorId: `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      displayLabel: '🛣'.repeat(80),
      categories: ['queue'],
    }));
    const response = { ...snapshot, choices };
    const escaped = JSON.stringify(response).replaceAll('🛣', '\\ud83d\\udee3');
    expect(new TextEncoder().encode(escaped).byteLength).toBeGreaterThan(64 * 1024);
    expect(new TextEncoder().encode(escaped).byteLength).toBeLessThanOrEqual(256 * 1024);
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(escaped, { headers: { 'Content-Type': 'application/json' } }),
      );
    vi.stubGlobal('fetch', fetcher);

    const result = await readBrowserLiveSignalChoices(accountId, journeyId);
    expect(result.choices).toHaveLength(128);
    expect(Array.from(result.choices[0]!.displayLabel)).toHaveLength(80);
    expect(result.routeRevision).toBe('9223372036854775807');
    expect(result.issuedAt).toBe(issuedAt);
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0]).toEqual([
      `/api/v1/journeys/${journeyId}/signal-choices`,
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: { 'X-Routiqo-Account': accountId },
        signal: expect.any(AbortSignal),
      }),
    ]);
  });

  it('keeps all other LIVE responses at 64 KiB', async () => {
    const oversized = `${JSON.stringify(snapshot)}${' '.repeat(64 * 1024)}`;
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          new Response(oversized, { headers: { 'Content-Type': 'application/json' } }),
        ),
    );
    await expect(readBrowserLiveSignalChoices(accountId, journeyId)).resolves.toEqual(snapshot);

    const overChoiceLimit = `${JSON.stringify(snapshot)}${' '.repeat(256 * 1024)}`;
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          new Response(overChoiceLimit, { headers: { 'Content-Type': 'application/json' } }),
        ),
    );
    await expect(readBrowserLiveSignalChoices(accountId, journeyId)).rejects.toMatchObject({
      kind: 'unavailable',
    });
  });

  it.each([
    ['empty choices', { ...snapshot, choices: [] }],
    ['too many choices', { ...snapshot, choices: Array.from({ length: 129 }, () => choice) }],
    ['duplicate choices', { ...snapshot, choices: [choice, choice] }],
    [
      'unsorted choices',
      {
        ...snapshot,
        choices: [{ ...choice, anchorId: '00000000-0000-4000-8000-000000000006' }, choice],
      },
    ],
    [
      'unsorted categories',
      { ...snapshot, choices: [{ ...choice, categories: ['traffic', 'queue'] }] },
    ],
    [
      'duplicate categories',
      { ...snapshot, choices: [{ ...choice, categories: ['queue', 'queue'] }] },
    ],
    ['unknown category', { ...snapshot, choices: [{ ...choice, categories: ['safety'] }] }],
    ['leading space label', { ...snapshot, choices: [{ ...choice, displayLabel: ' Private' }] }],
    ['oversized label', { ...snapshot, choices: [{ ...choice, displayLabel: '🛣'.repeat(81) }] }],
    ['format label', { ...snapshot, choices: [{ ...choice, displayLabel: 'Private\u200Barea' }] }],
    [
      'separator label',
      { ...snapshot, choices: [{ ...choice, displayLabel: 'Private\u00A0area' }] },
    ],
    [
      'terminal newline label',
      { ...snapshot, choices: [{ ...choice, displayLabel: 'Private\n' }] },
    ],
    [
      'terminal carriage return label',
      { ...snapshot, choices: [{ ...choice, displayLabel: 'Private\r' }] },
    ],
    [
      'terminal line separator label',
      { ...snapshot, choices: [{ ...choice, displayLabel: 'Private\u2028' }] },
    ],
    [
      'terminal paragraph separator label',
      { ...snapshot, choices: [{ ...choice, displayLabel: 'Private\u2029' }] },
    ],
    ['future issuance', { ...snapshot, issuedAt: '2026-09-19T10:00:30.000000001Z' }],
    ['expired snapshot', { ...snapshot, expiresAt: '2026-09-19T10:00:30Z' }],
    ['overlong lifetime', { ...snapshot, expiresAt: '2026-09-20T10:00:00.123456790Z' }],
    ['noncanonical revision', { ...snapshot, routeRevision: '01' }],
    ['unknown field', { ...snapshot, coordinates: [80, 13] }],
  ] as const)('rejects malformed choice snapshot: %s', async (_label, response) => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(Response.json(response)));
    await expect(readBrowserLiveSignalChoices(accountId, journeyId)).rejects.toEqual(
      new BrowserLiveError('unavailable'),
    );
  });

  it('snapshots the exact expected tuple before CSRF and matches the returned grant', async () => {
    const csrf = deferred<Response>();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockReturnValueOnce(csrf.promise)
      .mockResolvedValueOnce(Response.json(grant));
    vi.stubGlobal('fetch', fetcher);
    const input = { ...expected };
    const operation = issueBrowserLiveExpectedSignalCommand(accountId, journeyId, input);
    input.anchorId = '00000000-0000-4000-8000-000000000099';
    input.routeRevision = '0';
    csrf.resolve(csrfResponse());

    await expect(operation).resolves.toEqual(grant);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      `/api/v1/journeys/${journeyId}/signal-commands/expected-context`,
    );
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body as string)).toEqual(expected);
    expect(fetcher.mock.calls.some(([url]) => String(url).endsWith('/signal-commands'))).toBe(
      false,
    );
  });

  it.each([
    ['anchor', { ...grant, anchorId: '00000000-0000-4000-8000-000000000099' }],
    ['context', { ...grant, contextId: '00000000-0000-4000-8000-000000000099' }],
    ['route revision', { ...grant, routeRevision: '1' }],
    ['consent generation', { ...grant, consentGeneration: '1' }],
    ['future grant', { ...grant, issuedAt: '2026-09-19T10:00:30.000000001Z' }],
    ['expired grant', { ...grant, expiresAt: '2026-09-19T10:00:30Z' }],
  ] as const)(
    'rejects an expected issuance %s mismatch without retry',
    async (_label, response) => {
      const fetcher = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(csrfResponse())
        .mockResolvedValueOnce(Response.json(response));
      vi.stubGlobal('fetch', fetcher);
      await expect(
        issueBrowserLiveExpectedSignalCommand(accountId, journeyId, expected),
      ).rejects.toMatchObject({ kind: 'unavailable' });
      expect(fetcher).toHaveBeenCalledTimes(2);
    },
  );

  it('rejects a grant that expires in transit without issuing again', async () => {
    const response = deferred<Response>();
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(csrfResponse())
      .mockReturnValueOnce(response.promise);
    vi.stubGlobal('fetch', fetcher);
    const operation = issueBrowserLiveExpectedSignalCommand(accountId, journeyId, expected);
    await vi.advanceTimersByTimeAsync(5_001);
    response.resolve(Response.json({ ...grant, expiresAt: '2026-09-19T10:00:35Z' }));
    await expect(operation).rejects.toMatchObject({ kind: 'unavailable' });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('rejects malformed expected input before fetching', () => {
    const fetcher = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetcher);
    for (const input of [
      { ...expected, contextId: null },
      { ...expected, routeRevision: '9223372036854775808' },
      { ...expected, consentGeneration: '0\n' },
      { ...expected, extra: true },
    ])
      expect(() => issueBrowserLiveExpectedSignalCommand(accountId, journeyId, input)).toThrow(
        BrowserLiveError,
      );
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('preserves cancellation during CSRF and never issues or retries afterward', async () => {
    const csrf = deferred<Response>();
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(csrf.promise);
    vi.stubGlobal('fetch', fetcher);
    const controller = new AbortController();
    const operation = issueBrowserLiveExpectedSignalCommand(
      accountId,
      journeyId,
      expected,
      controller.signal,
    );
    controller.abort();
    await expect(operation).rejects.toMatchObject({ name: 'AbortError' });
    csrf.resolve(csrfResponse());
    await Promise.resolve();
    await Promise.resolve();
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it('uses one 12-second deadline and never retries an uncooperative expected issuance', async () => {
    const fetcher = vi.fn<typeof fetch>().mockReturnValue(new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetcher);
    const operation = issueBrowserLiveExpectedSignalCommand(accountId, journeyId, expected);
    const rejection = expect(operation).rejects.toMatchObject({ kind: 'unavailable' });
    await vi.advanceTimersByTimeAsync(12_000);
    await rejection;
    expect(fetcher).toHaveBeenCalledOnce();
  });
});
