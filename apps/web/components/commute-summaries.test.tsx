// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import type { JourneySnapshots } from '@routiqo/shared';
import { CommuteSummaries } from './commute-summaries';

const account = '00000000-0000-4000-8000-000000000001';
const empty: JourneySnapshots = { version: 1, accountId: account, journeys: [] };
afterEach(cleanup);

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
        startedAt: '2026-09-15T12:00:00Z',
        completedAt: '2026-09-15T12:25:00Z',
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
