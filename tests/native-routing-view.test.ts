import { createElement, isValidElement, type ReactNode } from '../apps/mobile/node_modules/react';
import { expect, it, vi } from 'vitest';
import { Keyboard } from '../apps/mobile/node_modules/react-native';

vi.mock('../apps/mobile/node_modules/expo-router', () => ({ useFocusEffect: vi.fn() }));
vi.mock('../apps/mobile/node_modules/react-native', () => ({
  AppState: { currentState: 'active', addEventListener: vi.fn() },
  Keyboard: { dismiss: vi.fn() },
  Platform: { OS: 'android' },
  Pressable: 'Pressable',
  Text: 'Text',
  TextInput: 'TextInput',
  View: 'View',
  StyleSheet: { create: (styles: unknown) => styles },
}));
vi.mock('../apps/mobile/src/auth/native-account-provider', () => ({ useNativeAccount: vi.fn() }));

import {
  NativeRoutePlannerView,
  type NativeRoutePlannerViewProps,
} from '../apps/mobile/src/features/journey/native-route-planner';
import type { NativeRoutingState } from '../apps/mobile/src/features/journey/native-routing-controller';

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
const place = (id: string, longitude: number) => ({
  id,
  label: id,
  coordinate: [longitude, 13] as [number, number],
});
const empty: NativeRoutingState = {
  origin: { text: '', selected: null, results: null, attribution: null },
  destination: { text: '', selected: null, results: null, attribution: null },
  mode: 'driving',
  route: null,
  alternative: 0,
  step: 0,
  busy: null,
  failure: null,
  noRoute: false,
};
function props(overrides: Partial<NativeRoutePlannerViewProps> = {}): NativeRoutePlannerViewProps {
  return {
    state: empty,
    online: true,
    available: true,
    onEdit: vi.fn(),
    onSearch: vi.fn(),
    onSelect: vi.fn(),
    onMode: vi.fn(),
    onSwap: vi.fn(),
    onCalculate: vi.fn(),
    onAlternative: vi.fn(),
    onStep: vi.fn(),
    ...overrides,
  };
}

it('exposes bounded explicit place search, mode and selected-match actions', () => {
  const onEdit = vi.fn();
  const onSearch = vi.fn();
  const onSelect = vi.fn();
  const state: NativeRoutingState = {
    ...empty,
    origin: {
      text: 'Chen',
      selected: null,
      results: {
        provider: 'photon',
        attribution: '© OpenStreetMap contributors',
        places: [place('Chennai', 80)],
      },
      attribution: null,
    },
  };
  const tree = nodes(
    createElement(NativeRoutePlannerView, props({ state, onEdit, onSearch, onSelect })),
  );
  const inputs = tree.filter((node) => node.type === 'TextInput');
  expect(inputs.map((input) => input.props.maxLength)).toEqual([256, 256]);
  (inputs[0]?.props.onChangeText as (value: string) => void)('Chennai');
  (action(tree, 'Search starting place')?.props.onPress as () => void)();
  (action(tree, 'Chennai')?.props.onPress as () => void)();
  expect(onEdit).toHaveBeenCalledWith('origin', 'Chennai');
  expect(onSearch).toHaveBeenCalledWith('origin');
  expect(Keyboard.dismiss).toHaveBeenCalledOnce();
  expect(onSelect).toHaveBeenCalledWith('origin', 'Chennai');
  expect(copy(tree)).toContain('OpenStreetMap');
  expect(action(tree, 'Calculate route')?.props.disabled).toBe(true);
  expect(action(tree, 'Swap places')?.props.disabled).toBe(true);
});

it('dismisses the keyboard before explicit calculation', () => {
  vi.mocked(Keyboard.dismiss).mockClear();
  const onCalculate = vi.fn();
  const state: NativeRoutingState = {
    ...empty,
    origin: { text: 'Chennai', selected: place('Chennai', 80), results: null, attribution: null },
    destination: {
      text: 'Pondicherry',
      selected: place('Pondicherry', 81),
      results: null,
      attribution: null,
    },
  };
  const tree = nodes(createElement(NativeRoutePlannerView, props({ state, onCalculate })));
  (action(tree, 'Calculate route')?.props.onPress as () => void)();
  expect(Keyboard.dismiss).toHaveBeenCalledOnce();
  expect(onCalculate).toHaveBeenCalledOnce();
});

it('shows loaded estimates, attribution and one manual instruction while offline', () => {
  const onStep = vi.fn();
  const state: NativeRoutingState = {
    ...empty,
    origin: {
      text: 'Chennai',
      selected: place('Chennai', 80),
      results: null,
      attribution: '© OpenStreetMap',
    },
    destination: {
      text: 'Pondicherry',
      selected: place('Pondicherry', 81),
      results: null,
      attribution: '© OpenStreetMap',
    },
    route: {
      provider: 'valhalla',
      calculatedAt: '2026-09-24T06:00:00Z',
      routes: [
        {
          distanceMetres: 1000,
          durationSeconds: 600,
          geometry: [
            [80, 13],
            [81, 13],
          ],
          steps: [
            {
              instruction: 'Head east',
              distanceMetres: 400,
              durationSeconds: 240,
              location: [80, 13],
            },
            {
              instruction: 'Continue',
              distanceMetres: 600,
              durationSeconds: 360,
              location: [81, 13],
            },
          ],
        },
      ],
    },
  };
  const tree = nodes(
    createElement(NativeRoutePlannerView, props({ state, online: false, onStep })),
  );
  expect(copy(tree)).toContain('Head east');
  expect(copy(tree)).not.toContain('Continue');
  expect(copy(tree)).toContain('Last calculated');
  expect(copy(tree)).toContain('Showing the last loaded estimate');
  expect(action(tree, 'Calculate route')?.props.disabled).toBe(true);
  expect(action(tree, 'Swap places')?.props.disabled).toBe(false);
  (action(tree, 'Next step')?.props.onPress as () => void)();
  expect(onStep).toHaveBeenCalledWith(1);
});

it('shows distinct coverage, no-route and empty instruction states without inventing directions', () => {
  const failed = nodes(
    createElement(
      NativeRoutePlannerView,
      props({ state: { ...empty, failure: 'coverage', noRoute: true } }),
    ),
  );
  expect(copy(failed)).toContain('Route coverage is unavailable');
  expect(copy(failed)).toContain('No route was found');
  const noSteps = nodes(
    createElement(
      NativeRoutePlannerView,
      props({
        state: {
          ...empty,
          route: {
            provider: 'valhalla',
            calculatedAt: '2026-09-24T06:00:00Z',
            routes: [
              {
                distanceMetres: 1000,
                durationSeconds: 600,
                geometry: [
                  [80, 13],
                  [81, 13],
                ],
                steps: [],
              },
            ],
          },
        },
      }),
    ),
  );
  expect(copy(noSteps)).toContain('Turn instructions are unavailable');
});
