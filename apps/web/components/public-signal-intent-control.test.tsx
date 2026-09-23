// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { browserAccount } from '../lib/browser-auth';
import {
  shareBrowserLiveSignalPublicIntent,
  stopBrowserLiveSignalPublicIntent,
} from '../lib/browser-live';
import { PublicSignalIntentControl } from './public-signal-intent-control';

vi.mock('../lib/browser-auth', () => ({ browserAccount: vi.fn() }));
vi.mock('../lib/browser-live', async (load) => ({
  ...(await load<typeof import('../lib/browser-live')>()),
  shareBrowserLiveSignalPublicIntent: vi.fn(),
  stopBrowserLiveSignalPublicIntent: vi.fn(),
}));

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const commandId = '00000000-0000-4000-8000-000000000003';
const otherAccount = '00000000-0000-4000-8000-000000000004';
const props = {
  accountId,
  journeyId,
  commandId,
  receiptExpiresAt: new Date(Date.now() + 600_000).toISOString(),
  eligible: true,
  identityConfirmed: true,
  online: true,
  shareEnabled: true,
  activeJourney: true,
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
  vi.mocked(browserAccount).mockResolvedValue({ accountId });
  vi.mocked(shareBrowserLiveSignalPublicIntent).mockResolvedValue({
    commandId,
    status: 'shared',
    sharedAt: new Date().toISOString(),
  });
  vi.mocked(stopBrowserLiveSignalPublicIntent).mockResolvedValue({ commandId, status: 'stopped' });
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

async function share() {
  fireEvent.click(screen.getByLabelText('I choose public consideration for this receipt only.'));
  fireEvent.click(screen.getByLabelText('I’m stopped or a passenger and can interact safely.'));
  fireEvent.click(screen.getByRole('button', { name: 'Request public consideration' }));
}

describe('per-receipt public intent control', () => {
  it('requires purpose acknowledgement and active unexpired private receipt; does not send on mount', async () => {
    const view = render(<PublicSignalIntentControl {...props} />);
    expect(shareBrowserLiveSignalPublicIntent).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Request public consideration' })).toHaveProperty(
      'disabled',
      true,
    );
    view.rerender(<PublicSignalIntentControl {...props} activeJourney={false} />);
    await share();
    expect(shareBrowserLiveSignalPublicIntent).not.toHaveBeenCalled();
    view.rerender(
      <PublicSignalIntentControl {...props} receiptExpiresAt={new Date(0).toISOString()} />,
    );
    expect(screen.getByRole('button', { name: 'Request public consideration' })).toHaveProperty(
      'disabled',
      true,
    );
  });
  it('makes one explicit request, then permits Stop after journey ends and sharing is disabled', async () => {
    const view = render(<PublicSignalIntentControl {...props} />);
    await share();
    await screen.findByText(/does not mean a Live Moment was published/);
    expect(shareBrowserLiveSignalPublicIntent).toHaveBeenCalledTimes(1);
    expect(vi.mocked(shareBrowserLiveSignalPublicIntent).mock.calls[0]?.slice(0, 3)).toEqual([
      accountId,
      journeyId,
      commandId,
    ]);
    expect(vi.mocked(shareBrowserLiveSignalPublicIntent).mock.calls[0]?.[3]).toEqual({
      requestId: expect.any(String),
      purpose: 'public-live-moment-v1',
    });
    view.rerender(
      <PublicSignalIntentControl {...props} activeJourney={false} shareEnabled={false} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Stop public consideration' }));
    await screen.findByText(/Public consideration stopped/);
    expect(stopBrowserLiveSignalPublicIntent).toHaveBeenCalledExactlyOnceWith(
      accountId,
      journeyId,
      commandId,
      expect.any(AbortSignal),
    );
  });
  it('treats an uncertain share result as potentially accepted and offers exact Stop', async () => {
    vi.mocked(shareBrowserLiveSignalPublicIntent).mockRejectedValueOnce(
      new Error('sensitive server detail'),
    );
    render(<PublicSignalIntentControl {...props} />);
    await share();
    await screen.findByText(/request result is uncertain/);
    expect(screen.queryByText('sensitive server detail')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Stop public consideration' }));
    await screen.findByText(/Public consideration stopped/);
    expect(shareBrowserLiveSignalPublicIntent).toHaveBeenCalledTimes(1);
  });
  it('refuses a changed account before sending and never retries automatically', async () => {
    vi.mocked(browserAccount).mockResolvedValue({ accountId: otherAccount });
    render(<PublicSignalIntentControl {...props} />);
    await share();
    await screen.findByText(/request result is uncertain/);
    expect(shareBrowserLiveSignalPublicIntent).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Stop public consideration' }));
    await waitFor(() => expect(browserAccount).toHaveBeenCalledTimes(2));
    expect(stopBrowserLiveSignalPublicIntent).not.toHaveBeenCalled();
  });
  it('aborts an in-flight request offline and ignores a late response', async () => {
    const pending = deferred<{ commandId: string; status: 'shared'; sharedAt: string }>();
    vi.mocked(shareBrowserLiveSignalPublicIntent).mockReturnValue(pending.promise);
    render(<PublicSignalIntentControl {...props} />);
    await share();
    await waitFor(() => expect(shareBrowserLiveSignalPublicIntent).toHaveBeenCalledTimes(1));
    const signal = vi.mocked(shareBrowserLiveSignalPublicIntent).mock.calls[0]?.[4];
    fireEvent(window, new Event('offline'));
    expect(signal?.aborted).toBe(true);
    await act(async () =>
      pending.resolve({ commandId, status: 'shared', sharedAt: new Date().toISOString() }),
    );
    expect(screen.getByText(/request result is uncertain/)).toBeTruthy();
  });
});
