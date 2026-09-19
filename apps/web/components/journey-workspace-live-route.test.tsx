// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { authAvailability, browserAccount } from '../lib/browser-auth';
import {
  dispatchBrowserJourneyBatch,
  restoreBrowserJourneyAuthentication,
} from '../lib/journey-dispatch';
import { listBrowserJournals } from '../lib/journal-storage';
import { readBrowserJourneyPartition } from '../lib/journey-storage';
import type { LiveConsentConfirmation } from './live-consent-panel';
import type { RoutePlannerLiveBinding } from './route-planner';
import { JourneyWorkspace } from './journey-workspace';

const controls = vi.hoisted(() => ({
  consentCallback: undefined as
    ((confirmation: LiveConsentConfirmation | null) => void) | undefined,
  binding: undefined as RoutePlannerLiveBinding | undefined,
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

beforeEach(() => {
  controls.consentCallback = undefined;
  controls.binding = undefined;
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
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.resetAllMocks();
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
