// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { RouteResult } from '@routiqo/shared';
import { RouteResults } from './route-results';
const lifecycle = vi.hoisted(() => ({ mount: vi.fn(), unmount: vi.fn() }));
vi.mock('./route-map', async () => {
  const { useEffect } = await import('react');
  return {
    RouteMap: () => {
      useEffect(() => {
        lifecycle.mount();
        return () => lifecycle.unmount();
      }, []);
      return <div>Map component</div>;
    },
  };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
const result: RouteResult = {
  provider: 'mapbox',
  calculatedAt: '2026-09-12T04:00:00Z',
  routes: [
    {
      distanceMetres: 1000,
      durationSeconds: 500,
      geometry: [
        [80, 13],
        [80.01, 13.01],
      ],
      steps: [
        {
          instruction: 'Head north',
          distanceMetres: 900,
          durationSeconds: 450,
          location: [80, 13],
        },
        {
          instruction: 'Arrive at destination',
          distanceMetres: 100,
          durationSeconds: 50,
          location: [80.01, 13.01],
        },
      ],
    },
    {
      distanceMetres: 1200,
      durationSeconds: 600,
      geometry: [
        [80, 13],
        [80.02, 13.01],
      ],
      steps: [
        {
          instruction: 'Take the eastern road',
          distanceMetres: 1200,
          durationSeconds: 600,
          location: [80, 13],
        },
      ],
    },
  ],
};
it('bounds manual step review and resets it when selecting an alternative', () => {
  render(<RouteResults result={result} />);
  expect(screen.getByRole('status').textContent).toContain('Head north');
  expect(
    (screen.getByRole('button', { name: 'Previous step' }) as HTMLButtonElement).disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'Next step' }));
  expect(screen.getByRole('status').textContent).toContain('Arrive at destination');
  expect((screen.getByRole('button', { name: 'Next step' }) as HTMLButtonElement).disabled).toBe(
    true,
  );
  fireEvent.click(screen.getByRole('button', { name: /Route 2/ }));
  expect(screen.getByRole('status').textContent).toContain('Step 1 of 1');
  expect(screen.getByRole('status').textContent).toContain('Take the eastern road');
  expect(lifecycle.mount).toHaveBeenCalledOnce();
  expect(lifecycle.unmount).not.toHaveBeenCalled();
});
it('does not invent directions for legacy route responses', () => {
  const first = result.routes[0]!;
  const legacy = {
    ...result,
    routes: [
      {
        geometry: first.geometry,
        distanceMetres: first.distanceMetres,
        durationSeconds: first.durationSeconds,
      },
    ],
  };
  render(<RouteResults result={legacy} />);
  expect(screen.getByText(/Turn instructions are unavailable/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Next step' })).toBeNull();
});
