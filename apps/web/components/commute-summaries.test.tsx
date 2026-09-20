// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JourneySnapshots } from '@routiqo/shared';
import { CommuteSummaries } from './commute-summaries';

const account = '00000000-0000-4000-8000-000000000001';
const empty: JourneySnapshots = { version: 1, accountId: account, journeys: [] };

describe('CommuteSummaries component', () => {
  let mockBrowserZone: string = 'UTC';
  let shouldThrowResolvedOptions = false;
  let visibility: DocumentVisibilityState = 'visible';
  const originalResolvedOptions = Intl.DateTimeFormat.prototype.resolvedOptions;

  beforeEach(() => {
    mockBrowserZone = 'UTC';
    shouldThrowResolvedOptions = false;
    visibility = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
    vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockImplementation(function (
      this: Intl.DateTimeFormat,
    ) {
      if (shouldThrowResolvedOptions) {
        throw new Error('Timezone resolution unavailable');
      }
      const options = originalResolvedOptions.call(this);
      return {
        ...options,
        timeZone: mockBrowserZone,
      };
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('shows a partial-history disclosure and an empty state without invented metrics', async () => {
    render(<CommuteSummaries account={account} snapshots={empty} />);
    expect(await screen.findByText(/Complete a commute/)).toBeTruthy();
    expect(
      screen.getByText(/Older journeys and records from other devices may be missing/),
    ).toBeTruthy();
  });

  it('renders confirmed elapsed time and fails closed for another account', async () => {
    const snapshots: JourneySnapshots = {
      ...empty,
      journeys: [
        {
          id: '00000000-0000-4000-8000-000000000002',
          kind: 'commute',
          status: 'completed',
          startedAt: '2026-09-15T12:00:00.000000Z',
          completedAt: '2026-09-15T12:25:00.000000Z',
        },
      ],
    };
    const view = render(<CommuteSummaries account={account} snapshots={snapshots} />);
    expect(await screen.findByText('1 confirmed commute · 25 recorded minutes')).toBeTruthy();
    view.rerender(
      <CommuteSummaries account="00000000-0000-4000-8000-000000000003" snapshots={snapshots} />,
    );
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText('1 confirmed commute · 25 recorded minutes')).toBeNull();
  });

  it('refreshes timezone and month grouping on tab resume across month boundaries', async () => {
    // Journey at 00:30 UTC March 1, 2026:
    // In UTC -> March 2026
    // In America/Los_Angeles (UTC-8) -> Feb 28 2026 16:30 -> February 2026
    const boundarySnapshots: JourneySnapshots = {
      ...empty,
      journeys: [
        {
          id: '00000000-0000-4000-8000-000000000004',
          kind: 'commute',
          status: 'completed',
          startedAt: '2026-03-01T00:30:00.000000Z',
          completedAt: '2026-03-01T01:30:00.000000Z',
        },
      ],
    };

    render(<CommuteSummaries account={account} snapshots={boundarySnapshots} />);

    // Initially in UTC
    expect(await screen.findByText(/Grouped by start month · UTC/)).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'March 2026' })).toBeTruthy();
    expect(screen.getByText(/1 confirmed commute · 60 recorded minutes/)).toBeTruthy();

    // Emulate device timezone change while backgrounded
    mockBrowserZone = 'America/Los_Angeles';

    // Dispatch visibilitychange while visible
    visibility = 'visible';
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    // Month heading changed to February 2026, zone updated, count and minutes unchanged
    expect(screen.getByText(/Grouped by start month · America\/Los_Angeles/)).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'February 2026' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'March 2026' })).toBeNull();
    expect(screen.getByText(/1 confirmed commute · 60 recorded minutes/)).toBeTruthy();
  });

  it('refreshes timezone on window focus while visible', async () => {
    const boundarySnapshots: JourneySnapshots = {
      ...empty,
      journeys: [
        {
          id: '00000000-0000-4000-8000-000000000004',
          kind: 'commute',
          status: 'completed',
          startedAt: '2026-03-01T00:30:00.000000Z',
          completedAt: '2026-03-01T01:30:00.000000Z',
        },
      ],
    };

    mockBrowserZone = 'America/Los_Angeles';
    render(<CommuteSummaries account={account} snapshots={boundarySnapshots} />);

    expect(await screen.findByText(/Grouped by start month · America\/Los_Angeles/)).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'February 2026' })).toBeTruthy();

    // Change zone back to UTC and trigger focus
    mockBrowserZone = 'UTC';
    visibility = 'visible';
    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    expect(screen.getByText(/Grouped by start month · UTC/)).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'March 2026' })).toBeTruthy();
  });

  it('handles same-zone events without altering state or failing', async () => {
    const snapshots: JourneySnapshots = {
      ...empty,
      journeys: [
        {
          id: '00000000-0000-4000-8000-000000000005',
          kind: 'commute',
          status: 'completed',
          startedAt: '2026-09-15T12:00:00.000000Z',
          completedAt: '2026-09-15T12:25:00.000000Z',
        },
      ],
    };

    render(<CommuteSummaries account={account} snapshots={snapshots} />);
    expect(await screen.findByText('1 confirmed commute · 25 recorded minutes')).toBeTruthy();

    // Dispatch visibilitychange with the same zone
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(screen.getByText('1 confirmed commute · 25 recorded minutes')).toBeTruthy();

    // Dispatch when hidden should not refresh
    visibility = 'hidden';
    mockBrowserZone = 'Asia/Tokyo';
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    // Still shows UTC because document was hidden
    expect(screen.getByText(/Grouped by start month · UTC/)).toBeTruthy();
  });

  it('falls back to unavailable alert on invalid zone and recovers when valid', async () => {
    const snapshots: JourneySnapshots = {
      ...empty,
      journeys: [
        {
          id: '00000000-0000-4000-8000-000000000006',
          kind: 'commute',
          status: 'completed',
          startedAt: '2026-09-15T12:00:00.000000Z',
          completedAt: '2026-09-15T12:25:00.000000Z',
        },
      ],
    };

    render(<CommuteSummaries account={account} snapshots={snapshots} />);
    expect(await screen.findByText('1 confirmed commute · 25 recorded minutes')).toBeTruthy();

    // Simulate timezone error
    shouldThrowResolvedOptions = true;
    visibility = 'visible';
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    // Renders error alert
    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.queryByText('1 confirmed commute · 25 recorded minutes')).toBeNull();

    // Recover with valid zone
    shouldThrowResolvedOptions = false;
    mockBrowserZone = 'UTC';
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('1 confirmed commute · 25 recorded minutes')).toBeTruthy();
  });

  it('cleans up event listeners on unmount', () => {
    const removeDocSpy = vi.spyOn(document, 'removeEventListener');
    const removeWinSpy = vi.spyOn(window, 'removeEventListener');

    const { unmount } = render(<CommuteSummaries account={account} snapshots={empty} />);
    unmount();

    expect(removeDocSpy).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
    expect(removeWinSpy).toHaveBeenCalledWith('focus', expect.any(Function));
  });
});
