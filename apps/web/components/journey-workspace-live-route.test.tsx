// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { authAvailability, browserAccount } from '../lib/browser-auth';
import {
  dispatchBrowserJourneyBatch,
  restoreBrowserJourneyAuthentication,
} from '../lib/journey-dispatch';
import { listBrowserJournals } from '../lib/journal-storage';
import { queueBrowserJourneyAction, readBrowserJourneyPartition } from '../lib/journey-storage';
import type { LiveSignalRecoveryCoordinator } from '../lib/live-signal-recovery';
import type { LiveConsentConfirmation } from './live-consent-panel';
import type {
  RoutePlannerContributionAuthority,
  RoutePlannerContributionSource,
  RoutePlannerLiveBinding,
} from './route-planner';
import { JourneyWorkspace } from './journey-workspace';

const controls = vi.hoisted(() => ({
  consentCallback: undefined as
    ((confirmation: LiveConsentConfirmation | null) => void) | undefined,
  binding: undefined as RoutePlannerLiveBinding | undefined,
  privateSignals: undefined as
    | {
        accountId: string;
        source: RoutePlannerContributionSource;
        coordinator: LiveSignalRecoveryCoordinator;
      }
    | undefined,
}));

vi.mock('../lib/browser-auth');
vi.mock('../lib/journey-storage');
vi.mock('../lib/journey-dispatch');
vi.mock('../lib/journey-restoration');
vi.mock('../lib/journal-storage', () => ({ listBrowserJournals: vi.fn(async () => []) }));
vi.mock('./live-consent-panel', () => ({
  LiveConsentPanel: ({
    onAuthorityChange,
  }: {
    onAuthorityChange?: (confirmation: LiveConsentConfirmation | null) => void;
  }) => {
    controls.consentCallback = onAuthorityChange;
    return <div data-testid="consent-panel" />;
  },
}));
vi.mock('./route-planner', () => ({
  RoutePlanner: ({ liveBinding }: { liveBinding?: RoutePlannerLiveBinding }) => {
    controls.binding = liveBinding;
    return (
      <div data-testid="route-planner">{liveBinding ? 'binding available' : 'planning only'}</div>
    );
  },
}));
vi.mock('./private-signals-panel', () => ({
  PrivateSignalsPanel: (props: {
    accountId: string;
    source: RoutePlannerContributionSource;
    coordinator: LiveSignalRecoveryCoordinator;
  }) => {
    controls.privateSignals = props;
    return <div data-testid="private-signals" />;
  },
}));
vi.mock('./community-traffic-panel', () => ({
  CommunityTrafficPanel: () => <div data-testid="v3-feed" />,
}));
vi.mock('./community-share-recovery-panel', () => ({
  CommunityShareRecoveryPanel: () => <div data-testid="v3-recovery" />,
}));
vi.mock('./commute-summaries', () => ({ CommuteSummaries: () => null }));
vi.mock('./journey-history', () => ({ JourneyHistory: () => null }));

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000009';
const activePartition = {
  outbox: { version: 1 as const, accountId, entries: [] },
  snapshots: {
    version: 1 as const,
    accountId,
    journeys: [
      {
        id: journeyId,
        kind: 'trip' as const,
        status: 'active' as const,
        startedAt: '2026-09-19T06:00:00.000000Z',
        completedAt: null,
      },
    ],
  },
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

function attachContributionSource() {
  const binding = controls.binding!;
  const authority: RoutePlannerContributionAuthority = {
    accountId,
    journeyId,
    consentGeneration: binding.authority.generation,
    consentEpoch: binding.authority.epoch,
    selectionEpoch: 11,
    contextId: '00000000-0000-4000-8000-000000000011',
    routeRevision: '7',
    expiresAt: '2099-09-19T08:10:00Z',
  };
  const listeners = new Set<() => void>();
  const source: RoutePlannerContributionSource = {
    read: () => {
      const consent = binding.getAuthority();
      return consent &&
        consent.accountId === authority.accountId &&
        consent.journeyId === authority.journeyId &&
        consent.generation === authority.consentGeneration &&
        consent.epoch === authority.consentEpoch
        ? { ...authority }
        : null;
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
  const detach = binding.registerContributionSource!(source);
  return { authority, detach, notify: () => listeners.forEach((listener) => listener()) };
}

beforeEach(() => {
  controls.consentCallback = undefined;
  controls.binding = undefined;
  controls.privateSignals = undefined;
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
  vi.mocked(authAvailability).mockResolvedValue({
    enabled: true,
    clientId: 'test.apps.googleusercontent.com',
  });
  vi.mocked(browserAccount).mockResolvedValue({ accountId });
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue(activePartition);
  vi.mocked(listBrowserJournals).mockResolvedValue([]);
  vi.mocked(restoreBrowserJourneyAuthentication).mockResolvedValue(true);
  vi.mocked(dispatchBrowserJourneyBatch).mockResolvedValue({ acknowledged: 0, reason: 'idle' });
  vi.mocked(queueBrowserJourneyAction).mockImplementation(
    async (_account, _command) => activePartition,
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});

it('mounts V3 reader and recovery only with the separate V3 flag', async () => {
  vi.stubEnv('NEXT_PUBLIC_ROUTIQO_PUBLIC_SIGNAL_INTENT_UI_ENABLED', 'true');
  vi.stubEnv('NEXT_PUBLIC_ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED', 'false');
  const first = render(<JourneyWorkspace />);
  await screen.findByTestId('consent-panel');
  expect(screen.queryByTestId('v3-feed')).toBeNull();
  expect(screen.queryByTestId('v3-recovery')).toBeNull();
  first.unmount();
  vi.stubEnv('NEXT_PUBLIC_ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED', 'true');
  render(<JourneyWorkspace />);
  expect(await screen.findByTestId('v3-feed')).toBeTruthy();
  expect(screen.getByTestId('v3-recovery')).toBeTruthy();
});

it('mounts route binding only after the consent panel publishes matching confirmed authority', async () => {
  render(<JourneyWorkspace />);
  await screen.findByTestId('consent-panel');
  expect(screen.getByTestId('route-planner').textContent).toBe('planning only');
  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '9007199254740993' });
  });
  await waitFor(() =>
    expect(screen.getByTestId('route-planner').textContent).toBe('binding available'),
  );
  expect(controls.binding?.getAuthority()).toMatchObject({
    accountId,
    journeyId,
    generation: '9007199254740993',
  });

  const published = controls.binding!;
  act(() => {
    controls.consentCallback?.(null);
    expect(published.getAuthority()).toBeNull();
  });
  expect(screen.getByTestId('route-planner').textContent).toBe('planning only');
});

