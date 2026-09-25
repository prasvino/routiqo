// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AccountPlanningCopy, AccountPlanningWrite, JourneyPlan } from '@routiqo/shared';
import { AccountPlanningCopyPanel } from './account-planning-copy';
import { PlanningProvider } from './planning-provider';

const transport = vi.hoisted(() => ({
  read: vi.fn(),
  save: vi.fn(),
  remove: vi.fn(),
}));
vi.mock('../lib/browser-planning', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/browser-planning')>();
  return {
    ...actual,
    readBrowserAccountPlanning: transport.read,
    saveBrowserAccountPlanning: transport.save,
    deleteBrowserAccountPlanning: transport.remove,
  };
});
const { BrowserPlanningError } = await import('../lib/browser-planning');

const KEY = 'routiqo.planning.v1';
const account = '00000000-0000-4000-8000-000000000001';
const plan = (id: string, destination = 'DLF Chennai'): JourneyPlan => ({
  id,
  kind: 'commute',
  origin: 'Navalur',
  destination,
  date: '2026-09-28',
  time: '08:15',
  days: [1, 2, 3, 4, 5],
  notes: '',
  createdAt: '2026-09-25T06:00:00.000Z',
});
const absent: AccountPlanningCopy = {
  version: 0,
  updatedAt: null,
  state: { version: 1, plans: [], saved: [] },
};
const stored = (
  version: number,
  plans: JourneyPlan[],
  saved: string[] = [],
): AccountPlanningCopy => ({
  version,
  updatedAt: '2026-09-25T07:00:00.000000Z',
  state: { version: 1, plans, saved },
});
function renderPanel(local: { plans: JourneyPlan[]; saved: string[] }) {
  localStorage.setItem(KEY, JSON.stringify({ version: 1, ...local }));
  return render(
    <PlanningProvider>
      <AccountPlanningCopyPanel key={account} accountId={account} />
    </PlanningProvider>,
  );
}
const button = (name: RegExp | string) => screen.getByRole('button', { name });

