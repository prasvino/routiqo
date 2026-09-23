// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ProviderLiveAlerts } from './provider-live-alerts';
import { readBrowserProviderAlerts, ProviderAlertsError } from '../lib/browser-provider-alerts';

vi.mock('../lib/browser-provider-alerts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/browser-provider-alerts')>()),
  readBrowserProviderAlerts: vi.fn(),
}));
const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const alert = {
  id: '12345678',
  event: 'Heavy rain',
  area: 'Chennai',
  severity: 'Severe' as const,
  issuer: 'official@example.gov.in',
  sourceUrl: 'https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile?identifier=12345678',
  issuedAt: new Date(Date.now() - 60_000).toISOString(),
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
};
const response = (alerts: (typeof alert)[]) => ({
  region: 'Chennai district area',
  scope: 'District-wide alerts; not road conditions',
  source: 'NDMA SACHET',
  alerts,
});

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

it('shows an attributed district alert, then marks the in-memory copy stale offline', async () => {
  vi.mocked(readBrowserProviderAlerts).mockResolvedValue(response([alert]));
  const view = render(<ProviderLiveAlerts accountId={accountId} journeyId={journeyId} online />);
  expect(await screen.findByText('Heavy rain')).toBeTruthy();
  expect(screen.getByRole('link', { name: 'View official alert' }).getAttribute('href')).toBe(
    alert.sourceUrl,
  );
  expect(screen.getByText(/not road conditions/)).toBeTruthy();
  view.rerender(<ProviderLiveAlerts accountId={accountId} journeyId={journeyId} online={false} />);
  expect(await screen.findByText(/Last checked copy/)).toBeTruthy();
  expect(readBrowserProviderAlerts).toHaveBeenCalledTimes(1);
});

it('shows an honest empty state and hides a server-disabled pilot', async () => {
  vi.mocked(readBrowserProviderAlerts).mockResolvedValueOnce(response([]));
  const view = render(<ProviderLiveAlerts accountId={accountId} journeyId={journeyId} online />);
  expect(await screen.findByText(/This does not mean roads are safe/)).toBeTruthy();
  view.unmount();
  vi.mocked(readBrowserProviderAlerts).mockRejectedValueOnce(new ProviderAlertsError(404));
  render(<ProviderLiveAlerts accountId={accountId} journeyId={journeyId} online />);
  await waitFor(() =>
    expect(screen.queryByRole('heading', { name: 'Official alerts' })).toBeNull(),
  );
});

it('aborts the request when the journey panel unmounts', async () => {
  let observed: AbortSignal | undefined;
  vi.mocked(readBrowserProviderAlerts).mockImplementation(async (_account, _journey, signal) => {
    observed = signal;
    return new Promise(() => undefined);
  });
  const view = render(<ProviderLiveAlerts accountId={accountId} journeyId={journeyId} online />);
  await waitFor(() => expect(observed).toBeDefined());
  view.unmount();
  expect(observed?.aborted).toBe(true);
});
