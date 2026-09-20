// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { destinations } from '@routiqo/shared';
import { DestinationDialog } from './destination-dialog';
import { usePlanning } from './planning-provider';

vi.mock('./planning-provider', () => ({
  usePlanning: vi.fn(),
}));

const mockPlace = destinations[0]!;

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

describe('DestinationDialog save error recovery', () => {
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

  it('renders role="alert" within dialog on save failure without optimistic update, and preserves dialog content', () => {
    const saveDestination = vi.fn().mockImplementation(() => {
      throw new Error('Storage quota exceeded on this device.');
    });

    vi.mocked(usePlanning).mockReturnValue(
      createPlanningMock({
        saveDestination,
      }),
    );

    const onClose = vi.fn();
    const onPlan = vi.fn();

    render(<DestinationDialog place={mockPlace} onClose={onClose} onPlan={onPlan} />);

    const dialog = screen.getByRole('dialog', { name: mockPlace.name });
    expect(dialog).toBeTruthy();

    const saveButton = screen.getByRole('button', { name: 'Save place' });
    expect(saveButton).toBeTruthy();

    // Click to save -> throws
    fireEvent.click(saveButton);

    expect(saveDestination).toHaveBeenCalledWith(mockPlace.id);

    // Dialog remains open and has role="alert"
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toBe('Storage quota exceeded on this device.');
    expect(dialog.contains(alert)).toBe(true);

    // Dialog content and actions remain preserved
    expect(screen.getByText(mockPlace.description)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Plan a journey/i })).toBeTruthy();
    // Button state is NOT optimistically updated to "Saved"
    expect(screen.getByRole('button', { name: 'Save place' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Saved' })).toBeNull();
  });

  it('clears error on retry and reflects confirmed provider state when save succeeds', () => {
    let savedList: string[] = [];
    let shouldFail = true;

    const saveDestination = vi.fn().mockImplementation((id: string) => {
      if (shouldFail) {
        throw new Error('Network timeout writing local storage.');
      }
      savedList = [...savedList, id];
    });

    vi.mocked(usePlanning).mockImplementation(() =>
      createPlanningMock({
        state: { version: 1, plans: [], saved: savedList },
        saveDestination,
      }),
    );

    const { rerender } = render(
      <DestinationDialog place={mockPlace} onClose={vi.fn()} onPlan={vi.fn()} />,
    );

    // Initial attempt fails
    fireEvent.click(screen.getByRole('button', { name: 'Save place' }));
    expect(screen.getByRole('alert').textContent).toBe('Network timeout writing local storage.');

    // Now retry succeeds
    shouldFail = false;
    fireEvent.click(screen.getByRole('button', { name: 'Save place' }));

    // Rerender with updated provider state
    rerender(<DestinationDialog place={mockPlace} onClose={vi.fn()} onPlan={vi.fn()} />);

    // Error alert is cleared
    expect(screen.queryByRole('alert')).toBeNull();
    // Button reflects confirmed saved state
    expect(screen.getByRole('button', { name: 'Saved' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Save place' })).toBeNull();
  });

  it('handles unsave failure by showing alert while retaining confirmed saved state', () => {
    const saveDestination = vi.fn().mockImplementation(() => {
      throw new Error('Failed to remove bookmark.');
    });

    vi.mocked(usePlanning).mockReturnValue(
      createPlanningMock({
        state: { version: 1, plans: [], saved: [mockPlace.id] },
        saveDestination,
      }),
    );

    render(<DestinationDialog place={mockPlace} onClose={vi.fn()} onPlan={vi.fn()} />);

    const savedButton = screen.getByRole('button', { name: 'Saved' });
    expect(savedButton).toBeTruthy();

    fireEvent.click(savedButton);

    expect(saveDestination).toHaveBeenCalledWith(mockPlace.id);
    expect(screen.getByRole('alert').textContent).toBe('Failed to remove bookmark.');
    // Button still shows Saved
    expect(screen.getByRole('button', { name: 'Saved' })).toBeTruthy();
  });

  it('uses safe fallback message when non-Error is thrown', () => {
    const saveDestination = vi.fn().mockImplementation(() => {
      throw 'unknown failure';
    });

    vi.mocked(usePlanning).mockReturnValue(
      createPlanningMock({
        saveDestination,
      }),
    );

    render(<DestinationDialog place={mockPlace} onClose={vi.fn()} onPlan={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'Save place' }));

    expect(screen.getByRole('alert').textContent).toBe(
      'This place could not be saved on this device. Try again.',
    );
  });

  it('disables bookmark action when planning state is not ready', () => {
    const saveDestination = vi.fn();

    vi.mocked(usePlanning).mockReturnValue(
      createPlanningMock({
        ready: false,
        saveDestination,
      }),
    );

    render(<DestinationDialog place={mockPlace} onClose={vi.fn()} onPlan={vi.fn()} />);

    const saveButton = screen.getByRole('button', { name: 'Save place' }) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(true);

    fireEvent.click(saveButton);
    expect(saveDestination).not.toHaveBeenCalled();
  });
});
