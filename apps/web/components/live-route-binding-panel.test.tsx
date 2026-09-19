// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bindBrowserLiveRouteContext,
  BrowserLiveError,
  readBrowserLiveRouteContext,
  type LiveRouteBindingResult,
  type LiveRouteContext,
} from '../lib/browser-live';
import {
  LiveRouteBindingPanel,
  type LiveConsentAuthority,
  type RouteBindingSelectionSnapshot,
} from './live-route-binding-panel';

vi.mock('../lib/browser-live', async (load) => {
  const actual = await load<typeof import('../lib/browser-live')>();
  return {
    ...actual,
    bindBrowserLiveRouteContext: vi.fn(),
    readBrowserLiveRouteContext: vi.fn(),
  };
});

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const contextId = '00000000-0000-4000-8000-000000000003';
const now = Date.parse('2026-09-19T08:00:00Z');

function context(overrides: Partial<LiveRouteContext> = {}): LiveRouteContext {
  return {
    contextId,
    revision: '4',
    anchorIds: [],
    issuedAt: '2026-09-19T07:59:00Z',
    expiresAt: '2026-09-19T08:10:00Z',
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

function setup() {
  let authority: LiveConsentAuthority | null = {
    accountId,
    journeyId,
    generation: '9007199254740993',
    epoch: 1,
  };
  let selection: RouteBindingSelectionSnapshot = {
    epoch: 1,
    selection: {
      mode: 'driving',
      origin: [80, 13],
      destination: [81, 14],
      alternativeIndex: 0,
    },
  };
  const props = () => ({
    accountId,
    journeyId,
    online: true,
    available: true,
    authority: authority!,
    getAuthority: () => authority,
    selectionSnapshot: selection,
    getSelectionSnapshot: () => selection,
  });
  const view = render(<LiveRouteBindingPanel {...props()} />);
  return {
    view,
    props,
    setAuthority(value: LiveConsentAuthority | null) {
      authority = value;
    },
    setSelection(value: RouteBindingSelectionSnapshot) {
      selection = value;
    },
  };
}

beforeEach(() => {
  vi.spyOn(Date, 'now').mockReturnValue(now);
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  vi.mocked(readBrowserLiveRouteContext).mockResolvedValue({ context: null });
  vi.mocked(bindBrowserLiveRouteContext).mockResolvedValue({
    status: 'bound',
    context: context(),
  });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.resetAllMocks();
});

describe('LiveRouteBindingPanel', () => {
  it('makes no request on mount, focus or reconnect and requires an explicit check', () => {
    const state = setup();
    expect(readBrowserLiveRouteContext).not.toHaveBeenCalled();
    fireEvent.focus(window);
    state.view.rerender(<LiveRouteBindingPanel {...state.props()} online={false} />);
    state.view.rerender(<LiveRouteBindingPanel {...state.props()} online />);
    expect(readBrowserLiveRouteContext).not.toHaveBeenCalled();
    expect(bindBrowserLiveRouteContext).not.toHaveBeenCalled();
    expect(screen.getByText('Route preparation has not been checked.')).toBeTruthy();
  });

  it('submits a copied selected alternative with the exact observed null expectation', async () => {
    const state = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    await screen.findByText(/Last checked: no private route preparation/);
    fireEvent.click(screen.getByRole('button', { name: 'Prepare selected route' }));
    await screen.findByText(/confirmed for this request/);
    expect(vi.mocked(bindBrowserLiveRouteContext).mock.calls[0]?.slice(0, 3)).toEqual([
      accountId,
      journeyId,
      {
        mode: 'driving',
        origin: [80, 13],
        destination: [81, 14],
        alternativeIndex: 0,
        expectedContextId: null,
      },
    ]);
    state.setSelection({
      epoch: 2,
      selection: {
        mode: 'walking',
        origin: [0, 0],
        destination: [0, 0],
        alternativeIndex: 2,
      },
    });
    state.view.rerender(<LiveRouteBindingPanel {...state.props()} />);
    expect(screen.queryByText(/confirmed for this request/)).toBeNull();
    expect(screen.getByText('Route preparation has not been checked.')).toBeTruthy();
    expect(vi.mocked(bindBrowserLiveRouteContext).mock.calls[0]?.[2]).toMatchObject({
      mode: 'driving',
      origin: [80, 13],
    });
  });

  it('uses the exact observed context and permits only one in-flight prepare', async () => {
    const pending = deferred<LiveRouteBindingResult>();
    vi.mocked(readBrowserLiveRouteContext).mockResolvedValue({ context: context() });
    vi.mocked(bindBrowserLiveRouteContext).mockReturnValue(pending.promise);
    setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    await screen.findByText(/preparation exists/);
    const prepare = screen.getByRole('button', { name: 'Prepare selected route' });
    fireEvent.click(prepare);
    fireEvent.click(prepare);
    expect(bindBrowserLiveRouteContext).toHaveBeenCalledTimes(1);
    expect(vi.mocked(bindBrowserLiveRouteContext).mock.calls[0]?.[2]).toMatchObject({
      expectedContextId: contextId,
    });
    pending.resolve({ status: 'bound', context: context() });
    await screen.findByText(/confirmed for this request/);
  });

  it('discards a late bind after consent authority is synchronously withdrawn', async () => {
    const pending = deferred<LiveRouteBindingResult>();
    vi.mocked(bindBrowserLiveRouteContext).mockReturnValue(pending.promise);
    const state = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    await screen.findByText(/Last checked/);
    fireEvent.click(screen.getByRole('button', { name: 'Prepare selected route' }));
    const signal = vi.mocked(bindBrowserLiveRouteContext).mock.calls[0]?.[3];
    state.setAuthority(null);
    pending.resolve({ status: 'bound', context: context() });
    await act(async () => Promise.resolve());
    expect(signal?.aborted).toBe(false);
    expect(screen.queryByText(/confirmed for this request/)).toBeNull();
  });

  it('aborts and ignores a late read when the selected result changes', async () => {
    const pending = deferred<{ context: LiveRouteContext | null }>();
    vi.mocked(readBrowserLiveRouteContext).mockReturnValue(pending.promise);
    const state = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    const signal = vi.mocked(readBrowserLiveRouteContext).mock.calls[0]?.[2];
    state.setSelection({ ...state.props().selectionSnapshot, epoch: 2 });
    state.view.rerender(<LiveRouteBindingPanel {...state.props()} />);
    expect(signal?.aborted).toBe(true);
    pending.resolve({ context: context() });
    await act(async () => Promise.resolve());
    expect(screen.queryByText(/preparation exists/)).toBeNull();
  });

  it('aborts and ignores a late read when consent authority advances', async () => {
    const pending = deferred<{ context: LiveRouteContext | null }>();
    vi.mocked(readBrowserLiveRouteContext).mockReturnValue(pending.promise);
    const state = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    const signal = vi.mocked(readBrowserLiveRouteContext).mock.calls[0]?.[2];
    state.setAuthority({ ...state.props().authority, generation: '6', epoch: 2 });
    state.view.rerender(<LiveRouteBindingPanel {...state.props()} />);
    expect(signal?.aborted).toBe(true);
    pending.resolve({ context: context() });
    await act(async () => Promise.resolve());
    expect(screen.queryByText(/preparation exists/)).toBeNull();
  });

  it('aborts and ignores a late read when the journey scope changes', async () => {
    const pending = deferred<{ context: LiveRouteContext | null }>();
    vi.mocked(readBrowserLiveRouteContext).mockReturnValue(pending.promise);
    const state = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    const signal = vi.mocked(readBrowserLiveRouteContext).mock.calls[0]?.[2];
    state.view.rerender(
      <LiveRouteBindingPanel {...state.props()} journeyId="00000000-0000-4000-8000-000000000099" />,
    );
    expect(signal?.aborted).toBe(true);
    pending.resolve({ context: context() });
    await act(async () => Promise.resolve());
    expect(screen.queryByText(/preparation exists/)).toBeNull();
  });

  it.each(['offline', 'blur', 'hidden', 'unmount'] as const)(
    'aborts and ignores a late bind after %s invalidation',
    async (event) => {
      const pending = deferred<LiveRouteBindingResult>();
      vi.mocked(bindBrowserLiveRouteContext).mockReturnValue(pending.promise);
      const state = setup();
      fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
      await screen.findByText(/Last checked/);
      fireEvent.click(screen.getByRole('button', { name: 'Prepare selected route' }));
      const signal = vi.mocked(bindBrowserLiveRouteContext).mock.calls[0]?.[3];
      if (event === 'offline')
        state.view.rerender(<LiveRouteBindingPanel {...state.props()} online={false} />);
      else if (event === 'blur') fireEvent.blur(window);
      else if (event === 'hidden') {
        Object.defineProperty(document, 'visibilityState', {
          configurable: true,
          value: 'hidden',
        });
        fireEvent(document, new Event('visibilitychange'));
      } else state.view.unmount();
      expect(signal?.aborted).toBe(true);
      pending.resolve({ status: 'bound', context: context() });
      await act(async () => Promise.resolve());
      expect(screen.queryByText(/confirmed for this request/)).toBeNull();
    },
  );

  it.each([
    ['future issuance', { issuedAt: '2026-09-19T08:00:00.000000001Z' }],
    ['expired result', { expiresAt: '2026-09-19T08:00:00Z' }],
  ])('rejects a %s without making it preparable', async (_name, timestamps) => {
    vi.mocked(readBrowserLiveRouteContext).mockResolvedValue({ context: context(timestamps) });
    setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/invalid timing/);
    expect(screen.queryByRole('button', { name: 'Prepare selected route' })).toBeNull();
  });

  it('checks observation expiry again at prepare time before a passive timer runs', async () => {
    vi.mocked(readBrowserLiveRouteContext).mockResolvedValue({
      context: context({ expiresAt: '2026-09-19T08:00:01Z' }),
    });
    setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    await screen.findByText(/preparation exists/);
    vi.mocked(Date.now).mockReturnValue(now + 1001);
    fireEvent.click(screen.getByRole('button', { name: 'Prepare selected route' }));
    expect(bindBrowserLiveRouteContext).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toMatch(/Check route preparation/);
  });

  it('keeps conflict failures unknown and requires a fresh explicit check', async () => {
    vi.mocked(bindBrowserLiveRouteContext).mockRejectedValue(new BrowserLiveError('conflict'));
    setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    await screen.findByText(/Last checked/);
    fireEvent.click(screen.getByRole('button', { name: 'Prepare selected route' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Prepare selected route' })).toBeNull();
    expect(readBrowserLiveRouteContext).toHaveBeenCalledTimes(1);
  });

  it('expires a bound acknowledgement passively without fetching', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(now);
    vi.mocked(bindBrowserLiveRouteContext).mockResolvedValue({
      status: 'bound',
      context: context({ expiresAt: '2026-09-19T08:00:01Z' }),
    });
    setup();
    fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
    await act(async () => Promise.resolve());
    fireEvent.click(screen.getByRole('button', { name: 'Prepare selected route' }));
    await act(async () => Promise.resolve());
    expect(screen.getByText(/confirmed for this request/)).toBeTruthy();
    act(() => vi.advanceTimersByTime(1000));
    expect(screen.getByText(/confirmed preparation expired/)).toBeTruthy();
    expect(readBrowserLiveRouteContext).toHaveBeenCalledTimes(1);
    expect(bindBrowserLiveRouteContext).toHaveBeenCalledTimes(1);
  });

  it.each(['no_route', 'no_eligible_anchors'] as const)(
    'keeps prior-context uncertainty explicit for %s',
    async (status) => {
      vi.mocked(bindBrowserLiveRouteContext).mockResolvedValue({
        status,
        context: null,
      });
      setup();
      fireEvent.click(screen.getByRole('button', { name: 'Check route preparation' }));
      await screen.findByText(/Last checked/);
      fireEvent.click(screen.getByRole('button', { name: 'Prepare selected route' }));
      expect(await screen.findByText(/earlier private context may still exist/)).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Prepare selected route' })).toBeNull();
    },
  );
});
