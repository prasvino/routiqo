import { createElement, isValidElement, type ReactNode } from '../apps/mobile/node_modules/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../apps/mobile/node_modules/expo-router', () => ({
  useFocusEffect: vi.fn(),
  useRouter: vi.fn(),
}));
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
  currentJourney,
  elapsedLabel,
  journeyMapEnabled,
  positionCopy,
  routeNote,
  routeProgress,
  startNote,
} from '../apps/mobile/src/features/journey/journey-mode-model';
import {
  ActiveJourneyCard,
  JourneyModeView,
  JourneyReturnBar,
  type JourneyModeViewProps,
} from '../apps/mobile/src/features/journey/journey-mode-view';
import {
  NativeRoutePlannerView,
  plannerJourneyRoute,
} from '../apps/mobile/src/features/journey/native-route-planner';
import type { NativeRoutingState } from '../apps/mobile/src/features/journey/native-routing-controller';
import type { LocationState } from '../apps/mobile/src/features/journey/journey-location';
import { buildJourneyRoute } from '../packages/shared/src/journey-route';
import type { JourneyOutbox } from '../packages/shared/src/journey-outbox';
import type { JourneySnapshots } from '../packages/shared/src/journey-snapshots';

type Node = { type: unknown; props: Record<string, unknown> };
function nodes(value: ReactNode): Node[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!isValidElement(value)) return [];
  if (typeof value.type === 'function') return nodes(value.type(value.props));
  const props = value.props as Record<string, unknown>;
  return [{ type: value.type, props }, ...nodes(props.children as ReactNode)];
}
const text = (tree: Node[]) =>
  tree
    .filter((node) => node.type === 'Text')
    .map((node) => [node.props.children].flat().join(''))
    .join(' ');
const button = (tree: Node[], label: string) =>
  tree.find(
    (node) =>
      node.type === 'Pressable' &&
      nodes(node.props.children as ReactNode).some((child) => child.props.children === label),
  );

const owner = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000003';
const outbox = (entries: JourneyOutbox['entries'] = []): JourneyOutbox => ({
  version: 1,
  accountId: owner,
  entries,
});
const snapshots = (journeys: JourneySnapshots['journeys'] = []): JourneySnapshots =>
  ({ version: 1, accountId: owner, journeys }) as JourneySnapshots;
const entry = (command: JourneyOutbox['entries'][number]['command'], blocked = null) => ({
  command,
  attempts: 0,
  nextAttemptAt: 0,
  lease: null,
  blocked,
});
const route = buildJourneyRoute({
  journeyId,
  mode: 'driving',
  originLabel: 'Kilambakkam',
  destinationLabel: 'Trichy',
  origin: [80, 12],
  destination: [80, 12.1],
  alternativeIndex: 0,
  calculatedAt: '2026-09-25T10:00:00Z',
  route: {
    distanceMetres: 11_100,
    durationSeconds: 900,
    geometry: [
      [80, 12],
      [80, 12.05],
      [80, 12.1],
    ],
  },
});
const located = (coordinate: [number, number] | null): LocationState =>
  coordinate
    ? { status: 'ready', fix: { coordinate, accuracyMetres: 10, at: 0 } }
    : { status: 'not_asked', fix: null };

