import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  readBrowserLivePublicIntents,
  shareBrowserLiveSignalPublicIntent,
  stopBrowserLiveSignalPublicIntent,
} from '../apps/web/lib/browser-live';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const commandId = '00000000-0000-4000-8000-000000000003';
const requestId = '00000000-0000-4000-8000-000000000004';
const sharedAt = '2026-09-23T12:00:00.000000000Z';
const csrfToken = 'C'.repeat(32);
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('private public-intent browser client', () => {
  it('sends exact deliberate share body and exact Stop, without automatic replay', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ token: csrfToken }))
      .mockResolvedValueOnce(Response.json({ commandId, status: 'shared', sharedAt }))
      .mockResolvedValueOnce(Response.json({ token: csrfToken }))
      .mockResolvedValueOnce(Response.json({ commandId, status: 'stopped' }));
    vi.stubGlobal('fetch', fetcher);
    await expect(
      shareBrowserLiveSignalPublicIntent(accountId, journeyId, commandId, {
        requestId,
        purpose: 'public-live-moment-v1',
      }),
    ).resolves.toEqual({ commandId, status: 'shared', sharedAt });
    await expect(
      stopBrowserLiveSignalPublicIntent(accountId, journeyId, commandId),
    ).resolves.toEqual({ commandId, status: 'stopped' });
    expect(fetcher.mock.calls[1]?.[0]).toBe(
      `/api/v1/journeys/${journeyId}/signals/${commandId}/public-intent`,
    );
    expect(fetcher.mock.calls[1]?.[1]).toEqual(
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        body: JSON.stringify({ requestId, purpose: 'public-live-moment-v1' }),
      }),
    );
    expect(fetcher.mock.calls[3]?.[0]).toBe(
      `/api/v1/journeys/${journeyId}/signals/${commandId}/public-intent/stop`,
    );
    expect(fetcher.mock.calls[3]?.[1]).toEqual(expect.objectContaining({ body: '{}' }));
    expect(fetcher).toHaveBeenCalledTimes(4);
  });
  it('rejects extra request fields before any network call', () => {
    const fetcher = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', fetcher);
    expect(() =>
      shareBrowserLiveSignalPublicIntent(accountId, journeyId, commandId, {
        requestId,
        purpose: 'public-live-moment-v1',
        publish: true,
      }),
    ).toThrow();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it('reads only bounded owner handles and rejects identity or location fields', async () => {
    const valid = {
      intents: [{ journeyId, commandId, status: 'shared', sharedAt }],
      nextCursor: 'abc_123',
    };
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json(valid));
    vi.stubGlobal('fetch', fetcher);
    await expect(readBrowserLivePublicIntents(accountId)).resolves.toEqual(valid);
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/v1/public-intents');
    fetcher.mockResolvedValueOnce(
      Response.json({ intents: [{ ...valid.intents[0], anchorId: requestId }], nextCursor: null }),
    );
    await expect(readBrowserLivePublicIntents(accountId)).rejects.toMatchObject({
      kind: 'unavailable',
    });
    expect(() => readBrowserLivePublicIntents(accountId, 'bad/cursor')).toThrow();
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
