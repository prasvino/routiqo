// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocalClock } from './use-local-clock';

function ClockHarness() {
  const clock = useLocalClock();
  return <div data-testid="clock">{clock ? clock.toISOString() : 'null'}</div>;
}

describe('useLocalClock', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('initializes to null on initial render and populates on mount', () => {
    vi.setSystemTime(new Date('2026-09-20T12:00:45.000Z'));
    render(<ClockHarness />);
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:00:45.000Z');
  });

  it('mount at :45 refreshes at :00 minute boundary with no extra ticks', () => {
    vi.setSystemTime(new Date('2026-09-20T12:00:45.000Z'));
    render(<ClockHarness />);
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:00:45.000Z');

    // 14,999ms later - not yet at minute boundary
    act(() => {
      vi.advanceTimersByTime(14_999);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:00:45.000Z');

    // 1ms later - reaches 12:01:00.000Z
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:01:00.000Z');

    // 59,999ms later - not yet at next minute boundary
    act(() => {
      vi.advanceTimersByTime(59_999);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:01:00.000Z');

    // 1ms later - reaches 12:02:00.000Z
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:02:00.000Z');
  });

  it('suspends timer while hidden and refreshes immediately on resume', () => {
    vi.setSystemTime(new Date('2026-09-20T12:00:10.000Z'));
    let visibility: DocumentVisibilityState = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);

    render(<ClockHarness />);
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:00:10.000Z');
    expect(vi.getTimerCount()).toBe(1);

    // Tab is hidden
    visibility = 'hidden';
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(vi.getTimerCount()).toBe(0);

    // Advance past minute boundaries while hidden
    act(() => {
      vi.advanceTimersByTime(120_000); // 2 minutes later
    });
    // Should NOT have updated while hidden
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:00:10.000Z');
    expect(vi.getTimerCount()).toBe(0);

    // Tab becomes visible again
    visibility = 'visible';
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    // Immediately updates to current wall clock time (12:02:10)
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:02:10.000Z');
    expect(vi.getTimerCount()).toBe(1);

    // Next tick should align to 12:03:00.000Z (50,000ms later)
    act(() => {
      vi.advanceTimersByTime(49_999);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:02:10.000Z');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:03:00.000Z');
    expect(vi.getTimerCount()).toBe(1);
  });

  it('cleans up its timer and cannot restart on visibility changes after unmount', () => {
    vi.setSystemTime(new Date('2026-09-20T12:00:30.000Z'));
    const { unmount } = render(<ClockHarness />);
    expect(vi.getTimerCount()).toBe(1);

    unmount();
    expect(vi.getTimerCount()).toBe(0);
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(vi.getTimerCount()).toBe(0);
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it('handles StrictMode replay without leaving duplicate timers', () => {
    vi.setSystemTime(new Date('2026-09-20T12:00:40.000Z'));
    const { unmount } = render(
      <StrictMode>
        <ClockHarness />
      </StrictMode>,
    );
    expect(vi.getTimerCount()).toBe(1);

    act(() => {
      vi.advanceTimersByTime(20_000);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-09-20T12:01:00.000Z');
    expect(vi.getTimerCount()).toBe(1);

    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(vi.getTimerCount()).toBe(1);

    unmount();
    expect(vi.getTimerCount()).toBe(0);
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(vi.getTimerCount()).toBe(0);
  });
});
