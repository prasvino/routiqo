// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { TripJournal, TripJournalWrite } from '@routiqo/shared';
import { JournalEditor } from './journal-editor';
import {
  BrowserJournalError,
  readBrowserTripJournal,
  saveBrowserTripJournal,
} from '../lib/browser-journals';
import {
  acknowledgeBrowserJournalDraft,
  cacheBrowserTripJournal,
  readBrowserJournal,
  saveBrowserJournalDraft,
  type BrowserJournalDraft,
} from '../lib/journal-storage';

vi.mock('../lib/browser-journals', async (original) => ({
  ...(await original<typeof import('../lib/browser-journals')>()),
  readBrowserTripJournal: vi.fn(),
  saveBrowserTripJournal: vi.fn(),
}));
vi.mock('../lib/journal-storage');

const accountA = '00000000-0000-4000-8000-000000000001';
const accountB = '00000000-0000-4000-8000-000000000002';
const journeyId = '00000000-0000-4000-8000-000000000003';

function journal(title = '', notes = '', version = 0): TripJournal {
  return {
    journey: {
      id: journeyId,
      kind: 'trip',
      status: 'completed',
      startedAt: '2026-09-01T08:00:00.000Z',
      completedAt: '2026-09-01T10:00:00.000Z',
    },
    annotation: {
      title,
      notes,
      version,
      updatedAt: version === 0 ? null : '2026-09-01T10:01:00.000Z',
    },
  };
}

function stored(input: BrowserJournalDraft): BrowserJournalDraft {
  return { ...input };
}

beforeEach(() => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  });
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  });
  vi.mocked(readBrowserJournal).mockResolvedValue({ draft: null, journal: journal() });
  vi.mocked(readBrowserTripJournal).mockResolvedValue(journal());
  vi.mocked(cacheBrowserTripJournal).mockImplementation(
    async (_account, value) => value as TripJournal,
  );
  vi.mocked(saveBrowserJournalDraft).mockImplementation(async (_account, input) => stored(input));
  vi.mocked(acknowledgeBrowserJournalDraft).mockResolvedValue(true);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});

async function open(account = accountA) {
  const view = render(<JournalEditor account={account} journeyId={journeyId} onClose={vi.fn()} />);
  await screen.findByLabelText('Title');
  return view;
}

it('does not POST when the required local save fails', async () => {
  vi.mocked(saveBrowserJournalDraft).mockRejectedValueOnce(
    new Error('A newer draft exists in another tab.'),
  );
  await open();
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic local edit' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save to account' }));

  await screen.findByText('A newer draft exists in another tab.');
  expect(saveBrowserTripJournal).not.toHaveBeenCalled();
  expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe('Synthetic local edit');
});

it('saves a device draft while offline without sending it', async () => {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
  await open();
  fireEvent.change(screen.getByLabelText('Notes'), { target: { value: 'Synthetic offline note' } });
  await waitFor(() => expect(window.history.state?.__routiqoJournalGuard).toBeTruthy());
  fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));

  await screen.findByText('Draft saved on this device.');
  expect(saveBrowserJournalDraft).toHaveBeenCalledWith(
    accountA,
    expect.objectContaining({
      journeyId,
      notes: 'Synthetic offline note',
      expectedVersion: 0,
    }),
    null,
  );
  expect(saveBrowserTripJournal).not.toHaveBeenCalled();
});

it('retries unchanged text with the same durable mutation', async () => {
  vi.mocked(saveBrowserTripJournal)
    .mockRejectedValueOnce(new BrowserJournalError(503))
    .mockResolvedValueOnce(journal('Synthetic retry title', '', 1));
  await open();
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic retry title' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save to account' }));
  await screen.findByText(/unavailable/i);

  const first = vi.mocked(saveBrowserTripJournal).mock.calls[0]![2] as TripJournalWrite;
  fireEvent.click(screen.getByRole('button', { name: 'Save to account' }));
  await screen.findByText('Saved to your account.');

  expect(saveBrowserJournalDraft).toHaveBeenCalledTimes(2);
  expect(vi.mocked(saveBrowserTripJournal).mock.calls[1]![2]).toEqual(first);
  expect(acknowledgeBrowserJournalDraft).toHaveBeenCalledWith(
    accountA,
    journeyId,
    first.mutationId,
    expect.objectContaining({ annotation: expect.objectContaining({ version: 1 }) }),
  );
});

it('does not POST an unchanged retry when another tab replaced its durable draft', async () => {
  vi.mocked(saveBrowserTripJournal).mockRejectedValueOnce(new BrowserJournalError(503));
  await open();
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic retry title' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save to account' }));
  await screen.findByText(/unavailable/i);

  vi.mocked(saveBrowserJournalDraft).mockRejectedValueOnce(
    new Error('This journal draft changed in another tab.'),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Save to account' }));
  await screen.findByText('This journal draft changed in another tab.');

  expect(saveBrowserTripJournal).toHaveBeenCalledOnce();
});

