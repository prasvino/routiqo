import { afterEach, expect, it, vi } from 'vitest';
import { readBrowserProviderAlerts, ProviderAlertsError } from './browser-provider-alerts';

const account = '00000000-0000-4000-8000-000000000001';
const journey = '00000000-0000-4000-8000-000000000002';
const alert = {
  id: '12345678',
  event: 'Heavy rain',
  area: 'Chennai',
  severity: 'Severe',
  issuer: 'official@example.gov.in',
  sourceUrl: 'https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile?identifier=12345678',
  issuedAt: '2026-09-23T08:00:00Z',
  expiresAt: '2026-09-23T18:00:00Z',
};
const payload = (alerts: unknown[]) => ({
  region: 'Chennai district area',
  scope: 'District-wide alerts; not road conditions',
  source: 'NDMA SACHET',
  alerts,
});

afterEach(() => vi.unstubAllGlobals());

it('reads bounded official alerts with account context and no browser cache', async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json(payload([alert])));
  vi.stubGlobal('fetch', fetcher);
  expect((await readBrowserProviderAlerts(account, journey)).alerts).toHaveLength(1);
  const [url, options] = fetcher.mock.calls[0]!;
  expect(url).toBe(`/api/v1/journeys/${journey}/provider-alerts`);
  expect(options?.method).toBe('GET');
  expect(options?.cache).toBe('no-store');
  expect(new Headers(options?.headers).get('X-Routiqo-Account')).toBe(account);
});

it('rejects unexpected source links, oversized responses and unavailable API status', async () => {
  const fetcher = vi.fn<typeof fetch>();
  vi.stubGlobal('fetch', fetcher);
  fetcher.mockResolvedValueOnce(
    Response.json(payload([{ ...alert, sourceUrl: 'https://attacker.invalid' }])),
  );
  await expect(readBrowserProviderAlerts(account, journey)).rejects.toThrow('invalid');
  fetcher.mockResolvedValueOnce(Response.json(payload([{ ...alert, event: 'x'.repeat(33_000) }])));
  await expect(readBrowserProviderAlerts(account, journey)).rejects.toThrow('too large');
  fetcher.mockResolvedValueOnce(new Response(null, { status: 503 }));
  await expect(readBrowserProviderAlerts(account, journey)).rejects.toBeInstanceOf(
    ProviderAlertsError,
  );
});

it('cancels a stalled response read when the journey is left', async () => {
  const cancel = vi.fn().mockResolvedValue(undefined);
  const body = new ReadableStream<Uint8Array>({ cancel });
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>().mockResolvedValue(
      new Response(body, {
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
  const controller = new AbortController();
  const result = readBrowserProviderAlerts(account, journey, controller.signal);
  controller.abort();
  await expect(result).rejects.toMatchObject({ name: 'AbortError' });
});
