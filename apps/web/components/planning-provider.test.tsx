// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningProvider, usePlanning } from './planning-provider';

function PlanningTestConsumer() {
  const { ready, error, message, state, saveDestination, clear } = usePlanning();
  return (
    <div>
      <div data-testid="ready">{ready ? 'ready' : 'loading'}</div>
      <div data-testid="error">{error}</div>
      <div data-testid="message">{message}</div>
      <div data-testid="saved-count">{state.saved.length}</div>
      <button
        onClick={() => {
          try {
            saveDestination('place-1');
          } catch {
            /* caught by caller */
          }
        }}
      >
        Save Place 1
      </button>
      <button
        onClick={() => {
          try {
            saveDestination('place-2');
          } catch {
            /* caught by caller */
          }
        }}
      >
        Save Place 2
      </button>
      <button onClick={() => clear()}>Clear Storage</button>
    </div>
  );
}

describe('PlanningProvider feedback recovery', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('clears stale success message when a subsequent save mutation fails, preserving previous state', () => {
    render(
      <PlanningProvider>
        <PlanningTestConsumer />
      </PlanningProvider>,
    );

    expect(screen.getByTestId('ready').textContent).toBe('ready');

    // Successful save
    fireEvent.click(screen.getByText('Save Place 1'));
    expect(screen.getByTestId('message').textContent).toBe('Saved places updated.');
    expect(screen.getByTestId('error').textContent).toBe('');
    expect(screen.getByTestId('saved-count').textContent).toBe('1');

    // Simulate storage failure on next mutation
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('Storage is full on this device.');
    });

    fireEvent.click(screen.getByText('Save Place 2'));

    // Stale success message MUST be cleared, error set, and saved state preserved
    expect(screen.getByTestId('message').textContent).toBe('');
    expect(screen.getByTestId('error').textContent).toBe('Storage is full on this device.');
    expect(screen.getByTestId('saved-count').textContent).toBe('1');

    // Recover with working storage
    setItemSpy.mockRestore();
    fireEvent.click(screen.getByText('Save Place 2'));

    expect(screen.getByTestId('message').textContent).toBe('Saved places updated.');
    expect(screen.getByTestId('error').textContent).toBe('');
    expect(screen.getByTestId('saved-count').textContent).toBe('2');
  });

  it('clears stale success message when clear fails', () => {
    render(
      <PlanningProvider>
        <PlanningTestConsumer />
      </PlanningProvider>,
    );

    fireEvent.click(screen.getByText('Save Place 1'));
    expect(screen.getByTestId('message').textContent).toBe('Saved places updated.');

    const removeItemSpy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('Denied');
    });

    fireEvent.click(screen.getByText('Clear Storage'));

    expect(screen.getByTestId('message').textContent).toBe('');
    expect(screen.getByTestId('error').textContent).toBe(
      'Your browser could not clear local data.',
    );
    expect(screen.getByTestId('saved-count').textContent).toBe('1');

    removeItemSpy.mockRestore();
  });

  it('clears stale success message when a storage event read fails', () => {
    render(
      <PlanningProvider>
        <PlanningTestConsumer />
      </PlanningProvider>,
    );

    fireEvent.click(screen.getByText('Save Place 1'));
    expect(screen.getByTestId('message').textContent).toBe('Saved places updated.');

    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Access denied');
    });

    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'routiqo.planning.v1' }));
    });

    expect(screen.getByTestId('message').textContent).toBe('');
    expect(screen.getByTestId('error').textContent).toBe('Access denied');
  });

  it('replaces browser storage exceptions with actionable copy on write and read', () => {
    render(
      <PlanningProvider>
        <PlanningTestConsumer />
      </PlanningProvider>,
    );
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Quota exceeded', 'QuotaExceededError');
    });
    fireEvent.click(screen.getByText('Save Place 1'));
    expect(screen.getByTestId('error').textContent).toBe(
      'Your changes couldn’t be saved on this device. Check that this site may store data and that your browser has space.',
    );
    expect(screen.getByTestId('saved-count').textContent).toBe('0');

    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Denied', 'SecurityError');
    });
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'routiqo.planning.v1' }));
    });
    expect(screen.getByTestId('error').textContent).toBe(
      'Saved plans can’t be read because browser storage is unavailable. Check that this site may store data.',
    );
  });

  it('keeps planning rule messages such as corrupt saved data', () => {
    localStorage.setItem('routiqo.planning.v1', '{not json');
    render(
      <PlanningProvider>
        <PlanningTestConsumer />
      </PlanningProvider>,
    );
    expect(screen.getByTestId('error').textContent).toMatch(/Saved (plans|data)/);
  });
});