it('retains authored fields and shows a read-only account version after a conflict', async () => {
  const latest = journal('Synthetic account title', 'Synthetic account note', 1);
  vi.mocked(readBrowserTripJournal).mockResolvedValueOnce(journal()).mockResolvedValueOnce(latest);
  vi.mocked(saveBrowserTripJournal).mockRejectedValueOnce(new BrowserJournalError(409));
  await open();
  fireEvent.change(screen.getByLabelText('Title'), {
    target: { value: 'Synthetic retained title' },
  });
  fireEvent.change(screen.getByLabelText('Notes'), {
    target: { value: 'Synthetic retained note' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Save to account' }));

  await screen.findByRole('region', { name: 'Latest account version' });
  expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(
    'Synthetic retained title',
  );
  expect((screen.getByLabelText('Notes') as HTMLTextAreaElement).value).toBe(
    'Synthetic retained note',
  );
  expect(screen.getByText('Synthetic account title')).toBeTruthy();
  expect(acknowledgeBrowserJournalDraft).not.toHaveBeenCalled();
});

it('resets private fields on account change and ignores a late save callback', async () => {
  let resolveSave!: () => void;
  vi.mocked(saveBrowserJournalDraft).mockImplementationOnce(
    (_account, input) =>
      new Promise((resolve) => {
        resolveSave = () => resolve(stored(input));
      }),
  );
  const view = await open(accountA);
  fireEvent.change(screen.getByLabelText('Title'), {
    target: { value: 'Synthetic account A text' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));

  vi.mocked(readBrowserJournal).mockResolvedValueOnce({
    draft: null,
    journal: journal('Synthetic account B title', '', 1),
  });
  vi.mocked(readBrowserTripJournal).mockResolvedValueOnce(
    journal('Synthetic account B title', '', 1),
  );
  view.rerender(<JournalEditor account={accountB} journeyId={journeyId} onClose={vi.fn()} />);
  await waitFor(() =>
    expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(
      'Synthetic account B title',
    ),
  );

  resolveSave();
  await Promise.resolve();
  expect(screen.queryByDisplayValue('Synthetic account A text')).toBeNull();
  expect(screen.queryByText('Draft saved on this device.')).toBeNull();
});

it('guards browser history while text is dirty and requires an explicit discard', async () => {
  const onClose = vi.fn();
  const historyGo = vi.spyOn(window.history, 'go').mockImplementation(() => undefined);
  const historyForward = vi.spyOn(window.history, 'forward').mockImplementation(() => undefined);
  const laterPopstate = vi.fn();
  render(<JournalEditor account={accountA} journeyId={journeyId} onClose={onClose} />);
  await screen.findByLabelText('Title');
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic route edit' } });
  await waitFor(() => expect(window.history.state?.__routiqoJournalGuard).toBeTruthy());
  window.addEventListener('popstate', laterPopstate);

  window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
  await screen.findByRole('heading', { name: 'Discard changes?' });
  expect(onClose).not.toHaveBeenCalled();
  expect(historyForward).toHaveBeenCalledOnce();
  expect(laterPopstate).not.toHaveBeenCalled();
  window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }));

  fireEvent.click(screen.getByRole('button', { name: 'Continue writing' }));
  expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe('Synthetic route edit');

  window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
  await screen.findByRole('heading', { name: 'Discard changes?' });
  fireEvent.click(screen.getByRole('button', { name: 'Discard changes' }));
  expect(onClose).toHaveBeenCalledOnce();
  await waitFor(() => expect(historyGo).toHaveBeenCalledWith(-2));
  window.removeEventListener('popstate', laterPopstate);
});

it('preserves router state and removes its clean guard when the editor closes', async () => {
  const onClose = vi.fn();
  const historyBack = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
  window.history.replaceState({ __NA: true, syntheticTree: 'kept' }, '', window.location.href);
  render(<JournalEditor account={accountA} journeyId={journeyId} onClose={onClose} />);
  await screen.findByLabelText('Title');
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic saved edit' } });
  await waitFor(() => expect(window.history.state?.__routiqoJournalGuard).toBeTruthy());
  expect(window.history.state).toEqual(
    expect.objectContaining({ __NA: true, syntheticTree: 'kept' }),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
  await screen.findByText('Draft saved on this device.');

  fireEvent.click(screen.getByRole('button', { name: 'Close dialog' }));
  expect(onClose).toHaveBeenCalledOnce();
  await waitFor(() => expect(historyBack).toHaveBeenCalledOnce());
});
