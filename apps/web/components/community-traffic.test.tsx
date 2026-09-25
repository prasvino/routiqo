// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { browserAccount } from '../lib/browser-auth';
import {
  readBrowserCommunityShares,
  readBrowserCommunityTraffic,
  reportBrowserCommunityTraffic,
  shareBrowserCommunityTraffic,
  stopBrowserCommunityTraffic,
  type CommunityTrafficMoment,
  type CommunityTrafficSnapshot,
} from '../lib/browser-community-traffic';
import { CommunityShareControl } from './community-share-control';
import { CommunityShareRecoveryPanel } from './community-share-recovery-panel';
import { CommunityTrafficPanel } from './community-traffic-panel';
import { BrowserLiveError } from '../lib/browser-live-private';

vi.mock('../lib/browser-auth', () => ({ browserAccount: vi.fn() }));
vi.mock('../lib/browser-community-traffic', () => ({
  readBrowserCommunityShares: vi.fn(),
  readBrowserCommunityTraffic: vi.fn(),
  reportBrowserCommunityTraffic: vi.fn(),
  shareBrowserCommunityTraffic: vi.fn(),
  stopBrowserCommunityTraffic: vi.fn(),
}));

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const commandId = '00000000-0000-4000-8000-000000000003';
const candidateId = '00000000-0000-4000-8000-000000000004';
const ref = '00000000-0000-4000-8000-000000000005';
const future = () => new Date(Date.now() + 300_000).toISOString();
function snapshot(rows: CommunityTrafficMoment[]): CommunityTrafficSnapshot {
  return {
    schemaVersion: 3,
    serverTime: new Date().toISOString(),
    moments: rows.map((row) => ({
      ...row,
      deadlineMonotonic: performance.now() + Date.parse(row.expiresAt) - Date.now(),
    })),
  };
}

beforeEach(() => {
  vi.mocked(browserAccount).mockResolvedValue({ accountId });
  vi.mocked(readBrowserCommunityShares).mockResolvedValue([]);
  vi.mocked(readBrowserCommunityTraffic).mockResolvedValue(snapshot([]));
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
  vi.useRealTimers();
});

const receipt = {
  status: 'received' as const,
  receivedAt: '2026-09-25T10:00:00Z',
  receiptExpiresAt: '2026-10-02T10:00:00Z',
};

it('requires fresh per-report consent and safe interaction, then states candidate acceptance without publication', async () => {
  vi.mocked(shareBrowserCommunityTraffic).mockResolvedValue({
    candidateId,
    commandId,
    status: 'accepted_for_consideration',
    acceptedAt: new Date().toISOString(),
    windowEndsAt: future(),
  });
  render(
    <CommunityShareControl
      accountId={accountId}
      journeyId={journeyId}
      commandId={commandId}
      receiptExpiresAt={future()}
      online
      identityConfirmed
      activeJourney
    />,
  );
  const share = screen.getByRole('button', {
    name: 'Share for consideration',
  }) as HTMLButtonElement;
  expect(share.disabled).toBe(true);
  fireEvent.click(screen.getByLabelText(/I choose community traffic sharing/));
  expect(share.disabled).toBe(true);
  fireEvent.click(screen.getByLabelText(/stopped or a passenger/));
  fireEvent.click(share);
  await waitFor(() => expect(shareBrowserCommunityTraffic).toHaveBeenCalledTimes(1));
  expect(vi.mocked(shareBrowserCommunityTraffic).mock.calls[0]?.[3]).toMatch(/^[a-f0-9-]{36}$/);
  expect(
    await screen.findByText(
      /Accepted for consideration. This does not mean a summary was published/,
    ),
  ).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Stop future sharing' })).toBeTruthy();
});