it('cannot resurrect old consent authority during a delayed identity refresh', async () => {
  render(<JourneyWorkspace />);
  await screen.findByTestId('consent-panel');
  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '5' });
  });
  await waitFor(() => expect(controls.binding).toBeTruthy());
  const published = controls.binding!;
  const pendingIdentity = deferred<{ accountId: string }>();
  vi.mocked(browserAccount).mockReturnValueOnce(pendingIdentity.promise);

  act(() => {
    fireEvent.focus(window);
    expect(published.getAuthority()).toBeNull();
    controls.consentCallback?.({ accountId, journeyId, generation: '6' });
    expect(published.getAuthority()).toBeNull();
  });
  expect(screen.getByTestId('route-planner').textContent).toBe('planning only');

  pendingIdentity.resolve({ accountId });
  await waitFor(() => expect(browserAccount).toHaveBeenCalledTimes(2));
  expect(screen.getByTestId('route-planner').textContent).toBe('planning only');
});

it('keeps consent authority closed across offline and reconnect until a fresh confirmation', async () => {
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
  render(<JourneyWorkspace />);
  await screen.findByTestId('consent-panel');
  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '5' });
  });
  await waitFor(() => expect(controls.binding).toBeTruthy());
  const published = controls.binding!;

  online.mockReturnValue(false);
  act(() => window.dispatchEvent(new Event('offline')));
  expect(published.getAuthority()).toBeNull();
  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '6' });
  });
  expect(published.getAuthority()).toBeNull();

  online.mockReturnValue(true);
  act(() => window.dispatchEvent(new Event('online')));
  expect(screen.getByTestId('route-planner').textContent).toBe('planning only');
  expect(published.getAuthority()).toBeNull();

  act(() => {
    controls.consentCallback?.(null);
    controls.consentCallback?.({ accountId, journeyId, generation: '7' });
  });
  await waitFor(() =>
    expect(screen.getByTestId('route-planner').textContent).toBe('binding available'),
  );
});

