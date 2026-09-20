// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { categories, destinations, searchDestinations } from '@routiqo/shared';
import { Explore } from './explore';
import { PlanningProvider } from './planning-provider';

describe('Explore category filter accessibility and behavior', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  function renderExplore(initialQuery = '') {
    return render(
      <PlanningProvider>
        <Explore initialQuery={initialQuery} />
      </PlanningProvider>,
    );
  }

  it('exposes destination categories as a named control group with initial All selection', () => {
    renderExplore();

    const group = screen.getByRole('group', { name: 'Destination categories' });
    expect(group).toBeTruthy();

    const buttons = within(group).getAllByRole('button');
    expect(buttons).toHaveLength(categories.length);

    // Verify "All" button is pressed, others are not
    const allButton = within(group).getByRole('button', { name: 'All' });
    expect(allButton.getAttribute('aria-pressed')).toBe('true');

    for (const cat of categories) {
      if (cat !== 'All') {
        const catButton = within(group).getByRole('button', { name: cat });
        expect(catButton.getAttribute('aria-pressed')).toBe('false');
      }
    }

    // Verify initial count matches all destinations
    const status = screen.getByRole('status');
    expect(status.textContent).toContain(`${destinations.length} places`);
  });

  it('updates exactly one pressed state and visible results when selecting a category and restores collection with All', () => {
    renderExplore();

    const group = screen.getByRole('group', { name: 'Destination categories' });
    const coastButton = within(group).getByRole('button', { name: 'Coast' });
    const allButton = within(group).getByRole('button', { name: 'All' });

    // Select Coast category
    fireEvent.click(coastButton);

    expect(coastButton.getAttribute('aria-pressed')).toBe('true');
    expect(allButton.getAttribute('aria-pressed')).toBe('false');

    // Exactly one button has aria-pressed="true"
    const pressedButtons = within(group)
      .getAllByRole('button')
      .filter((btn) => btn.getAttribute('aria-pressed') === 'true');
    expect(pressedButtons).toHaveLength(1);
    expect(pressedButtons[0]).toBe(coastButton);

    // Verify count reflects Coast destinations
    const coastDestinations = searchDestinations('', 'Coast');
    const status = screen.getByRole('status');
    expect(status.textContent).toContain(`${coastDestinations.length} places`);

    // Select All category again
    fireEvent.click(allButton);

    expect(allButton.getAttribute('aria-pressed')).toBe('true');
    expect(coastButton.getAttribute('aria-pressed')).toBe('false');
    expect(status.textContent).toContain(`${destinations.length} places`);
  });

  it('resets query and category on Explore all when no matches found while keeping filter group named', () => {
    renderExplore();

    const group = screen.getByRole('group', { name: 'Destination categories' });
    const hillsButton = within(group).getByRole('button', { name: 'Hills' });
    const searchInput = screen.getByRole('textbox', { name: 'Search places' }) as HTMLInputElement;

    // Filter by Hills
    fireEvent.click(hillsButton);
    expect(hillsButton.getAttribute('aria-pressed')).toBe('true');

    // Type query with no matches
    fireEvent.change(searchInput, { target: { value: 'nonexistent-query-xyz' } });
    expect(searchInput.value).toBe('nonexistent-query-xyz');

    // Empty state should be visible
    expect(screen.getByRole('heading', { name: 'No places found.' })).toBeTruthy();

    // Click "Explore all" recovery button
    const exploreAllButton = screen.getByRole('button', { name: /Explore all/i });
    fireEvent.click(exploreAllButton);

    // Query and category are reset
    expect(searchInput.value).toBe('');
    const allButton = within(group).getByRole('button', { name: 'All' });
    expect(allButton.getAttribute('aria-pressed')).toBe('true');
    expect(hillsButton.getAttribute('aria-pressed')).toBe('false');

    // Group remains accessible and named
    expect(screen.getByRole('group', { name: 'Destination categories' })).toBe(group);

    // All results returned
    const status = screen.getByRole('status');
    expect(status.textContent).toContain(`${destinations.length} places`);
    expect(screen.queryByRole('heading', { name: 'No places found.' })).toBeNull();
  });
});
