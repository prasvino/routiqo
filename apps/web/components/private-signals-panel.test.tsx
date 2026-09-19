// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { browserAccount } from '../lib/browser-auth';
import {
  acceptBrowserLiveSignalCommand,
  issueBrowserLiveExpectedSignalCommand,
  readBrowserLiveSignalChoices,
  stopBrowserLiveSignalCommand,
} from '../lib/browser-live';
import { LiveSignalRecoveryCoordinator } from '../lib/live-signal-recovery';
import type {
  RoutePlannerContributionAuthority,
  RoutePlannerContributionSource,
} from './route-planner';
import { PrivateSignalsPanel } from './private-signals-panel';

vi.mock('../lib/browser-auth', () => ({ browserAccount: vi.fn() }));
vi.mock('../lib/browser-live', async (load) => ({
  ...(await load<typeof import('../lib/browser-live')>()),
  acceptBrowserLiveSignalCommand: vi.fn(),
  issueBrowserLiveExpectedSignalCommand: vi.fn(),
  readBrowserLiveSignalChoices: vi.fn(),
  stopBrowserLiveSignalCommand: vi.fn(),
}));
const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const contextId = '00000000-0000-4000-8000-000000000003';
const anchorId = '00000000-0000-4000-8000-000000000004';
const commandId = '00000000-0000-4000-8000-000000000005';
const later = (seconds: number) => new Date(Date.now() + seconds * 1000).toISOString();
let authority: RoutePlannerContributionAuthority | null;
let listeners: Set<() => void>;
let coordinator: LiveSignalRecoveryCoordinator;
let source: RoutePlannerContributionSource;
function receipt() {
  const now = Date.now();
  return {
    commandId,
    status: 'accepted' as const,
    receivedAt: new Date(now).toISOString(),
    expiresAt: new Date(now + 900_000).toISOString(),
    retainUntil: new Date(now + 86_400_000).toISOString(),
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { resolve, reject, promise };
}
function panel(overrides: Partial<React.ComponentProps<typeof PrivateSignalsPanel>> = {}) {
  return (
    <PrivateSignalsPanel
      coordinator={coordinator}
      accountId={accountId}
      journeyId={journeyId}
      identityConfirmed
      online
      available
      source={source}
      {...overrides}
    />
  );
}
async function choose() {
  fireEvent.click(screen.getByRole('button', { name: 'Check available signals' }));
  await screen.findByLabelText('Route area');
  fireEvent.change(screen.getByLabelText('Route area'), { target: { value: anchorId } });
  fireEvent.change(screen.getByLabelText('Observation'), { target: { value: 'queue_under_5' } });
  fireEvent.click(screen.getByRole('checkbox'));
}
function invalidate() {
  authority = null;
  listeners.forEach((notify) => notify());
}
beforeEach(() => {
  vi.useRealTimers();
  coordinator = new LiveSignalRecoveryCoordinator();
  coordinator.setAccount(accountId);
  listeners = new Set();
  authority = {
    accountId,
    journeyId,
    contextId,
    consentGeneration: '9',
    consentEpoch: 1,
    selectionEpoch: 2,
    routeRevision: '4',
    expiresAt: later(900),
  };
  source = {
    read: () => authority,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  });
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  });
  vi.mocked(browserAccount).mockResolvedValue({ accountId });
  vi.mocked(readBrowserLiveSignalChoices).mockResolvedValue({
    contextId,
    routeRevision: '4',
    consentGeneration: '9',
    issuedAt: later(0),
    expiresAt: later(900),
    choices: [{ anchorId, displayLabel: 'Central public stop', categories: ['queue'] }],
  });
  vi.mocked(issueBrowserLiveExpectedSignalCommand).mockResolvedValue({
    commandId,
    anchorId,
    contextId,
    routeRevision: '4',
    consentGeneration: '9',
    categories: ['queue'],
    issuedAt: later(0),
    expiresAt: later(90),
  });
  vi.mocked(acceptBrowserLiveSignalCommand).mockImplementation(async () => receipt());
  vi.mocked(stopBrowserLiveSignalCommand).mockResolvedValue({
    commandId,
    status: 'stopped',
    receipt: null,
  });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});