describe('journey mode model', () => {
  it('reads the flag as exact true only', () => {
    expect(journeyMapEnabled('true')).toBe(true);
    for (const value of ['TRUE', '1', 'yes', ' true', '', undefined])
      expect(journeyMapEnabled(value)).toBe(false);
  });

  it('finds a confirmed journey, a queued start, or nothing', () => {
    expect(currentJourney(null)).toBeNull();
    expect(currentJourney({ outbox: outbox(), snapshots: snapshots() })).toBeNull();
    const active = {
      id: journeyId,
      kind: 'trip' as const,
      status: 'active' as const,
      startedAt: '2026-09-25T10:00:00Z',
      completedAt: null,
    };
    expect(currentJourney({ outbox: outbox(), snapshots: snapshots([active]) })).toMatchObject({
      id: journeyId,
      confirmed: true,
    });
    expect(
      currentJourney({
        outbox: outbox([entry({ journeyId, action: 'start', kind: 'commute' })]),
        snapshots: snapshots(),
      }),
    ).toMatchObject({ id: journeyId, kind: 'commute', confirmed: false, startedAt: null });
    // A queued completion ends Journey mode immediately.
    expect(
      currentJourney({
        outbox: outbox([entry({ journeyId, action: 'complete' })]),
        snapshots: snapshots([active]),
      }),
    ).toBeNull();
  });

  it('labels elapsed time and start state', () => {
    const start = '2026-09-25T10:00:00Z';
    expect(elapsedLabel(null, 0)).toBeNull();
    expect(elapsedLabel(start, Date.parse(start) + 5 * 60_000)).toBe('5 min');
    expect(elapsedLabel(start, Date.parse(start) + 125 * 60_000)).toBe('2 h 5 min');
    const queued = {
      id: journeyId,
      kind: 'trip' as const,
      startedAt: null,
      confirmed: false,
      blocked: null,
    };
    expect(startNote(queued, false)).toContain('send when connected');
    expect(startNote({ ...queued, blocked: 'conflict' }, true)).toContain('needs attention');
    expect(startNote({ ...queued, confirmed: true }, true)).toBeNull();
  });

  it('orders from the start without a position and projects with one', () => {
    expect(routeProgress(null, located(null))).toBeNull();
    const fromStart = routeProgress(route, located(null));
    expect(fromStart).toMatchObject({ alongMetres: 0, fromStart: true, offRoute: false });
    expect(routeNote(route, fromStart)).toContain('(from start)');
    const halfway = routeProgress(route, located([80, 12.05]));
    expect(halfway?.fromStart).toBe(false);
    expect(halfway?.remainingMetres).toBeGreaterThan(5000);
    expect(halfway?.remainingMetres).toBeLessThan(6000);
    expect(routeNote(route, halfway)).toContain('to go');
    const off = routeProgress(route, located([80.05, 12.05]));
    expect(off?.offRoute).toBe(true);
    expect(routeNote(route, off)).toContain('off the selected route');
    expect(routeNote(null, null)).toContain('Spots ahead are not available');
  });

  it('describes every location state without asking for permission itself', () => {
    const states: LocationState['status'][] = [
      'idle',
      'not_asked',
      'requesting',
      'denied',
      'blocked',
      'services_off',
      'waiting',
      'weak',
      'ready',
      'unavailable',
    ];
    for (const status of states) expect(positionCopy({ status, fix: null }).text).toBeTruthy();
    expect(positionCopy({ status: 'blocked', fix: null }).action).toBe('settings');
    expect(positionCopy({ status: 'not_asked', fix: null }).text).toContain('stays on this phone');
  });
});

function view(overrides: Partial<JourneyModeViewProps> = {}) {
  const props: JourneyModeViewProps = {
    title: 'Trip in progress',
    elapsed: '5 min',
    startNote: null,
    routeNote: 'Kilambakkam to Trichy · about 5.6 km to go',
    position: positionCopy({ status: 'not_asked', fix: null }),
    map: null,
    mapNotice: null,
    follow: null,
    spotsPanel: null,
    canComplete: true,
    confirmingComplete: false,
    busy: false,
    error: '',
    onClose: vi.fn(),
    onAskComplete: vi.fn(),
    onCancelComplete: vi.fn(),
    onConfirmComplete: vi.fn(),
    onPositionAction: vi.fn(),
    onToggleFollow: vi.fn(),
    ...overrides,
  };
  return { props, tree: nodes(createElement(JourneyModeView, props)) };
}