describe('AccountPlanningCopyPanel', () => {
  beforeEach(() => {
    localStorage.clear();
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    });
    let next = 0;
    vi.spyOn(crypto, 'randomUUID').mockImplementation(
      () =>
        `00000000-0000-4000-8000-${String(++next).padStart(12, '0')}` as `${string}-${string}-${string}-${string}-${string}`,
    );
  });
  afterEach(() => {
    cleanup();
    localStorage.clear();
    transport.read.mockReset();
    transport.save.mockReset();
    transport.remove.mockReset();
    vi.restoreAllMocks();
  });

  it('makes no request until the traveller checks, then saves a first copy without confirmation', async () => {
    renderPanel({ plans: [plan('a')], saved: ['ooty'] });
    expect(screen.getByText('Not checked yet')).toBeTruthy();
    expect(screen.getByText('1 plan · 1 saved place')).toBeTruthy();
    expect(transport.read).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: /Save this device/ })).toBeNull();

    transport.read.mockResolvedValue(absent);
    fireEvent.click(button('Check account copy'));
    expect(await screen.findByText('No plans saved on your account yet')).toBeTruthy();
    expect(transport.read).toHaveBeenCalledWith(account, expect.any(AbortSignal));
    expect(screen.queryByRole('button', { name: /Add account plans/ })).toBeNull();

    transport.save.mockImplementation(async (_: string, write: AccountPlanningWrite) =>
      stored(1, write.plans, write.saved),
    );
    fireEvent.click(button(/Save this device/));
    expect(await screen.findByText('Saved 1 plan · 1 saved place to your account.')).toBeTruthy();
    const write = transport.save.mock.calls[0]![1] as AccountPlanningWrite;
    expect(write).toMatchObject({ expectedVersion: 0, plans: [plan('a')], saved: ['ooty'] });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByText('This device matches your account copy.')).toBeTruthy();
  });

  it('asks before replacing an existing copy and sends the checked version', async () => {
    renderPanel({ plans: [plan('a')], saved: [] });
    transport.read.mockResolvedValue(stored(4, [plan('b'), plan('c')], ['ooty']));
    fireEvent.click(button('Check account copy'));
    await screen.findByText(/2 plans · 1 saved place · saved/);

    fireEvent.click(button(/Save this device/));
    const dialog = screen.getByRole('dialog', { name: 'Replace your account copy?' });
    expect(dialog.textContent).toContain('Your account copy has 2 plans · 1 saved place');
    expect(dialog.textContent).toContain('this device’s 1 plan · 0 saved places');
    fireEvent.click(button('Keep account copy'));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(transport.save).not.toHaveBeenCalled();

    transport.save.mockImplementation(async (_: string, write: AccountPlanningWrite) =>
      stored(5, write.plans, write.saved),
    );
    fireEvent.click(button(/Save this device/));
    fireEvent.click(button('Replace account copy'));
    await screen.findByText('Saved 1 plan · 0 saved places to your account.');
    expect((transport.save.mock.calls[0]![1] as AccountPlanningWrite).expectedVersion).toBe(4);
  });

  it('requires a new check after a conflict', async () => {
    renderPanel({ plans: [plan('a')], saved: [] });
    transport.read.mockResolvedValue(absent);
    fireEvent.click(button('Check account copy'));
    await screen.findByText('No plans saved on your account yet');
    transport.save.mockRejectedValue(new BrowserPlanningError('conflict'));
    fireEvent.click(button(/Save this device/));
    expect((await screen.findByRole('alert')).textContent).toContain('changed on another device');
    expect(screen.getByText('Not checked yet')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Save this device/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Retry/ })).toBeNull();
  });

  it('retries an uncertain save with exactly the same write and discards it after a new check', async () => {
    renderPanel({ plans: [plan('a')], saved: [] });
    transport.read.mockResolvedValue(absent);
    fireEvent.click(button('Check account copy'));
    await screen.findByText('No plans saved on your account yet');
    transport.save.mockRejectedValueOnce(new BrowserPlanningError('uncertain'));
    fireEvent.click(button(/Save this device/));
    expect((await screen.findByRole('alert')).textContent).toContain('wasn’t confirmed');
    expect((button(/Save this device/) as HTMLButtonElement).disabled).toBe(true);

    transport.save.mockImplementation(async (_: string, write: AccountPlanningWrite) =>
      stored(1, write.plans, write.saved),
    );
    fireEvent.click(button('Retry save'));
    await screen.findByText('Saved 1 plan · 0 saved places to your account.');
    expect(transport.save).toHaveBeenCalledTimes(2);
    expect(transport.save.mock.calls[1]![1]).toEqual(transport.save.mock.calls[0]![1]);
    expect(screen.queryByRole('button', { name: 'Retry save' })).toBeNull();

    transport.save.mockRejectedValueOnce(new BrowserPlanningError('uncertain'));
    fireEvent.click(button(/Save this device/));
    // The account now holds version 1, so a new save asks to replace it first.
    fireEvent.click(await screen.findByRole('button', { name: 'Replace account copy' }));
    await screen.findByRole('button', { name: 'Retry save' });
    fireEvent.click(button('Check again'));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Retry save' })).toBeNull());
  });

  it('adds account plans to this device without removing local plans', async () => {
    renderPanel({ plans: [plan('a')], saved: ['ooty'] });
    transport.read.mockResolvedValue(
      stored(2, [plan('a', 'Changed elsewhere'), plan('b')], ['kodaikanal']),
    );
    fireEvent.click(button('Check account copy'));
    await screen.findByText(/2 plans · 1 saved place · saved/);
    fireEvent.click(button('Add account plans to this device'));
    expect(
      screen.getByText(
        'Added 1 plan and 1 saved place to this device. 1 plan was already on this device.',
      ),
    ).toBeTruthy();
    const local = JSON.parse(localStorage.getItem(KEY)!);
    expect(local.plans.map((item: JourneyPlan) => item.id)).toEqual(['a', 'b']);
    expect(local.plans[0].destination).toBe('DLF Chennai');
    expect(local.saved).toEqual(['ooty', 'kodaikanal']);
    expect(transport.save).not.toHaveBeenCalled();
  });

  it('removes the account copy only after confirmation', async () => {
    renderPanel({ plans: [plan('a')], saved: [] });
    transport.read.mockResolvedValue(stored(3, [plan('a')]));
    fireEvent.click(button('Check account copy'));
    await screen.findByText(/1 plan · 0 saved places · saved/);
    fireEvent.click(button('Remove account copy'));
    const dialog = screen.getByRole('dialog', { name: 'Remove your account copy?' });
    expect(dialog.textContent).toContain('are not changed');
    transport.remove.mockResolvedValue(undefined);
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove account copy' }).at(-1)!);
    expect(
      await screen.findByText('Your account copy was removed. Plans on this device are unchanged.'),
    ).toBeTruthy();
    expect(transport.remove).toHaveBeenCalledWith(account, 3, expect.any(AbortSignal));
    expect(screen.getByText('No plans saved on your account yet')).toBeTruthy();
    expect(JSON.parse(localStorage.getItem(KEY)!).plans).toHaveLength(1);
  });

  it('disables account actions while offline and explains why', async () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
    renderPanel({ plans: [], saved: [] });
    expect(await screen.findByText(/You’re offline/)).toBeTruthy();
    expect((button('Check account copy') as HTMLButtonElement).disabled).toBe(true);
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    act(() => void window.dispatchEvent(new Event('online')));
    await waitFor(() =>
      expect((button('Check account copy') as HTMLButtonElement).disabled).toBe(false),
    );
  });

  it('does not offer an empty first copy', async () => {
    renderPanel({ plans: [], saved: [] });
    transport.read.mockResolvedValue(absent);
    fireEvent.click(button('Check account copy'));
    await screen.findByText('No plans saved on your account yet');
    expect((button(/Save this device/) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Save a plan or a place on this device to keep a copy.')).toBeTruthy();
  });

  it('shows session errors without exposing a stale view', async () => {
    renderPanel({ plans: [], saved: [] });
    transport.read.mockRejectedValue(new BrowserPlanningError('session'));
    fireEvent.click(button('Check account copy'));
    expect((await screen.findByRole('alert')).textContent).toContain('Sign in again');
    expect(screen.getByText('Not checked yet')).toBeTruthy();
  });

  it('aborts in-flight requests on unmount and ignores late results', async () => {
    let signal: AbortSignal | undefined;
    let resolve!: (copy: AccountPlanningCopy) => void;
    transport.read.mockImplementation((_: string, next: AbortSignal) => {
      signal = next;
      return new Promise((done) => (resolve = done));
    });
    const view = renderPanel({ plans: [], saved: [] });
    fireEvent.click(button('Check account copy'));
    await waitFor(() => expect(signal).toBeDefined());
    view.unmount();
    expect(signal!.aborted).toBe(true);
    await act(async () => resolve(stored(1, [plan('late')])));
    expect(screen.queryByText(/late/)).toBeNull();
  });
});
