import { afterEach, describe, expect, it, vi } from 'vitest';
import { sendBrowserJourney } from '../apps/web/lib/browser-journeys';
const account = '00000000-0000-4000-8000-000000000001';
const id = '00000000-0000-4000-8000-000000000002';
const command = { journeyId: id, action: 'start' as const, kind: 'trip' as const };
afterEach(() => vi.unstubAllGlobals());
function respond(response: Response) {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token: 'masked-csrf-token-for-testing' }))
    .mockResolvedValueOnce(response);
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}
describe('browser journey delivery', () => {
  it('binds the account partition and sends a stable retry identity', async () => {
    const fetcher = respond(
      Response.json({
        id,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
        credential: 'discard',
      }),
    );
    const delivered = await sendBrowserJourney(account, command);
    expect(delivered.outcome).toBe('success');
    expect(JSON.stringify(delivered)).not.toContain('credential');
    expect(fetcher.mock.calls[1]).toEqual([
      '/api/v1/journeys',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        redirect: 'error',
        body: JSON.stringify({ id, kind: 'trip' }),
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'masked-csrf-token-for-testing',
          'X-Routiqo-Account': account,
        },
      }),
    ]);
  });
  it('classifies retryable and blocked HTTP responses', async () => {
    for (const [status, outcome] of [
      [401, 'authentication'],
      [403, 'authentication'],
      [409, 'conflict'],
      [429, 'transient'],
      [503, 'transient'],
      [400, 'rejected'],
      [404, 'rejected'],
    ] as const) {
      respond(new Response(null, { status }));
      expect(await sendBrowserJourney(account, command)).toEqual({ outcome });
    }
  });
  it('retains work for malformed or unrelated successful results', async () => {
    for (const body of [
      {},
      {
        id: account,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
      },
    ]) {
      respond(Response.json(body));
      expect(await sendBrowserJourney(account, command)).toEqual({ outcome: 'transient' });
    }
    respond(
      Response.json({
        id,
        kind: 'trip',
        status: 'active',
        startedAt: '2026-09-08T12:00:00Z',
        completedAt: null,
      }),
    );
    expect(await sendBrowserJourney(account, { journeyId: id, action: 'complete' })).toEqual({
      outcome: 'transient',
    });
  });
});
