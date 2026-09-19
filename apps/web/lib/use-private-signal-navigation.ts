'use client';

import { useEffect, useRef } from 'react';

const historyGuardKey = '__routiqoPrivateSignalGuard';
const journalHistoryGuardKey = '__routiqoJournalGuard';
export const privateSignalNavigationWarning =
  'Leaving this page removes local Quick Signal stop handles. Server evidence may remain until it expires. Leave anyway?';

interface HistoryGuard {
  marker: string;
  workspaceUrl: string;
  observedUrl: string;
}

function stateWithMarker(state: unknown, marker: string): Record<string, unknown> {
  return typeof state === 'object' && state !== null
    ? { ...state, [historyGuardKey]: marker }
    : { [historyGuardKey]: marker };
}

function markerFrom(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null;
  const marker = (state as Record<string, unknown>)[historyGuardKey];
  return typeof marker === 'string' ? marker : null;
}

function hasJournalMarker(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    typeof (state as Record<string, unknown>)[journalHistoryGuardKey] === 'string'
  );
}

function stateWithoutMarker(state: unknown): unknown {
  if (typeof state !== 'object' || state === null) return state;
  const copy = { ...(state as Record<string, unknown>) };
  delete copy[historyGuardKey];
  return copy;
}

function sameDocumentHashOnly(destination: URL): boolean {
  return (
    destination.origin === window.location.origin &&
    destination.pathname === window.location.pathname &&
    destination.search === window.location.search
  );
}

function fragmentTransition(previousUrl: string): boolean {
  return sameDocumentHashOnly(new URL(previousUrl)) && previousUrl !== window.location.href;
}