it('recovers V3 handles on a fresh mount and stops future sharing across journeys', async () => {
  vi.mocked(readBrowserCommunityShares).mockResolvedValue([
    {
      candidateId,
      journeyId,
      commandId,
      requestId: ref,
      status: 'accepted_for_consideration',
      acceptedAt: new Date().toISOString(),
      windowEndsAt: future(),
    },
  ]);
  vi.mocked(stopBrowserCommunityTraffic).mockResolvedValue({
    commandId,
    status: 'stopped_for_future_sharing',
  });
  render(<CommunityShareRecoveryPanel accountId={accountId} online identityConfirmed />);
  expect(await screen.findByText('Accepted for consideration', { exact: false })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Stop future sharing' }));
  await waitFor(() =>
    expect(stopBrowserCommunityTraffic).toHaveBeenCalledWith(
      accountId,
      journeyId,
      commandId,
      expect.any(AbortSignal),
    ),
  );
  expect(
    await screen.findByText(/A summary already being prepared may still include/),
  ).toBeTruthy();
});

it('preserves the exact Share request after a lost response, then recovers it after remount', async () => {
  vi.mocked(shareBrowserCommunityTraffic).mockRejectedValueOnce(new Error('response lost'));
  const view = render(
    <CommunityShareControl
      accountId={accountId}
      journeyId={journeyId}
      commandId={commandId}
      receiptExpiresAt={future()}
      online
      identityConfirmed
      activeJourney
    />,
  );
  fireEvent.click(screen.getByLabelText(/I choose community traffic sharing/));
  fireEvent.click(screen.getByLabelText(/stopped or a passenger/));
  fireEvent.click(screen.getByRole('button', { name: 'Share for consideration' }));
  expect(await screen.findByText(/Share result is uncertain/)).toBeTruthy();
  const firstRequest = vi.mocked(shareBrowserCommunityTraffic).mock.calls[0]?.[3];
  fireEvent.click(screen.getByLabelText(/I choose community traffic sharing/));
  fireEvent.click(screen.getByLabelText(/stopped or a passenger/));
  vi.mocked(shareBrowserCommunityTraffic).mockResolvedValueOnce({
    candidateId,
    commandId,
    status: 'accepted_for_consideration',
    acceptedAt: new Date().toISOString(),
    windowEndsAt: future(),
  });
  fireEvent.click(screen.getByRole('button', { name: 'Retry this exact request' }));
  await waitFor(() => expect(shareBrowserCommunityTraffic).toHaveBeenCalledTimes(2));
  expect(vi.mocked(shareBrowserCommunityTraffic).mock.calls[1]?.[3]).toBe(firstRequest);
  view.unmount();
  vi.mocked(readBrowserCommunityShares).mockResolvedValueOnce([
    {
      candidateId,
      journeyId,
      commandId,
      requestId: firstRequest!,
      status: 'accepted_for_consideration',
      acceptedAt: new Date().toISOString(),
      windowEndsAt: future(),
    },
  ]);
  render(<CommunityShareRecoveryPanel accountId={accountId} online identityConfirmed />);
  expect(await screen.findByText(/Accepted for consideration/)).toBeTruthy();
});

it('shows honest empty/offline states and sends a report only for a visible canonical row', async () => {
  const row = {
    ref,
    areaLabel: 'Reviewed road area',
    trafficValue: 'traffic_slow' as const,
    observationPeriod: '10:00–10:05 UTC',
    expiresAt: future(),
    source: 'community' as const,
    schemaVersion: 3 as const,
  };
  vi.mocked(readBrowserCommunityTraffic)
    .mockResolvedValueOnce(snapshot([]))
    .mockResolvedValueOnce(snapshot([row]));
  vi.mocked(reportBrowserCommunityTraffic).mockResolvedValue(receipt);
  const view = render(<CommunityTrafficPanel accountId={accountId} journeyId={journeyId} online />);
  expect(await screen.findByText(/No recent community update/)).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Check traffic' }));
  expect(await screen.findByText('Slow traffic reported')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Report this summary'), { target: { value: 'UNSAFE' } });
  fireEvent.click(screen.getByRole('button', { name: 'Send report' }));
  await waitFor(() =>
    expect(reportBrowserCommunityTraffic).toHaveBeenCalledWith(
      accountId,
      journeyId,
      ref,
      'UNSAFE',
      expect.any(String),
      expect.any(AbortSignal),
    ),
  );
  view.rerender(
    <CommunityTrafficPanel accountId={accountId} journeyId={journeyId} online={false} />,
  );
  expect(await screen.findByText(/No saved community summaries are shown/)).toBeTruthy();
  expect(screen.queryByText('Slow traffic reported')).toBeNull();
});

it('retries an uncertain report with its exact reason and request id', async () => {
  const row = {
    ref,
    areaLabel: 'Reviewed road area',
    trafficValue: 'traffic_slow' as const,
    observationPeriod: '10:00–10:05 UTC',
    expiresAt: future(),
    source: 'community' as const,
    schemaVersion: 3 as const,
  };
  vi.mocked(readBrowserCommunityTraffic).mockResolvedValue(snapshot([row]));
  vi.mocked(reportBrowserCommunityTraffic)
    .mockRejectedValueOnce(new Error('response lost'))
    .mockResolvedValueOnce(receipt);
  render(<CommunityTrafficPanel accountId={accountId} journeyId={journeyId} online />);
  expect(await screen.findByText('Slow traffic reported')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Report this summary'), { target: { value: 'SPAM' } });
  fireEvent.click(screen.getByRole('button', { name: 'Send report' }));
  expect(await screen.findByText(/Report not confirmed/)).toBeTruthy();
  expect((screen.getByLabelText('Report this summary') as HTMLSelectElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'Send report' }));
  await waitFor(() => expect(reportBrowserCommunityTraffic).toHaveBeenCalledTimes(2));
  expect(vi.mocked(reportBrowserCommunityTraffic).mock.calls[1]?.slice(0, 5)).toEqual(
    vi.mocked(reportBrowserCommunityTraffic).mock.calls[0]?.slice(0, 5),
  );
});

it('clears community rows when contribution authority is withdrawn', async () => {
  const row = {
    ref,
    areaLabel: 'Reviewed road area',
    trafficValue: 'traffic_slow' as const,
    observationPeriod: '10:00–10:05 UTC',
    expiresAt: future(),
    source: 'community' as const,
    schemaVersion: 3 as const,
  };
  vi.mocked(readBrowserCommunityTraffic).mockResolvedValue(snapshot([row]));
  const view = render(
    <CommunityTrafficPanel accountId={accountId} journeyId={journeyId} online available />,
  );
  expect(await screen.findByText('Slow traffic reported')).toBeTruthy();
  view.rerender(
    <CommunityTrafficPanel accountId={accountId} journeyId={journeyId} online available={false} />,
  );
  expect(await screen.findByText(/Community traffic is paused/)).toBeTruthy();
  expect(screen.queryByText('Slow traffic reported')).toBeNull();
});

it('hides a server-timed row at expiry with a device clock five minutes slow', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-09-23T11:55:00.000Z'));
  const row = {
    ref,
    areaLabel: 'Reviewed road area',
    trafficValue: 'traffic_slow' as const,
    observationPeriod: '10:00–10:05 UTC',
    expiresAt: '2026-09-23T12:00:00.700000000Z',
    source: 'community' as const,
    schemaVersion: 3 as const,
  };
  vi.mocked(readBrowserCommunityTraffic).mockResolvedValue({
    schemaVersion: 3,
    serverTime: '2026-09-23T12:00:00.000000000Z',
    moments: [{ ...row, deadlineMonotonic: performance.now() + 700 }],
  });
  render(<CommunityTrafficPanel accountId={accountId} journeyId={journeyId} online />);
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
  expect(screen.getByText('Slow traffic reported')).toBeTruthy();
  await act(async () => {
    vi.advanceTimersByTime(701);
  });
  expect(screen.queryByText('Slow traffic reported')).toBeNull();
  expect(screen.getByText(/Previous community summaries expired/)).toBeTruthy();
});

it('explains the rolling report limit instead of implying the report may have been lost', async () => {
  const row = {
    ref,
    areaLabel: 'Reviewed road area',
    trafficValue: 'traffic_slow' as const,
    observationPeriod: '10:00–10:05 UTC',
    expiresAt: future(),
    source: 'community' as const,
    schemaVersion: 3 as const,
  };
  vi.mocked(readBrowserCommunityTraffic).mockResolvedValue(snapshot([row]));
  vi.mocked(reportBrowserCommunityTraffic).mockRejectedValueOnce(
    new BrowserLiveError('rate_limited'),
  );
  render(<CommunityTrafficPanel accountId={accountId} journeyId={journeyId} online />);
  expect(await screen.findByText('Slow traffic reported')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Report this summary'), { target: { value: 'SPAM' } });
  fireEvent.click(screen.getByRole('button', { name: 'Send report' }));
  expect(await screen.findByText(/up to 10 new reports in 24 hours/)).toBeTruthy();
});
