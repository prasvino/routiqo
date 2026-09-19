// @vitest-environment jsdom

import { act, cleanup, fireEvent, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  privateSignalNavigationWarning,
  usePrivateSignalNavigation,
} from './use-private-signal-navigation';

const guardKey = '__routiqoPrivateSignalGuard';

function link(href: string, attributes: Record<string, string> = {}) {
  const anchor = document.createElement('a');
  anchor.href = href;
  for (const [name, value] of Object.entries(attributes)) anchor.setAttribute(name, value);
  const child = document.createElement('span');
  anchor.append(child);
  document.body.append(anchor);
  return { anchor, child };
}

beforeEach(() => {
  vi.useFakeTimers();
  window.history.replaceState({ __NA: true, tree: 'preserved' }, '', '/journey?view=live');
  vi.spyOn(window.crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000001');
});

afterEach(() => {
  cleanup();
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('private signal navigation warning', () => {
  it.each([true, false])(
    'allows native fragment popstate without a warning or traversal (outstanding=%s)',
    (outstanding) => {
      const confirm = vi.fn(() => false);
      const back = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
      const forward = vi.spyOn(window.history, 'forward').mockImplementation(() => undefined);
      const view = renderHook(({ active }) => usePrivateSignalNavigation(active, confirm), {
        initialProps: { active: true },
      });
      const sentinel = window.history.state;
      view.rerender({ active: outstanding });
      window.history.replaceState(null, '', '/journey?view=live#recovery');
      window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
      expect(confirm).not.toHaveBeenCalled();
      expect(back).not.toHaveBeenCalled();
      expect(forward).not.toHaveBeenCalled();

      for (const href of [
        '/journey?view=live#other',
        '/journey?view=live#',
        '/journey?view=live',
      ]) {
        window.history.replaceState(null, '', href);
        window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
      }
      expect(confirm).not.toHaveBeenCalled();
      expect(back).not.toHaveBeenCalled();
      expect(forward).not.toHaveBeenCalled();

      window.history.replaceState(sentinel, '', '/journey?view=live');
      window.dispatchEvent(new PopStateEvent('popstate', { state: sentinel }));
      expect(confirm).not.toHaveBeenCalled();
      view.rerender({ active: true });
      window.history.replaceState({ base: true }, '', '/journey?view=live');
      window.dispatchEvent(new PopStateEvent('popstate', { state: { base: true } }));
      expect(confirm).toHaveBeenCalledOnce();
      expect(forward).toHaveBeenCalledOnce();
    },
  );

  it('installs one state-preserving guard and removes it when recovery clears', () => {
    const push = vi.spyOn(window.history, 'pushState');
    const replace = vi.spyOn(window.history, 'replaceState');
    const view = renderHook(({ outstanding }) => usePrivateSignalNavigation(outstanding), {
      initialProps: { outstanding: true },
    });

    expect(push).toHaveBeenCalledOnce();
    expect(window.history.state).toEqual(
      expect.objectContaining({
        __NA: true,
        tree: 'preserved',
        [guardKey]: '00000000-0000-4000-8000-000000000001',
      }),
    );
    view.rerender({ outstanding: true });
    expect(push).toHaveBeenCalledOnce();

    view.rerender({ outstanding: false });
    expect(replace).toHaveBeenCalledWith(
      { __NA: true, tree: 'preserved' },
      '',
      window.location.href,
    );
    expect(window.history.state).toEqual({ __NA: true, tree: 'preserved' });

    view.rerender({ outstanding: true });
    expect(push).toHaveBeenCalledOnce();
    expect(window.history.state[guardKey]).toBeTruthy();
  });

  it('uses the browser unload warning only while a handle is outstanding', () => {
    const view = renderHook(({ outstanding }) => usePrivateSignalNavigation(outstanding), {
      initialProps: { outstanding: true },
    });
    const blocked = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
    window.dispatchEvent(blocked);
    expect(blocked.defaultPrevented).toBe(true);

    view.rerender({ outstanding: false });
    const allowed = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
    window.dispatchEvent(allowed);
    expect(allowed.defaultPrevented).toBe(false);
  });

  it('blocks an ordinary same-origin link when the user keeps local recovery', () => {
    const confirm = vi.fn(() => false);
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    const { child } = link('/trips');
    const later = vi.fn();
    document.addEventListener('click', later);

    const allowed = fireEvent.click(child);
    expect(allowed).toBe(false);
    expect(confirm).toHaveBeenCalledWith(privateSignalNavigationWarning);
    expect(later).not.toHaveBeenCalled();
    expect(window.history.state[guardKey]).toBeTruthy();
    document.removeEventListener('click', later);
  });

  it('lets confirmed in-app navigation proceed and restores the guard if navigation is cancelled', () => {
    const confirm = vi.fn(() => true);
    const push = vi.spyOn(window.history, 'pushState');
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    const { anchor, child } = link('/trips');
    const reachedRouter = vi.fn((event: Event) => event.preventDefault());
    anchor.addEventListener('click', reachedRouter);

    fireEvent.click(child);
    expect(reachedRouter).toHaveBeenCalledOnce();
    expect(confirm).toHaveBeenCalledWith(privateSignalNavigationWarning);
    expect(window.history.state).toEqual({ __NA: true, tree: 'preserved' });
    act(() => vi.runAllTimers());
    expect(push).toHaveBeenCalledOnce();
    expect(window.history.state[guardKey]).toBeTruthy();

    fireEvent.click(child);
    act(() => vi.runAllTimers());
    expect(push).toHaveBeenCalledOnce();
  });

  it.each([
    ['modified click', '/trips', { ctrlKey: true }, {}],
    ['new tab', '/trips', {}, { target: '_blank' }],
    ['download', '/trips', {}, { download: 'trip.txt' }],
    ['same-page hash', '/journey?view=live#recovery', {}, {}],
    ['external destination', 'https://example.test/path', {}, {}],
  ])('does not intercept %s', (_label, href, event, attributes) => {
    const confirm = vi.fn(() => false);
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    const { anchor, child } = link(href, attributes);
    anchor.addEventListener('click', (clickEvent) => clickEvent.preventDefault());

    fireEvent.click(child, event);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('restores its sentinel after a cancelled Back without growing history', () => {
    const confirm = vi.fn(() => false);
    const forward = vi.spyOn(window.history, 'forward').mockImplementation(() => undefined);
    const push = vi.spyOn(window.history, 'pushState');
    const later = vi.fn();
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    window.addEventListener('popstate', later);

    const baseState = { __NA: true, tree: 'preserved' };
    window.dispatchEvent(new PopStateEvent('popstate', { state: baseState }));
    expect(confirm).toHaveBeenCalledWith(privateSignalNavigationWarning);
    expect(forward).toHaveBeenCalledOnce();
    expect(later).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalledOnce();

    window.dispatchEvent(
      new PopStateEvent('popstate', {
        state: { ...baseState, [guardKey]: '00000000-0000-4000-8000-000000000001' },
      }),
    );
    expect(forward).toHaveBeenCalledOnce();
    window.removeEventListener('popstate', later);
  });

  it('continues past its duplicate after confirmed Back and does not trap the next pop', () => {
    const confirm = vi.fn(() => true);
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    const later = vi.fn();
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    window.addEventListener('popstate', later);

    window.dispatchEvent(
      new PopStateEvent('popstate', { state: { __NA: true, tree: 'preserved' } }),
    );
    expect(back).toHaveBeenCalledOnce();
    expect(later).not.toHaveBeenCalled();

    window.history.replaceState({ earlier: true }, '', '/earlier');
    window.dispatchEvent(new PopStateEvent('popstate', { state: { earlier: true } }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(back).toHaveBeenCalledOnce();
    expect(later).toHaveBeenCalledOnce();
    window.removeEventListener('popstate', later);
  });

  it('rearms after another guard cancels a confirmed Back departure', () => {
    const confirm = vi.fn(() => true);
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    renderHook(() => usePrivateSignalNavigation(true, confirm));

    window.dispatchEvent(new PopStateEvent('popstate', { state: { base: true } }));
    expect(back).toHaveBeenCalledOnce();
    window.history.replaceState(
      {
        __NA: true,
        __routiqoJournalGuard: 'journal-marker',
        [guardKey]: '00000000-0000-4000-8000-000000000001',
      },
      '',
      window.location.href,
    );
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }));
    expect(window.history.state).toEqual(
      expect.objectContaining({
        __routiqoJournalGuard: 'journal-marker',
        [guardKey]: '00000000-0000-4000-8000-000000000001',
      }),
    );

    window.dispatchEvent(new PopStateEvent('popstate', { state: { base: true } }));
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(back).toHaveBeenCalledTimes(2);
  });

  it('does not intercept a journal-only restoration step after confirmed Back', () => {
    const confirm = vi.fn(() => true);
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => undefined);
    const later = vi.fn();
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    window.addEventListener('popstate', later);

    window.dispatchEvent(new PopStateEvent('popstate', { state: { base: true } }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(back).toHaveBeenCalledOnce();
    window.dispatchEvent(new PopStateEvent('popstate', { state: { base: true } }));
    window.dispatchEvent(
      new PopStateEvent('popstate', {
        state: { __routiqoJournalGuard: 'journal-marker' },
      }),
    );

    expect(confirm).toHaveBeenCalledOnce();
    expect(back).toHaveBeenCalledOnce();
    expect(later).toHaveBeenCalledTimes(2);
    window.removeEventListener('popstate', later);
  });

  it('does not trust a journal marker from a different URL', () => {
    const confirm = vi.fn(() => false);
    const forward = vi.spyOn(window.history, 'forward').mockImplementation(() => undefined);
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    window.history.replaceState({}, '', '/older-workspace');

    window.dispatchEvent(
      new PopStateEvent('popstate', {
        state: { __routiqoJournalGuard: 'stale-journal-marker' },
      }),
    );
    expect(confirm).toHaveBeenCalledOnce();
    expect(forward).toHaveBeenCalledOnce();
  });

  it('yields when another history guard is above its sentinel', () => {
    const confirm = vi.fn(() => false);
    const forward = vi.spyOn(window.history, 'forward').mockImplementation(() => undefined);
    renderHook(() => usePrivateSignalNavigation(true, confirm));
    const ownState = window.history.state;
    window.history.pushState(
      { ...ownState, __routiqoJournalGuard: 'journal-marker' },
      '',
      window.location.href,
    );
    const later = vi.fn();
    window.addEventListener('popstate', later);

    window.dispatchEvent(new PopStateEvent('popstate', { state: ownState }));
    expect(confirm).not.toHaveBeenCalled();
    expect(forward).not.toHaveBeenCalled();
    expect(later).toHaveBeenCalledOnce();
    window.removeEventListener('popstate', later);
  });

  it('preserves a journal marker and newer router state when recovery clears or unmounts', () => {
    const push = vi.spyOn(window.history, 'pushState');
    const view = renderHook(({ outstanding }) => usePrivateSignalNavigation(outstanding), {
      initialProps: { outstanding: true },
    });
    window.history.pushState(
      {
        ...window.history.state,
        __NA: true,
        tree: 'newer-router-state',
        __routiqoJournalGuard: 'journal-marker',
      },
      '',
      window.location.href,
    );

    view.rerender({ outstanding: false });
    expect(window.history.state).toEqual({
      __NA: true,
      tree: 'newer-router-state',
      __routiqoJournalGuard: 'journal-marker',
    });
    view.rerender({ outstanding: true });
    expect(push).toHaveBeenCalledTimes(2);
    expect(window.history.state).toEqual(
      expect.objectContaining({
        tree: 'newer-router-state',
        __routiqoJournalGuard: 'journal-marker',
        [guardKey]: '00000000-0000-4000-8000-000000000001',
      }),
    );

    view.unmount();
    expect(window.history.state).toEqual({
      __NA: true,
      tree: 'newer-router-state',
      __routiqoJournalGuard: 'journal-marker',
    });
  });

  it('preserves a pre-existing guard marker on remount without another push', () => {
    window.history.replaceState(
      { __NA: true, [guardKey]: '00000000-0000-4000-8000-000000000099' },
      '',
      window.location.href,
    );
    const push = vi.spyOn(window.history, 'pushState');
    const view = renderHook(() => usePrivateSignalNavigation(true));
    expect(push).not.toHaveBeenCalled();

    view.unmount();
    expect(window.history.state).toEqual({ __NA: true });
  });
});
