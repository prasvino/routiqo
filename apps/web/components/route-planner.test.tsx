// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { RoutePlanner } from './route-planner';
vi.mock('./route-map', () => ({ RouteMap: () => <div>Map component</div> }));
import { readBrowserLocation } from '../lib/browser-location';
vi.mock('../lib/browser-location', async (original) => ({
  ...(await original<typeof import('../lib/browser-location')>()),
  readBrowserLocation: vi.fn(),
}));
import {
  calculateBrowserRoute,
  searchBrowserPlaces,
  BrowserRoutingError,
} from '../lib/browser-routing';
vi.mock('../lib/browser-routing', async (original) => ({
  ...(await original<typeof import('../lib/browser-routing')>()),
  calculateBrowserRoute: vi.fn(),
  searchBrowserPlaces: vi.fn(),
}));
const account = '00000000-0000-4000-8000-000000000001';
const place = (label: string, longitude: number) => ({
  provider: 'mapbox' as const,
  attribution: 'Synthetic attribution',
  places: [{ id: label, label, coordinate: [longitude, 13] as [number, number] }],
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});
function open(accountId = account) {
  const view = render(<RoutePlanner account={accountId} />);
  fireEvent.click(screen.getByText('Plan a route'));
  // jsdom does not implement the native summary toggle.
  screen.getByText('Plan a route').closest('details')!.open = true;
  return view;
}
it('requires explicit selection and discards estimates after endpoints change', async () => {
  vi.mocked(searchBrowserPlaces)
    .mockResolvedValueOnce(place('Starting town', 80))
    .mockResolvedValueOnce(place('Ending town', 79));
  vi.mocked(calculateBrowserRoute).mockResolvedValue({
    provider: 'mapbox',
    calculatedAt: '2026-09-10T03:00:00Z',
    routes: [
      {
        distanceMetres: 1200,
        durationSeconds: 600,
        geometry: [
          [80, 13],
          [79, 13],
        ],
      },
    ],
  });
  open();
  fireEvent.change(screen.getByLabelText('From'), { target: { value: 'Starting' } });
  expect(searchBrowserPlaces).not.toHaveBeenCalled();
  expect(
    (screen.getByRole('button', { name: 'Calculate route' }) as HTMLButtonElement).disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'Find starting place' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Starting town' }));
  fireEvent.change(screen.getByLabelText('To'), { target: { value: 'Ending' } });
  fireEvent.click(screen.getByRole('button', { name: 'Find destination' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Ending town' }));
  fireEvent.click(screen.getByRole('button', { name: 'Calculate route' }));
  await screen.findByText('1.2 km');
  expect(calculateBrowserRoute).toHaveBeenCalledWith(
    account,
    { mode: 'driving', origin: [80, 13], destination: [79, 13] },
    expect.any(AbortSignal),
  );
  expect(screen.getAllByText('Synthetic attribution')).toHaveLength(2);
  fireEvent.change(screen.getByLabelText('From'), { target: { value: 'Another' } });
  expect(screen.queryByText('1.2 km')).toBeNull();
  expect(
    (screen.getByRole('button', { name: 'Calculate route' }) as HTMLButtonElement).disabled,
  ).toBe(true);
});
it('ignores late search results after the query changes', async () => {
  let resolve!: (value: ReturnType<typeof place>) => void;
  vi.mocked(searchBrowserPlaces).mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  open();
  fireEvent.change(screen.getByLabelText('From'), { target: { value: 'Starting' } });
  fireEvent.click(screen.getByRole('button', { name: 'Find starting place' }));
  fireEvent.change(screen.getByLabelText('From'), { target: { value: 'Changed' } });
  resolve(place('Stale town', 80));
  await waitFor(() => expect(searchBrowserPlaces).toHaveBeenCalledOnce());
  expect(screen.queryByRole('button', { name: 'Stale town' })).toBeNull();
});
it('does not send requests offline and clears private fields on account change', async () => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
  const view = open();
  fireEvent.change(screen.getByLabelText('From'), { target: { value: 'Private town' } });
  fireEvent.click(screen.getByRole('button', { name: 'Find starting place' }));
  await screen.findByText(/You’re offline/);
  expect(searchBrowserPlaces).not.toHaveBeenCalled();
  view.rerender(<RoutePlanner account="00000000-0000-4000-8000-000000000002" />);
  expect((screen.getByLabelText('From') as HTMLInputElement).value).toBe('');
});
it('distinguishes no matches from throttling without showing provider errors', async () => {
  vi.mocked(searchBrowserPlaces).mockResolvedValueOnce({
    provider: 'mapbox',
    attribution: 'Synthetic attribution',
    places: [],
  });
  open();
  fireEvent.change(screen.getByLabelText('From'), { target: { value: 'Unmatched' } });
  fireEvent.click(screen.getByRole('button', { name: 'Find starting place' }));
  await screen.findByText('No matching places. Try a nearby street or city.');
  vi.mocked(searchBrowserPlaces).mockRejectedValueOnce(new BrowserRoutingError(429));
  fireEvent.click(screen.getByRole('button', { name: 'Find starting place' }));
  await screen.findByText('Too many requests. Wait a minute and try again.');
  expect(calculateBrowserRoute).not.toHaveBeenCalled();
});
it('requests location only on an explicit click and does not calculate automatically', async () => {
  vi.mocked(readBrowserLocation).mockResolvedValue({ coordinate: [80, 13], accuracyMetres: 20 });
  open();
  expect(readBrowserLocation).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Use my current location' }));
  await screen.findByText('Selected: My current location');
  expect(readBrowserLocation).toHaveBeenCalledOnce();
  expect(searchBrowserPlaces).not.toHaveBeenCalled();
  expect(calculateBrowserRoute).not.toHaveBeenCalled();
});

it('allows an explicit local position reading offline without searching or calculating', async () => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
  vi.mocked(readBrowserLocation).mockResolvedValue({ coordinate: [80, 13], accuracyMetres: 20 });
  open();
  fireEvent.click(screen.getByRole('button', { name: 'Use my current location' }));
  await screen.findByText('Selected: My current location');
  expect(calculateBrowserRoute).not.toHaveBeenCalled();
  expect(searchBrowserPlaces).not.toHaveBeenCalled();
});

it('retains loaded directions through connection loss and failed recalculation but clears on auth failure', async () => {
  const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
  vi.mocked(searchBrowserPlaces)
    .mockResolvedValueOnce(place('Start', 80))
    .mockResolvedValueOnce(place('End', 79));
  vi.mocked(calculateBrowserRoute).mockResolvedValueOnce({
    provider: 'mapbox',
    calculatedAt: '2026-09-12T04:00:00Z',
    routes: [
      {
        distanceMetres: 1200,
        durationSeconds: 600,
        geometry: [
          [80, 13],
          [79, 13],
        ],
        steps: [
          {
            instruction: 'Continue west',
            distanceMetres: 1200,
            durationSeconds: 600,
            location: [80, 13],
          },
        ],
      },
    ],
  });
  open();
  fireEvent.change(screen.getByLabelText('From'), { target: { value: 'Start' } });
  fireEvent.click(screen.getByRole('button', { name: 'Find starting place' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Start' }));
  fireEvent.change(screen.getByLabelText('To'), { target: { value: 'End' } });
  fireEvent.click(screen.getByRole('button', { name: 'Find destination' }));
  fireEvent.click(await screen.findByRole('button', { name: 'End' }));
  fireEvent.click(screen.getByRole('button', { name: 'Calculate route' }));
  await screen.findByText('1.2 km');
  online.mockReturnValue(false);
  act(() => window.dispatchEvent(new Event('offline')));
  expect(screen.getByText(/You’re offline. Loaded directions/)).toBeTruthy();
  expect(
    (screen.getByRole('button', { name: 'Calculate route' }) as HTMLButtonElement).disabled,
  ).toBe(true);
  expect(screen.getAllByText(/Continue west/).length).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole('button', { name: 'Calculate route' }));
  expect(calculateBrowserRoute).toHaveBeenCalledOnce();
  online.mockReturnValue(true);
  act(() => window.dispatchEvent(new Event('online')));
  expect(calculateBrowserRoute).toHaveBeenCalledOnce();
  vi.mocked(calculateBrowserRoute).mockRejectedValueOnce(new BrowserRoutingError(503));
  fireEvent.click(screen.getByRole('button', { name: 'Calculate route' }));
  await screen.findByText('Route planning is unavailable. Try again later.');
  expect(screen.getByText(/Showing the last successful route/)).toBeTruthy();
  expect(screen.getByText('1.2 km')).toBeTruthy();
  vi.mocked(calculateBrowserRoute).mockRejectedValueOnce(new BrowserRoutingError(401));
  fireEvent.click(screen.getByRole('button', { name: 'Calculate route' }));
  await screen.findByText('Sign in again from Profile to continue.');
  expect(screen.queryByText('1.2 km')).toBeNull();
  expect((screen.getByLabelText('From') as HTMLInputElement).value).toBe('');
});
