// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JourneyPlan } from '@routiqo/shared';
import { Trips } from './trips';
import { usePlanning } from './planning-provider';
import { useLocalClock } from './use-local-clock';

vi.mock('./journey-workspace', () => ({
  JourneyWorkspace: () => null,
}));

vi.mock('./planning-provider', () => ({
  usePlanning: vi.fn(),
}));

vi.mock('./use-local-clock', () => ({
  useLocalClock: vi.fn(),
}));

const planA: JourneyPlan = {
  id: 'plan-a',
  kind: 'trip',
  origin: 'Chennai',
  destination: 'Pondicherry',
  date: '2026-09-25',
  time: '07:00',
  days: [],
  notes: 'Morning coastal drive',
  createdAt: '2026-09-20T00:00:00.000Z',
};

const planB: JourneyPlan = {
  id: 'plan-b',
  kind: 'trip',
  origin: 'Bengaluru',
  destination: 'Mysuru',
  date: '2026-09-26',
  time: '08:00',
  days: [],
  notes: 'Palace visit',
  createdAt: '2026-09-20T00:00:00.000Z',
};

function createPlanningMock(overrides?: Partial<ReturnType<typeof usePlanning>>) {
  return {
    state: { version: 1 as const, plans: [planA, planB], saved: [] },
    ready: true,
    error: '',
    message: '',
    savePlan: vi.fn(),
    removePlan: vi.fn(),
    saveDestination: vi.fn(),
    clear: vi.fn(() => true),
    exportBackup: vi.fn(() => ''),
    restoreBackup: vi.fn(() => ({ addedPlans: 0, keptPlans: 0, addedPlaces: 0 })),
    ...overrides,
  };
}

describe('Trips plan removal error lifecycle', () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    });
    vi.mocked(useLocalClock).mockReturnValue(new Date('2026-09-20T08:00:00.000Z'));
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('fails removal for plan A, cancels with Keep plan, and opening plan B has no stale error and no extra removePlan call', () => {
    const removePlan = vi.fn().mockImplementation((id: string) => {
      if (id === 'plan-a') {
        throw new Error('Local storage write rejected.');
      }
    });

    vi.mocked(usePlanning).mockReturnValue(createPlanningMock({ removePlan }));

    render(<Trips />);

    // Open removal for Plan A
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan to Pondicherry' }));
    expect(
      screen.getByText(/Chennai → Pondicherry will be removed from this device/i),
    ).toBeTruthy();

    // Trigger removal -> throws
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan' }));
    expect(removePlan).toHaveBeenCalledWith('plan-a');

    // Dialog stays open and displays error alert
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toBe('Local storage write rejected.');
    expect(
      screen.getByText(/Chennai → Pondicherry will be removed from this device/i),
    ).toBeTruthy();

    // Click "Keep plan" to cancel
    fireEvent.click(screen.getByRole('button', { name: 'Keep plan' }));
    expect(screen.queryByRole('dialog', { name: 'Remove this plan?' })).toBeNull();

    // Open removal for Plan B
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan to Mysuru' }));
    expect(screen.getByText(/Bengaluru → Mysuru will be removed from this device/i)).toBeTruthy();

    // Stale error from Plan A must NOT be displayed
    expect(screen.queryByRole('alert')).toBeNull();

    // removePlan should have been called only once for plan A, not for plan B
    expect(removePlan).toHaveBeenCalledTimes(1);
    expect(removePlan).not.toHaveBeenCalledWith('plan-b');
  });

  it('recovers from failed removal on successful retry and clears error upon closing', () => {
    let shouldFail = true;
    const removePlan = vi.fn().mockImplementation(() => {
      if (shouldFail) {
        throw new Error('Storage busy, try again.');
      }
    });

    vi.mocked(usePlanning).mockReturnValue(createPlanningMock({ removePlan }));

    render(<Trips />);

    // Open removal for Plan A
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan to Pondicherry' }));

    // First attempt fails
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan' }));
    expect(screen.getByRole('alert').textContent).toBe('Storage busy, try again.');

    // Second attempt succeeds
    shouldFail = false;
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan' }));

    // Dialog closes
    expect(screen.queryByRole('dialog', { name: 'Remove this plan?' })).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('clears removal error when closing via modal close button and does not show old error on reopen', () => {
    const removePlan = vi.fn().mockImplementation(() => {
      throw new Error('Failed to delete.');
    });

    vi.mocked(usePlanning).mockReturnValue(createPlanningMock({ removePlan }));

    render(<Trips />);

    // Open removal for Plan A
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan to Pondicherry' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan' }));
    expect(screen.getByRole('alert').textContent).toBe('Failed to delete.');

    // Close via close icon button
    fireEvent.click(screen.getByRole('button', { name: 'Close dialog' }));
    expect(screen.queryByRole('dialog', { name: 'Remove this plan?' })).toBeNull();

    // Reopen removal for Plan A
    fireEvent.click(screen.getByRole('button', { name: 'Remove plan to Pondicherry' }));

    // Alert must not be present
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
