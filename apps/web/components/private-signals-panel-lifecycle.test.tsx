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
import { PrivateSignalsPanel } from './private-signals-panel';
import type {
  RoutePlannerContributionAuthority,
  RoutePlannerContributionSource,
} from './route-planner';

vi.mock('../lib/browser-auth', () => ({ browserAccount: vi.fn() }));
vi.mock('../lib/browser-live', async (load) => ({
  ...(await load<typeof import('../lib/browser-live')>()),
  acceptBrowserLiveSignalCommand: vi.fn(),
  issueBrowserLiveExpectedSignalCommand: vi.fn(),
  readBrowserLiveSignalChoices: vi.fn(),
  stopBrowserLiveSignalCommand: vi.fn(),
}));

const accountId = '00000000-0000-4000-8000-000000000101';
const otherAccountId = '00000000-0000-4000-8000-000000000102';
const journeyId = '00000000-0000-4000-8000-000000000103';
const contextId = '00000000-0000-4000-8000-000000000104';
const anchorId = '00000000-0000-4000-8000-000000000105';
const commandId = '00000000-0000-4000-8000-000000000106';
const instant = (offsetSeconds: number) =>
  new Date(Date.now() + offsetSeconds * 1000).toISOString();

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

function acceptedReceipt(id = commandId) {
  const now = Date.now();
  return {
    commandId: id,
    status: 'accepted' as const,
    receivedAt: new Date(now).toISOString(),
    expiresAt: new Date(now + 900_000).toISOString(),
    retainUntil: new Date(now + 86_400_000).toISOString(),
  };
}

let coordinator: LiveSignalRecoveryCoordinator;
let authority: RoutePlannerContributionAuthority | null;
let listeners: Set<() => void>;
let source: RoutePlannerContributionSource;

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

function notifyRouteInvalidation() {
  authority = null;
  listeners.forEach((listener) => listener());
}

function seedAccepted(id = commandId) {
  const ticket = coordinator.beginAcceptance(accountId, journeyId, id);
  coordinator.confirmAcceptance(ticket, acceptedReceipt(id));
}

async function choose(value = 'queue_under_5') {
  fireEvent.click(screen.getByRole('button', { name: 'Check available signals' }));
  await screen.findByLabelText('Route area');
  fireEvent.change(screen.getByLabelText('Route area'), { target: { value: anchorId } });
  fireEvent.change(screen.getByLabelText('Observation'), { target: { value } });
  fireEvent.click(screen.getByRole('checkbox'));
}

