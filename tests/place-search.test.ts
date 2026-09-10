import { afterEach, expect, it, vi } from 'vitest';
import { readPlaceQuery, readPlaceResults } from '../packages/shared/src/place-search';
import { searchBrowserPlaces } from '../apps/web/lib/browser-routing';
import { proxyBrowserPlaceSearch } from '../apps/web/lib/auth-proxy';
const account = '00000000-0000-4000-8000-000000000001';
const result = {
  provider: 'mapbox',
  attribution: 'Synthetic attribution',
  places: [{ id: 'synthetic-place', label: 'Synthetic town', coordinate: [80, 13] }],
};
afterEach(() => vi.unstubAllGlobals());
it('validates Unicode searches and rejects oversized, control and provider-forbidden input', () => {
  expect(readPlaceQuery('  Chennai  ')).toBe('Chennai');
  expect(readPlaceQuery('சென்னை')).toBe('சென்னை');
  for (const invalid of [
    'ab',
    'town;street',
    'town\nstreet',
    'word '.repeat(21),
    'x'.repeat(257),
    '...',
  ])
    expect(() => readPlaceQuery(invalid)).toThrow();
});
it('validates every selected place and keeps attribution while stripping unused metadata', () => {
  expect(readPlaceResults({ ...result, private: 'discard' })).toEqual(result);
  for (const places of [
    [result.places[0], result.places[0]],
    [{ ...result.places[0], coordinate: [80, 91] }],
    [{ ...result.places[0], label: '' }],
  ])
    expect(() => readPlaceResults({ ...result, places })).toThrow();
});
it('submits a private account-bound search and bounds the response', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token: 'synthetic-csrf-token-for-place-tests' }))
    .mockResolvedValueOnce(Response.json(result));
  vi.stubGlobal('fetch', fetcher);
  expect(await searchBrowserPlaces(account, ' Chennai ')).toEqual(result);
  expect(fetcher).toHaveBeenLastCalledWith(
    '/api/v1/routes/places',
    expect.objectContaining({
      body: JSON.stringify({ query: 'Chennai' }),
      headers: expect.objectContaining({ 'X-Routiqo-Account': account }),
    }),
  );
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'synthetic-csrf-token-for-place-tests' }))
      .mockResolvedValueOnce(new Response('x'.repeat(262145))),
  );
  await expect(searchBrowserPlaces(account, 'Chennai')).rejects.toMatchObject({ status: 503 });
});
it('proxies only the fixed place-search target and refuses URL query leakage', async () => {
  const config = {
    clientId: 'test.apps.googleusercontent.com',
    upstream: 'http://localhost:8080',
    origin: 'http://localhost:3000',
  };
  const fetcher = vi.fn(async () => Response.json(result));
  const request = () =>
    new Request('http://localhost:3000/api/v1/routes/places', {
      method: 'POST',
      headers: { Origin: config.origin, 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'Chennai' }),
    });
  expect((await proxyBrowserPlaceSearch(request(), config, fetcher)).status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    'http://localhost:8080/api/v1/routes/places',
    expect.objectContaining({ method: 'POST', cache: 'no-store' }),
  );
  expect(
    (
      await proxyBrowserPlaceSearch(
        new Request('http://localhost:3000/api/v1/routes/places?q=private', { method: 'POST' }),
        config,
        fetcher,
      )
    ).status,
  ).toBe(400);
  expect(fetcher).toHaveBeenCalledOnce();
});