describe('journey mode view', () => {
  it('has Close and Complete as separate actions; Close never completes', () => {
    const { props, tree } = view();
    (button(tree, 'Close')?.props.onPress as () => void)();
    expect(props.onClose).toHaveBeenCalled();
    expect(props.onConfirmComplete).not.toHaveBeenCalled();
    (button(tree, 'Complete journey')?.props.onPress as () => void)();
    expect(props.onAskComplete).toHaveBeenCalled();
  });

  it('asks for confirmation before completing', () => {
    const { props, tree } = view({ confirmingComplete: true });
    expect(text(tree)).toContain('stored route is removed from this phone');
    expect(button(tree, 'Close')).toBeUndefined();
    (button(tree, 'Yes, complete journey')?.props.onPress as () => void)();
    expect(props.onConfirmComplete).toHaveBeenCalled();
    (button(tree, 'Keep going')?.props.onPress as () => void)();
    expect(props.onCancelComplete).toHaveBeenCalled();
  });

  it('disables completion until the start is confirmed', () => {
    const { tree } = view({ canComplete: false, startNote: 'Journey start saved on this device.' });
    expect(button(tree, 'Complete journey')?.props.disabled).toBe(true);
    expect(text(tree)).toContain('saved on this device');
  });

  it('offers the right position action and never text entry', () => {
    expect(button(view().tree, 'Show my position')).toBeDefined();
    const blocked = view({ position: positionCopy({ status: 'blocked', fix: null }) }).tree;
    expect(button(blocked, 'Open location settings')).toBeDefined();
    const ready = view({ position: positionCopy({ status: 'ready', fix: null }), follow: false });
    expect(button(ready.tree, 'Show my position')).toBeUndefined();
    expect(button(ready.tree, 'Follow me')).toBeDefined();
    for (const tree of [view().tree, blocked, ready.tree])
      expect(tree.some((node) => node.type === 'TextInput')).toBe(false);
  });

  it('shows map notices and hides the Spots slot when there is no panel', () => {
    const { tree } = view({
      mapNotice: 'Map tiles unavailable offline; your route and Spots ahead still work.',
    });
    expect(text(tree)).toContain('Map tiles unavailable offline');
    expect(text(tree)).not.toContain('Spots ahead ·');
  });

  it('keeps every button at least the touch-target height', () => {
    const { tree } = view({ follow: false });
    for (const node of tree.filter((item) => item.type === 'Pressable')) {
      const style = [node.props.style].flat(2).filter(Boolean) as Array<{ minHeight?: number }>;
      expect(style.some((item) => (item.minHeight ?? 0) >= 48)).toBe(true);
    }
  });
});

describe('journey entry points', () => {
  it('bar and Home card open Journey mode', () => {
    const onOpen = vi.fn();
    const bar = nodes(
      createElement(JourneyReturnBar, { title: 'Trip in progress', elapsed: '5 min', onOpen }),
    );
    expect(bar[0]?.props.accessibilityLabel).toBe(
      'Back to journey. Trip in progress, 5 min so far',
    );
    (bar[0]?.props.onPress as () => void)();
    const card = nodes(
      createElement(ActiveJourneyCard, { title: 'Trip in progress', elapsed: null, onOpen }),
    );
    (button(card, 'Open journey map')?.props.onPress as () => void)();
    expect(onOpen).toHaveBeenCalledTimes(2);
  });
});

describe('start with this route', () => {
  const place = (id: string, longitude: number) => ({
    id,
    label: id,
    coordinate: [longitude, 13] as [number, number],
  });
  const state: NativeRoutingState = {
    origin: { text: 'a', selected: place('Kilambakkam', 80), results: null, attribution: null },
    destination: { text: 'b', selected: place('Trichy', 79), results: null, attribution: null },
    mode: 'driving',
    route: {
      provider: 'valhalla',
      calculatedAt: '2026-09-25T10:00:00Z',
      routes: [
        {
          distanceMetres: 1000,
          durationSeconds: 60,
          geometry: [
            [80, 13],
            [79, 13],
          ],
        },
        {
          distanceMetres: 2000,
          durationSeconds: 90,
          geometry: [
            [80, 13],
            [79.5, 13.1],
            [79, 13],
          ],
        },
      ],
    },
    alternative: 1,
    step: 0,
    busy: null,
    failure: null,
    noRoute: false,
  };
  const handlers = {
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
  };

  it('builds the input from the selected alternative and both places', () => {
    expect(plannerJourneyRoute(state)).toMatchObject({
      originLabel: 'Kilambakkam',
      destinationLabel: 'Trichy',
      alternativeIndex: 1,
      route: { distanceMetres: 2000 },
    });
    expect(plannerJourneyRoute({ ...state, route: null })).toBeNull();
    expect(
      plannerJourneyRoute({ ...state, origin: { ...state.origin, selected: null } }),
    ).toBeNull();
  });

  it('is shown only when offered and explains the route stays on the phone', () => {
    const onPress = vi.fn();
    const hidden = nodes(createElement(NativeRoutePlannerView, { ...handlers, state }));
    expect(button(hidden, 'Start trip with this route')).toBeUndefined();
    const shown = nodes(
      createElement(NativeRoutePlannerView, {
        ...handlers,
        state,
        startWithRoute: { disabled: false, onPress },
      }),
    );
    (button(shown, 'Start trip with this route')?.props.onPress as () => void)();
    expect(onPress).toHaveBeenCalled();
    expect(text(shown)).toContain('route stays on this phone');
  });
});
