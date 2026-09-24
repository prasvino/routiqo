import { createElement, isValidElement, type ReactNode } from '../apps/mobile/node_modules/react';
import { expect, it, vi } from 'vitest';

vi.mock('../apps/mobile/node_modules/expo-router', () => ({ useFocusEffect: vi.fn() }));
vi.mock('../apps/mobile/node_modules/react-native', () => ({
  AppState: { currentState: 'active', addEventListener: vi.fn() },
  Platform: { OS: 'android' },
  Pressable: 'Pressable',
  Text: 'Text',
  View: 'View',
  StyleSheet: { create: (styles: unknown) => styles },
}));
vi.mock('../apps/mobile/src/auth/native-account-provider', () => ({ useNativeAccount: vi.fn() }));

import {
  NativeConsentView,
  type NativeConsentViewProps,
} from '../apps/mobile/src/features/live/native-consent-panel';
import { nativeConsentEligibility } from '../apps/mobile/src/features/live/native-consent-eligibility';
import type { NativeConsentState } from '../apps/mobile/src/features/live/native-consent-controller';

const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
type Node = { type: unknown; props: Record<string, unknown> };
function nodes(value: ReactNode): Node[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!isValidElement(value)) return [];
  if (typeof value.type === 'function') return nodes(value.type(value.props));
  const props = value.props as Record<string, unknown>;
  return [{ type: value.type, props }, ...nodes(props.children as ReactNode)];
}
const unknown: NativeConsentState = {
  confirmed: null,
  lastGeneration: '0',
  uncertain: false,
  terminal: false,
  busy: false,
  notice: 'unknown',
  failure: null,
};
function props(overrides: Partial<NativeConsentViewProps> = {}): NativeConsentViewProps {
  return {
    state: unknown,
    available: true,
    online: true,
    onCheck: vi.fn(),
    onAllow: vi.fn(),
    onStop: vi.fn(),
    ...overrides,
  };
}
function action(tree: Node[], label: string) {
  return tree.find(
    (node) =>
      node.type === 'Pressable' &&
      nodes(node.props.children as ReactNode).some((child) => child.props.children === label),
  );
}
function copy(tree: Node[]) {
  return tree
    .filter((node) => node.type === 'Text')
    .map((node) => node.props.children)
    .join(' ');
}

it('exposes explicit Check and Stop while unknown, and Allow only for confirmed active off', () => {
  const onStop = vi.fn();
  const initial = nodes(createElement(NativeConsentView, props({ onStop })));
  expect(action(initial, 'Check LIVE settings')?.props.disabled).toBe(false);
  expect(action(initial, 'Stop private contributions')?.props.disabled).toBe(false);
  expect(action(initial, 'Allow private contributions')?.props.disabled).toBe(true);
  (action(initial, 'Stop private contributions')?.props.onPress as () => void)();
  expect(onStop).toHaveBeenCalledOnce();
  expect(copy(initial)).toContain('Last checked: not yet');
  expect(copy(initial)).toContain(
    'Stopping does not delete saved private contributions or receipts',
  );
  const confirmed = nodes(
    createElement(
      NativeConsentView,
      props({
        state: {
          ...unknown,
          confirmed: { journeyId: id(2), generation: '1', sharing: false, journeyActive: true },
        },
      }),
    ),
  );
  expect(action(confirmed, 'Allow private contributions')?.props.disabled).toBe(false);
});

it('keeps uncertainty visible and disables controls during refresh, offline and pending work', () => {
  const uncertain = { ...unknown, uncertain: true, notice: 'interrupted' as const };
  const refresh = nodes(
    createElement(NativeConsentView, props({ state: uncertain, available: false })),
  );
  expect(copy(refresh)).toContain('A previous change may have reached your account');
  expect(copy(refresh)).toContain('temporarily unavailable');
  expect(action(refresh, 'Stop private contributions')?.props.accessibilityState).toEqual({
    disabled: true,
  });
  const offline = nodes(
    createElement(NativeConsentView, props({ state: uncertain, online: false })),
  );
  expect(copy(offline)).toContain('Offline');
  expect(action(offline, 'Check LIVE settings')?.props.disabled).toBe(true);
});

it('retains mounted scope through restoration, but blocks dispatch and removes it on account loss', () => {
  const initial = nativeConsentEligibility(null, {
    accountId: id(1),
    activeJourneyId: id(2),
    restoring: false,
    busy: false,
    deletionCleanupPending: false,
    pendingJourneyActions: 0,
  });
  expect(initial.available).toBe(true);
  const refreshing = nativeConsentEligibility(initial.scope, {
    accountId: id(1),
    activeJourneyId: id(2),
    restoring: false,
    busy: true,
    deletionCleanupPending: false,
    pendingJourneyActions: 0,
  });
  expect(refreshing).toEqual({ scope: initial.scope, available: false });
  const restoring = nativeConsentEligibility(initial.scope, {
    accountId: null,
    activeJourneyId: null,
    restoring: true,
    busy: false,
    deletionCleanupPending: false,
    pendingJourneyActions: 0,
  });
  expect(restoring).toEqual({ scope: initial.scope, available: false });
  const signedOut = nativeConsentEligibility(restoring.scope, {
    accountId: null,
    activeJourneyId: null,
    restoring: false,
    busy: false,
    deletionCleanupPending: false,
    pendingJourneyActions: 0,
  });
  expect(signedOut.scope).toBeNull();
  const queued = nativeConsentEligibility(null, {
    accountId: id(1),
    activeJourneyId: null,
    restoring: false,
    busy: false,
    deletionCleanupPending: false,
    pendingJourneyActions: 1,
  });
  expect(queued.scope).toBeNull();
});
