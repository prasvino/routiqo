// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ServerJourney } from '@routiqo/shared';
import { StrictMode } from 'react';
import {
  readBrowserJourneyPage,
  type JourneyHistoryCursor,
  type JourneyHistoryPage,
} from '../lib/browser-history';
import { JourneyHistory } from './journey-history';

vi.mock('../lib/browser-history', () => ({ readBrowserJourneyPage: vi.fn() }));

const firstAccount = '00000000-0000-4000-8000-000000000001';
const secondAccount = '00000000-0000-4000-8000-000000000002';
const tripId = '00000000-0000-4000-8000-000000000011';
const activeTripId = '00000000-0000-4000-8000-000000000012';
const commuteId = '00000000-0000-4000-8000-000000000013';
const activeCommuteId = '00000000-0000-4000-8000-000000000014';

function journey(
  id: string,
  kind: ServerJourney['kind'],
  status: ServerJourney['status'],
  startedAt: string,
): ServerJourney {
  return {
    id,
    kind,
    status,
    startedAt,
    completedAt: status === 'completed' ? '2026-09-12T11:00:00.000000Z' : null,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (failure: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

function openHistory() {
  fireEvent.click(screen.getByText('Account journey history'));
}

beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

describe('JourneyHistory', () => {
  it('loads after the Strict Mode effect replay', async () => {
    vi.mocked(readBrowserJourneyPage).mockResolvedValue({
      journeys: [journey(activeTripId, 'trip', 'active', '2026-09-09T09:00:00.000000Z')],
      next: null,
    });
    render(
      <StrictMode>
        <JourneyHistory account={firstAccount} onOpenJournal={vi.fn()} />
      </StrictMode>,
    );

    openHistory();
    fireEvent.click(screen.getByRole('button', { name: 'Load account history' }));
    expect(await screen.findByText('Trip · Active')).toBeTruthy();
    expect(readBrowserJourneyPage).toHaveBeenCalledOnce();
  });

  it('loads only on request, preserves a page on failure, and retries the requested cursor', async () => {
    const cursor: JourneyHistoryCursor = {
      startedAt: '2026-09-10T09:00:00.000000Z',
      id: tripId,
    };
    const latest: JourneyHistoryPage = {
      journeys: [journey(tripId, 'trip', 'completed', '2026-09-10T09:00:00.000000Z')],
      next: cursor,
    };
    const earlier: JourneyHistoryPage = {
      journeys: [journey(commuteId, 'commute', 'completed', '2026-08-10T09:00:00.000000Z')],
      next: null,
    };
    vi.mocked(readBrowserJourneyPage)
      .mockResolvedValueOnce(latest)
      .mockRejectedValueOnce(new Error('Synthetic offline failure'))
      .mockResolvedValueOnce(earlier)
      .mockResolvedValueOnce(latest);

    render(<JourneyHistory account={firstAccount} onOpenJournal={vi.fn()} />);
    expect(readBrowserJourneyPage).not.toHaveBeenCalled();
    openHistory();

    fireEvent.click(screen.getByRole('button', { name: 'Load account history' }));
    expect(await screen.findByText('Trip · Completed')).toBeTruthy();
    expect(readBrowserJourneyPage).toHaveBeenNthCalledWith(
      1,
      firstAccount,
      null,
      expect.anything(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Earlier journeys' }));
    expect((await screen.findByRole('alert')).textContent).toContain(
      'Account history is unavailable',
    );
    expect(screen.getByText('Trip · Completed')).toBeTruthy();
    expect(readBrowserJourneyPage).toHaveBeenNthCalledWith(
      2,
      firstAccount,
      cursor,
      expect.anything(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Retry account history' }));
    expect(await screen.findByText('Commute · Completed')).toBeTruthy();
    expect(screen.queryByText('Trip · Completed')).toBeNull();
    expect(readBrowserJourneyPage).toHaveBeenNthCalledWith(
      3,
      firstAccount,
      cursor,
      expect.anything(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Latest journeys' }));
    expect(await screen.findByText('Trip · Completed')).toBeTruthy();
    expect(readBrowserJourneyPage).toHaveBeenNthCalledWith(
      4,
      firstAccount,
      null,
      expect.anything(),
    );
  });

  it('renders a successful empty page without requesting a missing next page', async () => {
    vi.mocked(readBrowserJourneyPage).mockResolvedValue({ journeys: [], next: null });
    render(<JourneyHistory account={firstAccount} onOpenJournal={vi.fn()} />);

    expect(readBrowserJourneyPage).not.toHaveBeenCalled();
    openHistory();
    fireEvent.click(screen.getByRole('button', { name: 'Load account history' }));
    expect(await screen.findByText('No journeys are saved in your account history.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Earlier journeys' })).toBeNull();
    expect(readBrowserJourneyPage).toHaveBeenCalledOnce();
  });

  it('clears a displayed private page after an authentication failure', async () => {
    const cursor: JourneyHistoryCursor = {
      startedAt: '2026-09-10T09:00:00.000000Z',
      id: tripId,
    };
    vi.mocked(readBrowserJourneyPage)
      .mockResolvedValueOnce({
        journeys: [journey(tripId, 'trip', 'completed', cursor.startedAt)],
        next: cursor,
      })
      .mockRejectedValueOnce(Object.assign(new Error('Signed out'), { status: 401 }))
      .mockResolvedValueOnce({ journeys: [], next: null });
    render(<JourneyHistory account={firstAccount} onOpenJournal={vi.fn()} />);

    openHistory();
    fireEvent.click(screen.getByRole('button', { name: 'Load account history' }));
    expect(await screen.findByText('Trip · Completed')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Earlier journeys' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Sign in again');
    expect(screen.queryByText('Trip · Completed')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Open journal' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again after signing in' }));
    expect(readBrowserJourneyPage).toHaveBeenLastCalledWith(firstAccount, null, expect.anything());
  });

  it('resets on account change and ignores the prior account response', async () => {
    const first = deferred<JourneyHistoryPage>();
    const second = deferred<JourneyHistoryPage>();
    vi.mocked(readBrowserJourneyPage).mockImplementation((account) =>
      account === firstAccount ? first.promise : second.promise,
    );
    const view = render(<JourneyHistory account={firstAccount} onOpenJournal={vi.fn()} />);

    openHistory();
    fireEvent.click(screen.getByRole('button', { name: 'Load account history' }));
    await waitFor(() => expect(readBrowserJourneyPage).toHaveBeenCalledOnce());
    const firstSignal = vi.mocked(readBrowserJourneyPage).mock.calls[0]![2];
    view.rerender(<JourneyHistory account={secondAccount} onOpenJournal={vi.fn()} />);
    expect(firstSignal?.aborted).toBe(true);
    openHistory();
    expect(screen.getByRole('button', { name: 'Load account history' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Load account history' }));
    await act(async () => {
      second.resolve({
        journeys: [journey(activeCommuteId, 'commute', 'active', '2026-09-11T09:00:00.000000Z')],
        next: null,
      });
    });
    expect(await screen.findByText('Commute · Active')).toBeTruthy();

    await act(async () => {
      first.resolve({
        journeys: [journey(tripId, 'trip', 'completed', '2026-09-10T09:00:00.000000Z')],
        next: null,
      });
    });
    expect(screen.getByText('Commute · Active')).toBeTruthy();
    expect(screen.queryByText('Trip · Completed')).toBeNull();
  });

  it('opens journals only for completed trips and does not display record identifiers', async () => {
    const onOpenJournal = vi.fn();
    vi.mocked(readBrowserJourneyPage).mockResolvedValue({
      journeys: [
        journey(tripId, 'trip', 'completed', '2026-09-10T09:00:00.000000Z'),
        journey(activeTripId, 'trip', 'active', '2026-09-09T09:00:00.000000Z'),
        journey(commuteId, 'commute', 'completed', '2026-09-08T09:00:00.000000Z'),
        journey(activeCommuteId, 'commute', 'active', '2026-09-07T09:00:00.000000Z'),
      ],
      next: null,
    });
    render(<JourneyHistory account={firstAccount} onOpenJournal={onOpenJournal} />);

    openHistory();
    fireEvent.click(screen.getByRole('button', { name: 'Load account history' }));
    const open = await screen.findByRole('button', { name: 'Open journal' });
    expect(screen.getAllByRole('button', { name: 'Open journal' })).toHaveLength(1);
    fireEvent.click(open);
    expect(onOpenJournal).toHaveBeenCalledWith(tripId);
    expect(screen.queryByText(tripId)).toBeNull();
    expect(screen.queryByText(activeTripId)).toBeNull();
    expect(screen.queryByText(commuteId)).toBeNull();
    expect(screen.queryByText(activeCommuteId)).toBeNull();
  });
});
