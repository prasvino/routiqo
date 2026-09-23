// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { browserAccount } from '../lib/browser-auth';
import {
  readBrowserLivePublicIntents,
  stopBrowserLiveSignalPublicIntent,
} from '../lib/browser-live';
import { PublicIntentRecoveryPanel } from './public-intent-recovery-panel';

vi.mock('../lib/browser-auth', () => ({ browserAccount: vi.fn() }));
vi.mock('../lib/browser-live', async (load) => ({
  ...(await load<typeof import('../lib/browser-live')>()),
  readBrowserLivePublicIntents: vi.fn(),
  stopBrowserLiveSignalPublicIntent: vi.fn(),
}));

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const commandId = '00000000-0000-4000-8000-000000000003';
const item = {
  journeyId,
  commandId,
  status: 'shared' as const,
  sharedAt: new Date().toISOString(),
};
const onReady = vi.fn();
const props = { accountId, identityConfirmed: true, online: true, onReady };

beforeEach(() => {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
  vi.mocked(browserAccount).mockResolvedValue({ accountId });
  vi.mocked(readBrowserLivePublicIntents).mockResolvedValue({ intents: [item], nextCursor: null });
  vi.mocked(stopBrowserLiveSignalPublicIntent).mockResolvedValue({ commandId, status: 'stopped' });
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

describe('owner public intent recovery', () => {
  it('loads all pages before enabling share and stops after journey completion', async () => {
    vi.mocked(readBrowserLivePublicIntents)
      .mockResolvedValueOnce({ intents: [], nextCursor: 'abc_123' })
      .mockResolvedValueOnce({ intents: [item], nextCursor: null });
    render(<PublicIntentRecoveryPanel {...props} />);
    await screen.findByRole('button', { name: 'Stop request 1' });
    expect(readBrowserLivePublicIntents).toHaveBeenNthCalledWith(
      1,
      accountId,
      undefined,
      expect.any(AbortSignal),
    );
    expect(readBrowserLivePublicIntents).toHaveBeenNthCalledWith(
      2,
      accountId,
      'abc_123',
      expect.any(AbortSignal),
    );
    expect(onReady).toHaveBeenLastCalledWith(true);
    fireEvent.click(screen.getByRole('button', { name: 'Stop request 1' }));
    await screen.findByText(/No active public consideration requests/);
    expect(stopBrowserLiveSignalPublicIntent).toHaveBeenCalledExactlyOnceWith(
      accountId,
      journeyId,
      commandId,
      expect.any(AbortSignal),
    );
  });
  it('fails closed if the owner list cannot be fully read', async () => {
    vi.mocked(readBrowserLivePublicIntents).mockRejectedValue(new Error('private detail'));
    render(<PublicIntentRecoveryPanel {...props} />);
    await screen.findByText(/Requests could not be checked/);
    expect(onReady).toHaveBeenLastCalledWith(false);
    expect(screen.queryByText('private detail')).toBeNull();
  });
  it('recovers a Stop handle after remount and keeps it through a failed refresh', async () => {
    const first = render(<PublicIntentRecoveryPanel {...props} />);
    await screen.findByRole('button', { name: 'Stop request 1' });
    first.unmount();
    const second = render(<PublicIntentRecoveryPanel {...props} />);
    await screen.findByRole('button', { name: 'Stop request 1' });
    vi.mocked(readBrowserLivePublicIntents).mockRejectedValueOnce(new Error('offline'));
    fireEvent.click(screen.getByRole('button', { name: 'Check requests' }));
    await screen.findByText(/Previously checked Stop handles remain/);
    expect(screen.getByRole('button', { name: 'Stop request 1' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Stop request 1' }));
    await screen.findByText(/No active public consideration requests/);
    second.unmount();
    expect(stopBrowserLiveSignalPublicIntent).toHaveBeenCalledExactlyOnceWith(
      accountId,
      journeyId,
      commandId,
      expect.any(AbortSignal),
    );
  });
  it('does not list another account and checks a newly confirmed account', async () => {
    const view = render(<PublicIntentRecoveryPanel {...props} identityConfirmed={false} />);
    expect(readBrowserLivePublicIntents).not.toHaveBeenCalled();
    view.rerender(<PublicIntentRecoveryPanel {...props} />);
    await waitFor(() => expect(readBrowserLivePublicIntents).toHaveBeenCalledTimes(1));
    expect(onReady).toHaveBeenLastCalledWith(true);
  });
});
