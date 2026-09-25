// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { JourneyWorkspace } from './journey-workspace';
import { restoreRecentBrowserJourneyHistory } from '../lib/journey-restoration';
import { authAvailability, browserAccount } from '../lib/browser-auth';
import { listBrowserJournals } from '../lib/journal-storage';
import { readBrowserJourneyPartition, queueBrowserJourneyAction } from '../lib/journey-storage';
import {
  restoreBrowserJourneyAuthentication,
  dispatchBrowserJourneyBatch,
} from '../lib/journey-dispatch';
vi.mock('../lib/browser-auth');
vi.mock('../lib/journey-storage');
vi.mock('../lib/journey-dispatch');
vi.mock('../lib/journey-restoration');
vi.mock('../lib/journal-storage', () => ({ listBrowserJournals: vi.fn(async () => []) }));
vi.mock('./journal-editor', () => ({
  JournalEditor: ({ account, journeyId }: { account: string; journeyId: string }) => (
    <div data-testid="trip-journal">
      {account}:{journeyId}
    </div>
  ),
}));
const accountId = '00000000-0000-4000-8000-000000000001';
const empty = () => ({
  outbox: { version: 1 as const, accountId, entries: [] },
  snapshots: { version: 1 as const, accountId, journeys: [] },
});
const activeJourneyId = '00000000-0000-4000-8000-000000000009';
const active = () => ({
  ...empty(),
  snapshots: {
    version: 1 as const,
    accountId,
    journeys: [
      {
        id: activeJourneyId,
        kind: 'trip' as const,
        status: 'active' as const,
        startedAt: '2026-09-19T06:00:00.000000Z',
        completedAt: null,
      },
    ],
  },
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
}
it('shows a confirmed completion and allows a new journey', async () => {
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue({
    ...empty(),
    snapshots: {
      version: 1,
      accountId,
      journeys: [
        {
          id: accountId,
          kind: 'commute',
          status: 'completed',
          startedAt: '2026-09-08T12:00:00.000000Z',
          completedAt: '2026-09-08T13:00:00.000000Z',
        },
      ],
    },
  });
  render(<JourneyWorkspace />);
  await screen.findByText('Your last journey is complete.');
  expect(screen.getByRole('heading', { name: 'Recently completed' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Start a journey' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Finish journey' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Open trip journal' })).toBeNull();
});
it('keeps a retained journal draft accessible without a recent journey snapshot', async () => {
  const journeyId = '00000000-0000-4000-8000-000000000004';
  vi.mocked(listBrowserJournals).mockResolvedValue([
    {
      journal: {
        journey: {
          id: journeyId,
          kind: 'trip',
          status: 'completed',
          startedAt: '2026-08-01T12:00:00Z',
          completedAt: '2026-08-01T13:00:00Z',
        },
        annotation: { title: '', notes: '', version: 0, updatedAt: null },
      },
      draft: {
        journeyId,
        title: 'Retained writing',
        notes: 'Unsent',
        expectedVersion: 0,
        mutationId: accountId,
      },
    },
  ]);
  render(<JourneyWorkspace />);
  fireEvent.click(await screen.findByText('Journals saved on this device'));
  fireEvent.click(await screen.findByRole('button', { name: 'Retained writing · Unsent draft' }));
  expect(screen.getByTestId('trip-journal').textContent).toBe(`${accountId}:${journeyId}`);
});
it('opens a completed trip journal with the verified account and journey identity', async () => {
  const journeyId = '00000000-0000-4000-8000-000000000002';
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue({
    ...empty(),
    snapshots: {
      version: 1,
      accountId,
      journeys: [
        {
          id: journeyId,
          kind: 'trip',
          status: 'completed',
          startedAt: '2026-09-08T12:00:00.000000Z',
          completedAt: '2026-09-08T13:00:00.000000Z',
        },
      ],
    },
  });
  render(<JourneyWorkspace />);
  fireEvent.click(await screen.findByRole('button', { name: 'Open trip journal' }));
  expect(screen.getByTestId('trip-journal').textContent).toBe(`${accountId}:${journeyId}`);
  vi.mocked(browserAccount).mockResolvedValue({ accountId: journeyId });
  vi.mocked(readBrowserJourneyPartition).mockRejectedValue(new Error('Unreadable partition'));
  fireEvent.focus(window);
  await waitFor(() => expect(screen.queryByTestId('trip-journal')).toBeNull());
  await screen.findByText('Journey status is unavailable. Saved actions stay on this device.');
});
beforeEach(() => {
  vi.mocked(listBrowserJournals).mockResolvedValue([]);
  vi.mocked(authAvailability).mockResolvedValue({
    enabled: true,
    clientId: 'test.apps.googleusercontent.com',
  });
  vi.mocked(browserAccount).mockResolvedValue({ accountId });
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue(empty());
  vi.mocked(restoreBrowserJourneyAuthentication).mockResolvedValue(true);
  vi.mocked(dispatchBrowserJourneyBatch).mockResolvedValue({ acknowledged: 0, reason: 'idle' });
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open');
  };
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});
it('keeps offline start and finish pending until the server confirms them', async () => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
  let saved: Awaited<ReturnType<typeof readBrowserJourneyPartition>> = empty();
  vi.mocked(queueBrowserJourneyAction).mockImplementation(async (_account, command) => {
    const next = {
      ...saved,
      outbox: {
        ...saved.outbox,
        entries: [
          ...saved.outbox.entries,
          { command, attempts: 0, nextAttemptAt: Date.now(), blocked: null, lease: null },
        ],
      },
    };
    vi.mocked(readBrowserJourneyPartition).mockResolvedValue(next);
    saved = next;
    return next;
  });
  render(<JourneyWorkspace />);
  fireEvent.click(await screen.findByRole('button', { name: 'Start a journey' }));
  fireEvent.click(screen.getByRole('button', { name: 'Start journey' }));
  await screen.findByText('Start saved on this device. Waiting for confirmation.');
  expect(dispatchBrowserJourneyBatch).not.toHaveBeenCalled();
  const startCommand = vi.mocked(queueBrowserJourneyAction).mock.calls[0]![1];
  expect(startCommand.action).toBe('start');
  fireEvent.click(screen.getByRole('button', { name: 'Finish journey' }));
  await waitFor(() => expect(queueBrowserJourneyAction).toHaveBeenCalledTimes(2));
  await screen.findByText('Finish saved on this device. Waiting for confirmation.');
  expect(vi.mocked(queueBrowserJourneyAction).mock.calls[1]![1]).toEqual({
    action: 'complete',
    journeyId: startCommand.journeyId,
  });
  expect(dispatchBrowserJourneyBatch).not.toHaveBeenCalled();
});
it('retries an unblocked due action when connectivity returns', async () => {
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue({
    ...empty(),
    outbox: {
      version: 1,
      accountId,
      entries: [
        {
          command: { action: 'start', kind: 'trip', journeyId: accountId },
          attempts: 1,
          nextAttemptAt: 0,
          blocked: null,
          lease: null,
        },
      ],
    },
  });
  render(<JourneyWorkspace />);
  await screen.findByText('Start saved on this device. Waiting for confirmation.');
  expect(dispatchBrowserJourneyBatch).not.toHaveBeenCalled();
  online.mockReturnValue(true);
  fireEvent(window, new Event('online'));
  await waitFor(() => expect(dispatchBrowserJourneyBatch).toHaveBeenCalledTimes(1));
});
it('does not automatically retry conflicts when connectivity returns', async () => {
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue({
    ...empty(),
    outbox: {
      version: 1,
      accountId,
      entries: [
        {
          command: { action: 'start', kind: 'trip', journeyId: accountId },
          attempts: 1,
          nextAttemptAt: 0,
          blocked: 'conflict',
          lease: null,
        },
      ],
    },
  });
  render(<JourneyWorkspace />);
  await screen.findByRole('button', { name: 'Check server status' });
  fireEvent(window, new Event('online'));
  expect(dispatchBrowserJourneyBatch).not.toHaveBeenCalled();
});
it('does not offer journey writes when sign-in is unavailable', async () => {
  vi.mocked(authAvailability).mockResolvedValue({ enabled: false, clientId: null });
  render(<JourneyWorkspace />);
  await screen.findByText(/Journey start and finish will be available/);
  expect(screen.queryByRole('button', { name: 'Start a journey' })).toBeNull();
  expect(readBrowserJourneyPartition).not.toHaveBeenCalled();
});
it('does not dispatch or close the start dialog after a failed durable save', async () => {
  vi.mocked(queueBrowserJourneyAction).mockRejectedValue(new Error('Storage is full.'));
  render(<JourneyWorkspace />);
  fireEvent.click(await screen.findByRole('button', { name: 'Start a journey' }));
  fireEvent.click(screen.getByRole('button', { name: 'Start journey' }));
  await waitFor(() => expect(queueBrowserJourneyAction).toHaveBeenCalled());
  await screen.findAllByText('Storage is full.');
  expect(dispatchBrowserJourneyBatch).not.toHaveBeenCalled();
  expect(screen.getByRole('dialog', { name: 'Start a journey' })).toBeTruthy();
});
it('closes an old start dialog when the verified account changes', async () => {
  render(<JourneyWorkspace />);
  fireEvent.click(await screen.findByRole('button', { name: 'Start a journey' }));
  vi.mocked(browserAccount).mockResolvedValue(null);
  fireEvent.focus(window);
  await screen.findByRole('link', { name: 'Sign in from Profile' });
  expect(screen.queryByRole('dialog')).toBeNull();
});
it('keeps local journey starts available offline while server restoration waits for connectivity', async () => {
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
  render(<JourneyWorkspace />);
  const restore = (await screen.findByRole('button', {
    name: 'Restore recent journeys',
  })) as HTMLButtonElement;
  online.mockReturnValue(false);
  fireEvent(window, new Event('offline'));
  expect(screen.getByText(/You’re offline. Start and finish actions/)).toBeTruthy();
  expect(restore.disabled).toBe(true);
  expect(
    (screen.getByRole('button', { name: 'Start a journey' }) as HTMLButtonElement).disabled,
  ).toBe(false);
  fireEvent.click(restore);
  expect(restoreRecentBrowserJourneyHistory).not.toHaveBeenCalled();
  online.mockReturnValue(true);
  fireEvent(window, new Event('online'));
  expect(restore.disabled).toBe(false);
  expect(screen.queryByText(/You’re offline. Start and finish actions/)).toBeNull();
  expect(restoreRecentBrowserJourneyHistory).not.toHaveBeenCalled();
});

it('restores confirmed history through the account-bound service and keeps failures visible', async () => {
  vi.mocked(restoreRecentBrowserJourneyHistory).mockResolvedValue({
    partition: empty(),
    recentCount: 20,
  });
  render(<JourneyWorkspace />);
  fireEvent.click(await screen.findByRole('button', { name: 'Restore recent journeys' }));
  await screen.findByText('Checked 20 recent server journeys. Pending actions are preserved.');
  expect(restoreRecentBrowserJourneyHistory).toHaveBeenCalledWith(accountId);
  vi.mocked(restoreRecentBrowserJourneyHistory).mockRejectedValue(new Error('Service unavailable'));
  fireEvent.click(screen.getByRole('button', { name: 'Restore recent journeys' }));
  await screen.findByText('Recent journeys could not be restored. Saved work is unchanged.');
  expect(dispatchBrowserJourneyBatch).not.toHaveBeenCalled();
});

it('mounts private LIVE consent only for a confirmed active journey without an outbox action', async () => {
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue(active());
  render(<JourneyWorkspace />);
  expect(await screen.findByRole('heading', { name: 'Contribution settings' })).toBeTruthy();
  expect(screen.getByText(/Public LIVE is unavailable/)).toBeTruthy();
});

it('suppresses private LIVE consent while the active journey has pending or blocked work', async () => {
  const base = active();
  const pending = {
    ...base,
    outbox: {
      ...base.outbox,
      entries: [
        {
          command: { action: 'complete' as const, journeyId: activeJourneyId },
          attempts: 1,
          nextAttemptAt: Date.now() + 60_000,
          blocked: 'conflict' as const,
          lease: null,
        },
      ],
    },
  };
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue(pending);
  render(<JourneyWorkspace />);
  await screen.findByRole('button', { name: 'Check server status' });
  expect(screen.queryByRole('heading', { name: 'Contribution settings' })).toBeNull();
});

it('hides known LIVE controls during delayed same-account identity re-verification', async () => {
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue(active());
  render(<JourneyWorkspace />);
  await screen.findByRole('heading', { name: 'Contribution settings' });
  const identity = deferred<{ accountId: string } | null>();
  vi.mocked(browserAccount).mockReturnValueOnce(identity.promise);
  fireEvent.focus(window);
  await waitFor(() =>
    expect(screen.queryByRole('heading', { name: 'Contribution settings' })).toBeNull(),
  );
  identity.resolve({ accountId });
  expect(await screen.findByRole('heading', { name: 'Contribution settings' })).toBeTruthy();
});

it('keeps LIVE controls suppressed after same-account re-verification fails', async () => {
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue(active());
  render(<JourneyWorkspace />);
  await screen.findByRole('heading', { name: 'Contribution settings' });
  vi.mocked(browserAccount).mockRejectedValueOnce(new Error('Authentication unavailable'));
  fireEvent.focus(window);
  await screen.findByText('Journey status is unavailable. Saved actions stay on this device.');
  expect(screen.queryByRole('heading', { name: 'Contribution settings' })).toBeNull();
});

it('suppresses private LIVE controls while a parent journey operation is busy', async () => {
  vi.mocked(readBrowserJourneyPartition).mockResolvedValue(active());
  const restore = deferred<Awaited<ReturnType<typeof restoreRecentBrowserJourneyHistory>>>();
  vi.mocked(restoreRecentBrowserJourneyHistory).mockReturnValueOnce(restore.promise);
  render(<JourneyWorkspace />);
  await screen.findByRole('heading', { name: 'Contribution settings' });
  fireEvent.click(screen.getByRole('button', { name: 'Restore recent journeys' }));
  await waitFor(() =>
    expect(screen.queryByRole('heading', { name: 'Contribution settings' })).toBeNull(),
  );
  restore.resolve({ partition: active(), recentCount: 1 });
  expect(await screen.findByRole('heading', { name: 'Contribution settings' })).toBeTruthy();
});

it('ignores a delayed journal library result after switching accounts', async () => {
  const accountA = accountId;
  const accountB = '00000000-0000-4000-8000-000000000002';
  const journeyA = '00000000-0000-4000-8000-000000000011';
  const journeyB = '00000000-0000-4000-8000-000000000022';

  const deferredA = deferred<Awaited<ReturnType<typeof listBrowserJournals>>>();
  const deferredB = deferred<Awaited<ReturnType<typeof listBrowserJournals>>>();

  vi.mocked(browserAccount).mockResolvedValue({ accountId: accountA });
  vi.mocked(readBrowserJourneyPartition).mockImplementation(async (acc) => ({
    outbox: { version: 1 as const, accountId: acc, entries: [] },
    snapshots: { version: 1 as const, accountId: acc, journeys: [] },
  }));
  vi.mocked(listBrowserJournals).mockImplementation((acc) => {
    if (acc === accountA) return deferredA.promise;
    if (acc === accountB) return deferredB.promise;
    return Promise.resolve([]);
  });

  render(<JourneyWorkspace />);

  await waitFor(() => expect(listBrowserJournals).toHaveBeenCalledWith(accountA));

  vi.mocked(browserAccount).mockResolvedValue({ accountId: accountB });
  fireEvent.focus(window);

  await waitFor(() => expect(listBrowserJournals).toHaveBeenCalledWith(accountB));

  deferredB.resolve([
    {
      journal: {
        journey: {
          id: journeyB,
          kind: 'trip' as const,
          status: 'completed' as const,
          startedAt: '2026-08-02T12:00:00Z',
          completedAt: '2026-08-02T13:00:00Z',
        },
        annotation: {
          title: 'Account B Synthetic Title',
          notes: '',
          version: 1,
          updatedAt: '2026-08-02T14:00:00Z',
        },
      },
      draft: null,
    },
  ]);

  fireEvent.click(await screen.findByText('Journals saved on this device'));
  await screen.findByRole('button', { name: 'Account B Synthetic Title' });

  deferredA.resolve([
    {
      journal: {
        journey: {
          id: journeyA,
          kind: 'trip' as const,
          status: 'completed' as const,
          startedAt: '2026-08-01T12:00:00Z',
          completedAt: '2026-08-01T13:00:00Z',
        },
        annotation: {
          title: 'Account A Synthetic Title',
          notes: '',
          version: 1,
          updatedAt: '2026-08-01T14:00:00Z',
        },
      },
      draft: null,
    },
  ]);

  await waitFor(() => {
    expect(screen.queryByText('Account A Synthetic Title')).toBeNull();
  });
  expect(screen.getByRole('button', { name: 'Account B Synthetic Title' })).toBeTruthy();
  expect(screen.getAllByRole('button', { name: /Synthetic Title/ })).toHaveLength(1);

  fireEvent.click(screen.getByRole('button', { name: 'Account B Synthetic Title' }));
  expect(screen.getByTestId('trip-journal').textContent).toBe(`${accountB}:${journeyB}`);

  expect(listBrowserJournals).toHaveBeenCalledWith(accountA);
  expect(listBrowserJournals).toHaveBeenCalledWith(accountB);
});

it('gives sibling panels unique keys so unmounting clears every panel timer', async () => {
  vi.stubEnv('NEXT_PUBLIC_ROUTIQO_PUBLIC_SIGNAL_INTENT_UI_ENABLED', 'true');
  const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  const started = new Set<unknown>();
  const cleared = new Set<unknown>();
  const setInterval = window.setInterval.bind(window);
  const clearInterval = window.clearInterval.bind(window);
  vi.spyOn(window, 'setInterval').mockImplementation(((handler: TimerHandler, timeout?: number) => {
    const handle = setInterval(handler, timeout);
    started.add(handle);
    return handle;
  }) as typeof window.setInterval);
  vi.spyOn(window, 'clearInterval').mockImplementation(((handle?: number) => {
    cleared.add(handle);
    clearInterval(handle);
  }) as typeof window.clearInterval);
  try {
    vi.mocked(readBrowserJourneyPartition).mockResolvedValue(active());
    render(<JourneyWorkspace />);
    await screen.findByRole('heading', { name: 'Contribution settings' });
    await screen.findByText(
      /Recently completed|Journals saved on this device|Start a journey|Finish journey/,
    );
    expect(started.size).toBeGreaterThan(0);
    cleanup();
    expect([...started].filter((handle) => !cleared.has(handle))).toEqual([]);
    expect(errors.mock.calls.filter(([message]) => String(message).includes('same key'))).toEqual(
      [],
    );
  } finally {
    vi.unstubAllEnvs();
  }
});
