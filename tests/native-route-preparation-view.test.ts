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
  NativeRoutePreparationView,
  type NativeRoutePreparationViewProps,
} from '../apps/mobile/src/features/live/native-route-preparation-panel';
import type { NativePreparationState } from '../apps/mobile/src/features/live/native-route-preparation-controller';

type Node = { type: unknown; props: Record<string, unknown> };
function nodes(value: ReactNode): Node[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!isValidElement(value)) return [];
  if (typeof value.type === 'function') return nodes(value.type(value.props));
  const props = value.props as Record<string, unknown>;
  return [{ type: value.type, props }, ...nodes(props.children as ReactNode)];
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
const unknown: NativePreparationState = {
  observed: undefined,
  acknowledged: null,
  busy: null,
  failure: null,
};
const context = {
  contextId: '00000000-0000-4000-8000-000000000003',
  revision: '1',
  anchorIds: ['00000000-0000-4000-8000-000000000004'],
  issuedAt: '2026-09-24T10:00:00Z',
  expiresAt: '2026-09-24T10:10:00.123456789Z',
};
function props(
  overrides: Partial<NativeRoutePreparationViewProps> = {},
): NativeRoutePreparationViewProps {
  return {
    state: unknown,
    online: true,
    available: true,
    hasConsent: true,
    hasSelection: true,
    onCheck: vi.fn(),
    onPrepare: vi.fn(),
    ...overrides,
  };
}

it('requires an explicit observation and distinguishes unknown from observed null', () => {
  const onCheck = vi.fn();
  const onPrepare = vi.fn();
  const initial = nodes(createElement(NativeRoutePreparationView, props({ onCheck, onPrepare })));
  expect(copy(initial)).toContain('status is unknown');
  expect(action(initial, 'Check private route')?.props.disabled).toBe(false);
  expect(action(initial, 'Prepare private route')?.props.disabled).toBe(true);
  (action(initial, 'Check private route')?.props.onPress as () => void)();
  expect(onCheck).toHaveBeenCalledOnce();
  const observed = nodes(
    createElement(
      NativeRoutePreparationView,
      props({
        state: { ...unknown, observed: null },
        onPrepare,
      }),
    ),
  );
  expect(copy(observed)).toContain('no private route context was found');
  expect(action(observed, 'Prepare private route')?.props.disabled).toBe(false);
  (action(observed, 'Prepare private route')?.props.onPress as () => void)();
  expect(onPrepare).toHaveBeenCalledOnce();
});

it('gates consent, selection, offline and loading while showing bounded acknowledgement copy', () => {
  const noConsent = nodes(createElement(NativeRoutePreparationView, props({ hasConsent: false })));
  expect(copy(noConsent)).toContain('allow private contributions');
  expect(action(noConsent, 'Check private route')?.props.disabled).toBe(true);
  const noSelection = nodes(
    createElement(NativeRoutePreparationView, props({ hasSelection: false })),
  );
  expect(copy(noSelection)).toContain('Calculate a route');
  const offline = nodes(createElement(NativeRoutePreparationView, props({ online: false })));
  expect(copy(offline)).toContain('Offline');
  const busy = nodes(
    createElement(
      NativeRoutePreparationView,
      props({
        state: { ...unknown, busy: 'preparing' },
      }),
    ),
  );
  expect(copy(busy)).toContain('Preparing private route');
  expect(action(busy, 'Check private route')?.props.disabled).toBe(true);
  const bound = nodes(
    createElement(
      NativeRoutePreparationView,
      props({
        state: { ...unknown, observed: context, acknowledged: context },
      }),
    ),
  );
  expect(copy(bound)).toContain('confirmed for this selected route');
  expect(copy(bound)).not.toContain('Invalid Date');
  expect(action(bound, 'Prepare private route')?.props.disabled).toBe(true);
  expect(copy(bound)).not.toContain(context.contextId);
});