it('notifies the prepared source synchronously when consent advances or completion starts', async () => {
  render(<JourneyWorkspace />);
  await screen.findByTestId('consent-panel');
  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '5' });
  });
  await waitFor(() => expect(controls.binding).toBeTruthy());
  const attached = attachContributionSource();
  const prepared = controls.privateSignals!.source;
  expect(prepared.read()).toEqual(attached.authority);
  const observed: Array<RoutePlannerContributionAuthority | null> = [];
  const unsubscribe = prepared.subscribe(() => observed.push(prepared.read()));

  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '6' });
    expect(prepared.read()).toBeNull();
    expect(observed.at(-1)).toBeNull();
  });

  await waitFor(() => expect(controls.binding?.authority.generation).toBe('6'));
  const replacement = attachContributionSource();
  expect(prepared.read()).toEqual(replacement.authority);
  const pendingSave = deferred<typeof activePartition>();
  vi.mocked(queueBrowserJourneyAction).mockReturnValueOnce(pendingSave.promise);
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: 'Finish journey' }));
    expect(prepared.read()).toBeNull();
    expect(observed.at(-1)).toBeNull();
  });
  pendingSave.resolve(activePartition);
  await act(async () => Promise.resolve());
  replacement.detach?.();
  attached.detach?.();
  unsubscribe();
});

it('keeps recovery identity across same-account refresh and clears it on account change', async () => {
  render(<JourneyWorkspace />);
  await screen.findByTestId('consent-panel');
  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '5' });
  });
  await waitFor(() => expect(controls.binding).toBeTruthy());
  attachContributionSource();
  const prepared = controls.privateSignals!.source;
  const coordinator = controls.privateSignals!.coordinator;
  const commandId = '00000000-0000-4000-8000-000000000099';
  const ticket = coordinator.beginAcceptance(accountId, journeyId, commandId);
  coordinator.markAcceptanceUncertain(ticket);
  const notifications: Array<RoutePlannerContributionAuthority | null> = [];
  prepared.subscribe(() => notifications.push(prepared.read()));

  vi.mocked(browserAccount).mockResolvedValueOnce({ accountId });
  act(() => {
    fireEvent.focus(window);
    expect(prepared.read()).toBeNull();
    expect(notifications.at(-1)).toBeNull();
  });
  await waitFor(() => expect(browserAccount).toHaveBeenCalledTimes(2));
  expect(controls.privateSignals!.coordinator).toBe(coordinator);
  expect(coordinator.snapshot(accountId)).toMatchObject([
    { commandId, phase: 'acceptance_uncertain' },
  ]);

  act(() => {
    controls.consentCallback?.({ accountId, journeyId, generation: '6' });
  });
  await waitFor(() => expect(controls.binding?.authority.generation).toBe('6'));
  const replacementSource = attachContributionSource();
  expect(prepared.read()).toEqual(replacementSource.authority);
  const notificationsBeforeSwitch = notifications.length;

  const replacementAccount = '00000000-0000-4000-8000-000000000002';
  vi.mocked(browserAccount).mockResolvedValueOnce({ accountId: replacementAccount });
  act(() => {
    window.dispatchEvent(new Event('focus'));
    expect(prepared.read()).toBeNull();
    expect(notifications.length).toBeGreaterThan(notificationsBeforeSwitch);
    expect(notifications.at(-1)).toBeNull();
  });
  await waitFor(() => expect(controls.privateSignals?.accountId).toBe(replacementAccount));
  expect(prepared.read()).toBeNull();
  expect(controls.privateSignals!.coordinator).toBe(coordinator);
  expect(coordinator.snapshot(accountId)).toEqual([]);
  expect(coordinator.snapshot(replacementAccount)).toEqual([]);
});