describe('private Quick Signal controls', () => {
  it('makes no requests on mount, focus or reconnect, and needs fresh bound authority', async () => {
    authority = null;
    render(panel());
    fireEvent.focus(window);
    fireEvent(window, new Event('online'));
    expect(readBrowserLiveSignalChoices).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Check available signals' }));
    expect(readBrowserLiveSignalChoices).not.toHaveBeenCalled();
    expect(screen.getByText(/Prepare this route again/)).toBeTruthy();
  });
  it('offers only catalog-compatible values and requires deliberate safe intent before exact issuance', async () => {
    render(panel());
    await choose();
    expect(screen.queryByRole('option', { name: /Traffic/ })).toBeNull();
    fireEvent.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: 'Send privately' })).toHaveProperty('disabled', true);
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await screen.findByText('Saved privately. This is not a public LIVE post.');
    expect(issueBrowserLiveExpectedSignalCommand).toHaveBeenCalledExactlyOnceWith(
      accountId,
      journeyId,
      { anchorId, contextId, routeRevision: '4', consentGeneration: '9' },
      expect.any(AbortSignal),
    );
    expect(acceptBrowserLiveSignalCommand).toHaveBeenCalledExactlyOnceWith(
      accountId,
      journeyId,
      commandId,
      { anchorId, contextId, routeRevision: '4', consentGeneration: '9', value: 'queue_under_5' },
      expect.any(AbortSignal),
    );
    expect(screen.queryByText(commandId)).toBeNull();
  });
  it('rejects a choice snapshot from another context without displaying labels', async () => {
    vi.mocked(readBrowserLiveSignalChoices).mockResolvedValue({
      ...(await readBrowserLiveSignalChoices(accountId, journeyId)),
      contextId: commandId,
    });
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check available signals' }));
    await screen.findByText(/prepared route changed/);
    expect(screen.queryByLabelText('Route area')).toBeNull();
  });
  it('aborts an in-flight issuance synchronously on route invalidation and never accepts a late grant', async () => {
    const grant = await issueBrowserLiveExpectedSignalCommand(accountId, journeyId, {});
    vi.mocked(issueBrowserLiveExpectedSignalCommand).mockClear();
    const pending = deferred<typeof grant>();
    vi.mocked(issueBrowserLiveExpectedSignalCommand).mockReturnValue(pending.promise);
    render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    const signal = vi.mocked(issueBrowserLiveExpectedSignalCommand).mock.calls[0]![3]!;
    act(invalidate);
    expect(signal.aborted).toBe(true);
    await act(async () => pending.resolve(grant));
    expect(acceptBrowserLiveSignalCommand).not.toHaveBeenCalled();
    expect(coordinator.snapshot(accountId)).toHaveLength(0);
  });
  it('retains uncertain acceptance through Ghost/completion and stops the original command', async () => {
    const pending = deferred<ReturnType<typeof receipt>>();
    vi.mocked(acceptBrowserLiveSignalCommand).mockReturnValue(pending.promise);
    const view = render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await waitFor(() => expect(acceptBrowserLiveSignalCommand).toHaveBeenCalledTimes(1));
    act(invalidate);
    view.rerender(panel({ available: false, journeyId: null }));
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('acceptance_uncertain');
    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));
    await screen.findByText(/cannot create a new acceptance/);
    expect(stopBrowserLiveSignalCommand).toHaveBeenCalledExactlyOnceWith(
      accountId,
      journeyId,
      commandId,
      expect.any(AbortSignal),
    );
    await act(async () => pending.resolve(receipt()));
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('stopped');
  });
  it('Stop beats a late successful acceptance and never replays acceptance', async () => {
    const pending = deferred<ReturnType<typeof receipt>>();
    vi.mocked(acceptBrowserLiveSignalCommand).mockReturnValue(pending.promise);
    render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await screen.findByRole('button', { name: 'Stop command 1' });
    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));
    await screen.findByText(/cannot create a new acceptance/);
    await act(async () => pending.resolve(receipt()));
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('stopped');
    expect(acceptBrowserLiveSignalCommand).toHaveBeenCalledTimes(1);
  });
  it('failed stopping preserves the command for an explicit exact retry', async () => {
    render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await screen.findByText(/Saved privately/);
    vi.mocked(stopBrowserLiveSignalCommand).mockRejectedValueOnce(new Error('private detail'));
    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));
    await screen.findByText(/Stopping is unconfirmed/);
    expect(screen.queryByText('private detail')).toBeNull();
    expect(stopBrowserLiveSignalCommand).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));
    await screen.findByText(/cannot create a new acceptance/);
    expect(stopBrowserLiveSignalCommand).toHaveBeenCalledTimes(2);
  });
  it('hides recovery during same-account verification but preserves it on return and panel remount', async () => {
    const view = render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await screen.findByText(/Saved privately/);
    view.rerender(panel({ identityConfirmed: false }));
    expect(screen.queryByRole('button', { name: 'Stop command 1' })).toBeNull();
    expect(coordinator.snapshot(accountId)).toHaveLength(1);
    view.rerender(panel());
    expect(screen.getByRole('button', { name: 'Stop command 1' })).toBeTruthy();
    view.unmount();
    render(panel());
    expect(screen.getByRole('button', { name: 'Stop command 1' })).toBeTruthy();
  });
  it('does not stop under a different verified account', async () => {
    render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await screen.findByText(/Saved privately/);
    vi.mocked(browserAccount).mockResolvedValue({ accountId: contextId });
    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));
    await screen.findByText('Confirm your account before retrying Stop.');
    expect(stopBrowserLiveSignalCommand).not.toHaveBeenCalled();
  });
  it('lost issuance creates no invented recovery handle or automatic retry', async () => {
    vi.mocked(issueBrowserLiveExpectedSignalCommand).mockRejectedValue(new Error('failed'));
    render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await screen.findByText(/Command issuance could not be confirmed/);
    expect(coordinator.snapshot(accountId)).toHaveLength(0);
    expect(acceptBrowserLiveSignalCommand).not.toHaveBeenCalled();
    expect(issueBrowserLiveExpectedSignalCommand).toHaveBeenCalledTimes(1);
  });
  it('requires separate acknowledgment before removing an accepted handle without stopping', async () => {
    render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await screen.findByText(/Saved privately/);
    fireEvent.click(screen.getByRole('button', { name: 'Remove from this page' }));
    expect(coordinator.snapshot(accountId)).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Keep controls' }));
    expect(coordinator.snapshot(accountId)).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Remove from this page' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove local controls' }));
    expect(coordinator.snapshot(accountId)).toHaveLength(0);
    expect(stopBrowserLiveSignalCommand).not.toHaveBeenCalled();
  });
  it('a stale getter without a notification cannot leave a completed acceptance stuck in flight', async () => {
    const pending = deferred<ReturnType<typeof receipt>>();
    vi.mocked(acceptBrowserLiveSignalCommand).mockReturnValue(pending.promise);
    render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await waitFor(() => expect(acceptBrowserLiveSignalCommand).toHaveBeenCalledTimes(1));
    authority = null;
    await act(async () => pending.resolve(receipt()));
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('acceptance_uncertain');
  });
});