export function usePrivateSignalNavigation(
  hasOutstanding: boolean,
  confirmLeave: (message: string) => boolean = (message) => window.confirm(message),
): void {
  const outstanding = useRef(hasOutstanding);
  const confirmRef = useRef(confirmLeave);
  const guard = useRef<HistoryGuard | null>(null);
  const restoring = useRef(false);
  const bypassNextPop = useRef(false);
  const pendingPopLocation = useRef<string | null>(null);
  const mounted = useRef(true);
  outstanding.current = hasOutstanding;
  confirmRef.current = confirmLeave;

  useEffect(() => {
    mounted.current = true;

    const installGuard = () => {
      if (!mounted.current || !outstanding.current) return;
      if (guard.current) {
        if (markerFrom(window.history.state) !== guard.current.marker) {
          window.history.replaceState(
            stateWithMarker(window.history.state, guard.current.marker),
            '',
            window.location.href,
          );
        }
        return;
      }
      const existingMarker = markerFrom(window.history.state);
      if (existingMarker) {
        guard.current = {
          marker: existingMarker,
          workspaceUrl: window.location.href,
          observedUrl: window.location.href,
        };
        return;
      }
      const previousState: unknown = window.history.state;
      const marker = crypto.randomUUID();
      guard.current = {
        marker,
        workspaceUrl: window.location.href,
        observedUrl: window.location.href,
      };
      window.history.pushState(stateWithMarker(previousState, marker), '', window.location.href);
    };

    const removeCurrentGuard = () => {
      const current = guard.current;
      if (!current) return;
      if (markerFrom(window.history.state) === current.marker) {
        window.history.replaceState(
          stateWithoutMarker(window.history.state),
          '',
          window.location.href,
        );
      }
      guard.current = null;
      restoring.current = false;
    };

    const suspendCurrentGuard = () => {
      const current = guard.current;
      if (current && markerFrom(window.history.state) === current.marker) {
        window.history.replaceState(
          stateWithoutMarker(window.history.state),
          '',
          window.location.href,
        );
      }
      restoring.current = false;
    };

    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!outstanding.current) return;
      event.preventDefault();
      event.returnValue = '';
    };

    const click = (event: MouseEvent) => {
      if (
        !outstanding.current ||
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey
      )
        return;
      const target = event.target;
      if (!(target instanceof Element)) return;
      const anchor = target.closest('a[href]');
      if (!(anchor instanceof HTMLAnchorElement) || anchor.download) return;
      if (anchor.target && anchor.target.toLowerCase() !== '_self') return;
      const destination = new URL(anchor.href, window.location.href);
      if (destination.origin !== window.location.origin || sameDocumentHashOnly(destination))
        return;
      if (!confirmRef.current(privateSignalNavigationWarning)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        return;
      }
      const locationBeforeNavigation = window.location.href;
      suspendCurrentGuard();
      window.setTimeout(() => {
        if (window.location.href === locationBeforeNavigation) installGuard();
      }, 0);
    };

    const popstate = (event: PopStateEvent) => {
      if (bypassNextPop.current) {
        bypassNextPop.current = false;
        const origin = pendingPopLocation.current;
        pendingPopLocation.current = null;
        if (origin === window.location.href && outstanding.current && guard.current) {
          guard.current.observedUrl = window.location.href;
          if (markerFrom(window.history.state) !== guard.current.marker) {
            window.history.replaceState(
              stateWithMarker(window.history.state, guard.current.marker),
              '',
              window.location.href,
            );
          }
        } else {
          guard.current = null;
        }
        return;
      }
      const current = guard.current;
      if (!current) return;
      const fragmentOnly = fragmentTransition(current.observedUrl);
      current.observedUrl = window.location.href;
      // Native fragment navigation also emits popstate, often with null state.
      // It keeps the workspace mounted and has no duplicate entry to skip.
      if (!restoring.current && fragmentOnly) return;
      if (!outstanding.current) {
        if (markerFrom(event.state) === current.marker) {
          window.history.replaceState(stateWithoutMarker(event.state), '', window.location.href);
          return;
        }
        event.stopImmediatePropagation();
        guard.current = null;
        bypassNextPop.current = true;
        window.history.back();
        return;
      }
      if (restoring.current) {
        event.stopImmediatePropagation();
        if (markerFrom(event.state) === current.marker) restoring.current = false;
        else window.history.forward();
        return;
      }
      // Another guard may have pushed an entry above ours. Let it handle the
      // transition back to our marker; our warning belongs to leaving it.
      if (markerFrom(event.state) === current.marker) return;
      // Journal restoration may traverse an older journal-only sentinel after
      // another guard has already handled the departure. It owns that step.
      if (hasJournalMarker(event.state) && window.location.href === current.workspaceUrl) return;
      event.stopImmediatePropagation();
      if (confirmRef.current(privateSignalNavigationWarning)) {
        pendingPopLocation.current = window.location.href;
        bypassNextPop.current = true;
        window.history.back();
      } else {
        restoring.current = true;
        window.history.forward();
      }
    };

    installGuard();
    window.addEventListener('beforeunload', beforeUnload);
    document.addEventListener('click', click, { capture: true });
    window.addEventListener('popstate', popstate, { capture: true });
    return () => {
      mounted.current = false;
      window.removeEventListener('beforeunload', beforeUnload);
      document.removeEventListener('click', click, { capture: true });
      window.removeEventListener('popstate', popstate, { capture: true });
      removeCurrentGuard();
    };
  }, []);

  useEffect(() => {
    if (hasOutstanding) {
      if (guard.current) {
        if (markerFrom(window.history.state) !== guard.current.marker) {
          window.history.replaceState(
            stateWithMarker(window.history.state, guard.current.marker),
            '',
            window.location.href,
          );
        }
        return;
      }
      const previousState: unknown = window.history.state;
      const marker = crypto.randomUUID();
      guard.current = {
        marker,
        workspaceUrl: window.location.href,
        observedUrl: window.location.href,
      };
      window.history.pushState(stateWithMarker(previousState, marker), '', window.location.href);
      return;
    }
    const current = guard.current;
    if (!current) return;
    if (markerFrom(window.history.state) === current.marker) {
      window.history.replaceState(
        stateWithoutMarker(window.history.state),
        '',
        window.location.href,
      );
    }
    restoring.current = false;
  }, [hasOutstanding]);
}
