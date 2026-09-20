// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dayLabels } from '@routiqo/shared';
import { PlanDialog } from './plan-dialog';
import { usePlanning } from './planning-provider';

vi.mock('./planning-provider', () => ({
  usePlanning: vi.fn(),
}));

function createPlanningMock(overrides?: Partial<ReturnType<typeof usePlanning>>) {
  return {
    state: { version: 1 as const, plans: [], saved: [] },
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

describe('PlanDialog commute weekday validation and feedback', () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('shows inline weekday error, marks fieldset invalid, and focuses first day button when submitting commute with no days', () => {
    const savePlan = vi.fn();
    vi.mocked(usePlanning).mockReturnValue(createPlanningMock({ savePlan }));

    render(<PlanDialog destination="Ooty" onClose={vi.fn()} />);

    // Switch to commute
    fireEvent.click(screen.getByRole('button', { name: /Daily commute/i }));

    const fieldset = screen.getByRole('group', { name: 'Repeat on' });
    expect(fieldset).toBeTruthy();

    // Deselect all active weekdays (default is Mon-Fri: indices 1, 2, 3, 4, 5)
    const dayButtons = within(fieldset).getAllByRole('button');
    for (const btn of dayButtons) {
      if (btn.getAttribute('aria-pressed') === 'true') {
        fireEvent.click(btn);
      }
    }

    // All weekday buttons should now be unpressed
    for (const btn of dayButtons) {
      expect(btn.getAttribute('aria-pressed')).toBe('false');
    }

    // Submit form
    fireEvent.click(screen.getByRole('button', { name: /Save journey plan/i }));

    // savePlan must NOT be called
    expect(savePlan).not.toHaveBeenCalled();

    // Inline error displayed
    const inlineError = within(fieldset).getByRole('alert');
    expect(inlineError.textContent).toBe('Choose at least one day for this commute.');

    // Fieldset linked via aria-describedby and aria-invalid
    expect(fieldset.getAttribute('aria-describedby')).toBe(inlineError.id);
    expect(fieldset.getAttribute('aria-invalid')).toBe('true');

    // First weekday button (Sunday / first in dayLabels) has focus
    expect(document.activeElement).toBe(dayButtons[0]);
  });

  it('clears inline error when a day is selected and allows successful save', () => {
    const savePlan = vi.fn();
    const onClose = vi.fn();
    vi.mocked(usePlanning).mockReturnValue(createPlanningMock({ savePlan }));

    render(<PlanDialog destination="Ooty" onClose={onClose} />);

    // Switch to commute
    fireEvent.click(screen.getByRole('button', { name: /Daily commute/i }));

    const fieldset = screen.getByRole('group', { name: 'Repeat on' });
    const dayButtons = within(fieldset).getAllByRole('button');

    // Deselect all days
    for (const btn of dayButtons) {
      if (btn.getAttribute('aria-pressed') === 'true') {
        fireEvent.click(btn);
      }
    }

    // Submit to trigger error
    fireEvent.click(screen.getByRole('button', { name: /Save journey plan/i }));
    expect(within(fieldset).getByRole('alert')).toBeTruthy();

    // Select Monday (index 1)
    const mondayBtn = within(fieldset).getByRole('button', { name: dayLabels[1] });
    fireEvent.click(mondayBtn);

    // Error is cleared immediately
    expect(within(fieldset).queryByRole('alert')).toBeNull();
    expect(fieldset.hasAttribute('aria-describedby')).toBe(false);
    expect(fieldset.hasAttribute('aria-invalid')).toBe(false);

    // Now submit succeeds
    fireEvent.click(screen.getByRole('button', { name: /Save journey plan/i }));
    expect(savePlan).toHaveBeenCalledTimes(1);
    expect(savePlan).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'commute',
        days: [1],
      }),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('clears inline weekday error when switching to trip and saves with days: []', () => {
    const savePlan = vi.fn();
    const onClose = vi.fn();
    vi.mocked(usePlanning).mockReturnValue(createPlanningMock({ savePlan }));

    render(<PlanDialog destination="Ooty" onClose={onClose} />);

    // Switch to commute
    fireEvent.click(screen.getByRole('button', { name: /Daily commute/i }));
    const fieldset = screen.getByRole('group', { name: 'Repeat on' });

    // Deselect all days
    for (const btn of within(fieldset).getAllByRole('button')) {
      if (btn.getAttribute('aria-pressed') === 'true') {
        fireEvent.click(btn);
      }
    }

    // Submit to trigger error
    fireEvent.click(screen.getByRole('button', { name: /Save journey plan/i }));
    expect(within(fieldset).getByRole('alert')).toBeTruthy();

    // Switch back to trip
    fireEvent.click(screen.getByRole('button', { name: /Trip \/ travel/i }));

    // Fieldset is removed and inline error is gone
    expect(screen.queryByRole('group', { name: 'Repeat on' })).toBeNull();

    // Submit saves as trip with empty days
    fireEvent.click(screen.getByRole('button', { name: /Save journey plan/i }));
    expect(savePlan).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'trip',
        days: [],
      }),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('preserves entered form values when storage fails with general error', () => {
    const savePlan = vi.fn().mockImplementation(() => {
      throw new Error('Device storage full');
    });
    vi.mocked(usePlanning).mockReturnValue(createPlanningMock({ savePlan }));

    render(<PlanDialog destination="Ooty" onClose={vi.fn()} />);

    const originInput = screen.getByRole('textbox', { name: /Starting from/i }) as HTMLInputElement;
    const destInput = screen.getByRole('combobox', { name: /Going to/i }) as HTMLInputElement;
    const notesInput = screen.getByRole('textbox', {
      name: /Anything to remember/i,
    }) as HTMLTextAreaElement;

    fireEvent.change(originInput, { target: { value: 'Bengaluru' } });
    fireEvent.change(destInput, { target: { value: 'Mysuru' } });
    fireEvent.change(notesInput, { target: { value: 'Early breakfast stop' } });

    fireEvent.click(screen.getByRole('button', { name: /Save journey plan/i }));

    // General error alert is shown
    expect(screen.getByRole('alert').textContent).toBe('Device storage full');

    // Values are retained
    expect(originInput.value).toBe('Bengaluru');
    expect(destInput.value).toBe('Mysuru');
    expect(notesInput.value).toBe('Early breakfast stop');
  });
});