beforeEach(() => {
  coordinator = new LiveSignalRecoveryCoordinator();
  coordinator.setAccount(accountId);
  listeners = new Set();
  authority = {
    accountId,
    journeyId,
    contextId,
    consentGeneration: '7',
    consentEpoch: 3,
    selectionEpoch: 5,
    routeRevision: '11',
    expiresAt: instant(900),
  };
  source = {
    read: () => authority,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
  vi.mocked(browserAccount).mockResolvedValue({ accountId });
  vi.mocked(readBrowserLiveSignalChoices).mockResolvedValue({
    contextId,
    routeRevision: '11',
    consentGeneration: '7',
    issuedAt: instant(0),
    expiresAt: instant(900),
    choices: [{ anchorId, displayLabel: 'North entrance', categories: ['queue'] }],
  });
  vi.mocked(issueBrowserLiveExpectedSignalCommand).mockResolvedValue({
    commandId,
    anchorId,
    contextId,
    routeRevision: '11',
    consentGeneration: '7',
    categories: ['queue'],
    issuedAt: instant(0),
    expiresAt: instant(90),
  });
  vi.mocked(acceptBrowserLiveSignalCommand).mockResolvedValue(acceptedReceipt());
  vi.mocked(stopBrowserLiveSignalCommand).mockResolvedValue({
    commandId,
    status: 'stopped',
    receipt: null,
  });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});

describe('private signal lifecycle fences', () => {
  it('marks a known acceptance uncertain when the panel unmounts during acceptance', async () => {
    const acceptance = deferred<ReturnType<typeof acceptedReceipt>>();
    vi.mocked(acceptBrowserLiveSignalCommand).mockReturnValue(acceptance.promise);
    const view = render(panel());
    await choose();
    fireEvent.click(screen.getByRole('button', { name: 'Send privately' }));
    await waitFor(() => expect(acceptBrowserLiveSignalCommand).toHaveBeenCalledTimes(1));
    const signal = vi.mocked(acceptBrowserLiveSignalCommand).mock.calls[0]![4]!;

    view.unmount();

    expect(signal.aborted).toBe(true);
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('acceptance_uncertain');
  });

  it('keeps an explicit Stop alive across a consent or route notification', async () => {
    seedAccepted();
    const identity = deferred<{ accountId: string }>();
    vi.mocked(browserAccount).mockReturnValue(identity.promise);
    render(panel());

    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));
    act(notifyRouteInvalidation);
    await act(async () => identity.resolve({ accountId }));

    await waitFor(() => expect(stopBrowserLiveSignalCommand).toHaveBeenCalledTimes(1));
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('stopped');
  });

  it('marks Stop uncertain when connectivity is lost mid-request', async () => {
    seedAccepted();
    const stopped = deferred<{
      commandId: string;
      status: 'stopped';
      receipt: null;
    }>();
    vi.mocked(stopBrowserLiveSignalCommand).mockReturnValue(stopped.promise);
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));
    await waitFor(() => expect(stopBrowserLiveSignalCommand).toHaveBeenCalledTimes(1));
    const signal = vi.mocked(stopBrowserLiveSignalCommand).mock.calls[0]![3]!;

    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
    act(() => window.dispatchEvent(new Event('offline')));

    expect(signal.aborted).toBe(true);
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('stop_uncertain');
    await act(async () => stopped.resolve({ commandId, status: 'stopped', receipt: null }));
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('stop_uncertain');
  });

  it('does not call Stop when the account preflight is stale', async () => {
    seedAccepted();
    vi.mocked(browserAccount).mockResolvedValue({ accountId: otherAccountId });
    render(panel());

    fireEvent.click(screen.getByRole('button', { name: 'Stop command 1' }));

    await screen.findByText('Confirm your account before retrying Stop.');
    expect(stopBrowserLiveSignalCommand).not.toHaveBeenCalled();
    expect(coordinator.snapshot(accountId)[0]?.phase).toBe('stop_uncertain');
  });

  it('removes expired choice labels without making another request', async () => {
    let now = Date.parse('2026-09-19T12:00:00.000Z');
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    authority = { ...authority!, expiresAt: instant(900) };
    vi.mocked(readBrowserLiveSignalChoices).mockResolvedValue({
      contextId,
      routeRevision: '11',
      consentGeneration: '7',
      issuedAt: instant(0),
      expiresAt: instant(1),
      choices: [{ anchorId, displayLabel: 'Short-lived label', categories: ['queue'] }],
    });
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check available signals' }));
    expect(await screen.findByText('Short-lived label')).toBeTruthy();
    vi.mocked(readBrowserLiveSignalChoices).mockClear();

    now += 1001;
    act(() => window.dispatchEvent(new Event('focus')));

    expect(screen.queryByText('Short-lived label')).toBeNull();
    expect(screen.queryByLabelText('Route area')).toBeNull();
    expect(readBrowserLiveSignalChoices).not.toHaveBeenCalled();
  });

  it('does not restore an expired choice after the wall clock rolls back', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    let now = Date.parse('2026-09-19T12:00:00.000Z');
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    authority = { ...authority!, expiresAt: instant(900) };
    vi.mocked(readBrowserLiveSignalChoices).mockResolvedValue({
      contextId,
      routeRevision: '11',
      consentGeneration: '7',
      issuedAt: instant(0),
      expiresAt: instant(1),
      choices: [{ anchorId, displayLabel: 'One-way expiry label', categories: ['queue'] }],
    });
    render(panel());
    fireEvent.click(screen.getByRole('button', { name: 'Check available signals' }));
    await act(async () => undefined);
    expect(screen.getByText('One-way expiry label')).toBeTruthy();

    now += 1001;
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.queryByText('One-way expiry label')).toBeNull();

    now -= 1001;
    act(() => window.dispatchEvent(new Event('focus')));
    expect(screen.queryByText('One-way expiry label')).toBeNull();
    expect(screen.queryByLabelText('Route area')).toBeNull();
    expect(readBrowserLiveSignalChoices).toHaveBeenCalledTimes(1);
  });

  it('starts only one issuance when Send is clicked repeatedly', async () => {
    const issuance = deferred<Awaited<ReturnType<typeof issueBrowserLiveExpectedSignalCommand>>>();
    vi.mocked(issueBrowserLiveExpectedSignalCommand).mockReturnValue(issuance.promise);
    render(panel());
    await choose();
    const send = screen.getByRole('button', { name: 'Send privately' });

    fireEvent.click(send);
    fireEvent.click(send);

    expect(issueBrowserLiveExpectedSignalCommand).toHaveBeenCalledTimes(1);
    expect(acceptBrowserLiveSignalCommand).not.toHaveBeenCalled();
  });

  it('resets deliberate safe intent when the selected observation changes', async () => {
    render(panel());
    await choose();
    expect(screen.getByRole('checkbox')).toHaveProperty('checked', true);

    fireEvent.change(screen.getByLabelText('Observation'), {
      target: { value: 'queue_5_to_15' },
    });

    expect(screen.getByRole('checkbox')).toHaveProperty('checked', false);
    expect(screen.getByRole('button', { name: 'Send privately' })).toHaveProperty('disabled', true);
    expect(issueBrowserLiveExpectedSignalCommand).not.toHaveBeenCalled();
  });

  it('returns keyboard focus to Check when a successful Send removes its form', async () => {
    render(panel());
    await choose();
    const send = screen.getByRole('button', { name: 'Send privately' });
    send.focus();

    fireEvent.click(send, { detail: 0 });

    await screen.findByText('Saved privately. This is not a public LIVE post.');
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Check available signals' }),
      ),
    );
  });

  it('moves focus from a removed successful Stop to an available panel action', async () => {
    seedAccepted();
    render(panel());
    const stop = screen.getByRole('button', { name: 'Stop command 1' });
    stop.focus();

    fireEvent.click(stop, { detail: 0 });

    await screen.findByText(
      'This command cannot create a new acceptance. It may have been accepted earlier.',
    );
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Check available signals' }),
      ),
    );
  });

  it('does not steal focus when the user moves outside while Send is awaiting a result', async () => {
    const acceptance = deferred<ReturnType<typeof acceptedReceipt>>();
    vi.mocked(acceptBrowserLiveSignalCommand).mockReturnValue(acceptance.promise);
    render(
      <>
        <button type="button">Outside control</button>
        {panel()}
      </>,
    );
    await choose();
    const send = screen.getByRole('button', { name: 'Send privately' });
    send.focus();
    fireEvent.click(send, { detail: 0 });
    await waitFor(() => expect(acceptBrowserLiveSignalCommand).toHaveBeenCalledTimes(1));
    const outside = screen.getByRole('button', { name: 'Outside control' });
    outside.focus();

    await act(async () => acceptance.resolve(acceptedReceipt()));

    expect(document.activeElement).toBe(outside);
    expect(screen.queryByLabelText('Route area')).toBeNull();
  });

  it.each(['unresolved', 'capacity'] as const)(
    'does not issue when recovery is blocked by %s state',
    async (state) => {
      if (state === 'unresolved') {
        const ticket = coordinator.beginAcceptance(accountId, journeyId, commandId);
        coordinator.markAcceptanceUncertain(ticket);
      } else {
        for (let index = 0; index < 5; index += 1) {
          const id = `00000000-0000-4000-8000-${String(200 + index).padStart(12, '0')}`;
          seedAccepted(id);
        }
      }
      render(panel());
      await choose();

      const send = screen.getByRole('button', { name: 'Send privately' });
      expect(send).toHaveProperty('disabled', true);
      fireEvent.click(send);
      expect(issueBrowserLiveExpectedSignalCommand).not.toHaveBeenCalled();
    },
  );

  it('does not perform network work on focus or reconnect', () => {
    render(panel());

    act(() => window.dispatchEvent(new Event('focus')));
    act(() => window.dispatchEvent(new Event('online')));

    expect(readBrowserLiveSignalChoices).not.toHaveBeenCalled();
    expect(issueBrowserLiveExpectedSignalCommand).not.toHaveBeenCalled();
    expect(acceptBrowserLiveSignalCommand).not.toHaveBeenCalled();
    expect(stopBrowserLiveSignalCommand).not.toHaveBeenCalled();
    expect(browserAccount).not.toHaveBeenCalled();
  });

  it('hides unavailable contribution controls without hiding known recovery', () => {
    const view = render(panel({ available: false }));
    expect(screen.queryByRole('region', { name: 'Quick Signals' })).toBeNull();
    seedAccepted();
    view.rerender(panel({ available: false }));
    expect(screen.getByRole('button', { name: 'Stop command 1' })).toHaveProperty(
      'disabled',
      false,
    );
    expect(screen.queryByRole('button', { name: 'Check available signals' })).toBeNull();
    expect(readBrowserLiveSignalChoices).not.toHaveBeenCalled();
  });
});
