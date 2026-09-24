import { createElement, isValidElement, type ReactNode } from '../apps/mobile/node_modules/react';
import { expect, it, vi } from 'vitest';
import type { NativeHistoryPage } from '../apps/mobile/src/features/journey/native-history';
import type { NativeJournalState } from '../apps/mobile/src/features/journey/native-journal-controller';

vi.mock('../apps/mobile/node_modules/react-native', () => ({
  Pressable: 'Pressable',
  Text: 'Text',
  View: 'View',
  StyleSheet: { create: (styles: unknown) => styles },
}));
vi.mock('../apps/mobile/src/auth/native-account-provider', () => ({ useNativeAccount: vi.fn() }));

import {
  NativeJourneyHistoryView,
  type NativeJourneyHistoryViewProps,
} from '../apps/mobile/src/features/journey/native-journey-history';

const stamp = '2026-09-12T12:00:00.000000Z';
const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const page: NativeHistoryPage = {
  journeys: [
    { id: id(1), kind: 'trip', status: 'completed', startedAt: stamp, completedAt: stamp },
    { id: id(2), kind: 'trip', status: 'active', startedAt: stamp, completedAt: null },
    { id: id(3), kind: 'commute', status: 'completed', startedAt: stamp, completedAt: stamp },
  ],
  next: null,
};
const empty: NativeJournalState = { selectedId: null, journal: null, busy: false, failure: null };
function props(
  overrides: Partial<NativeJourneyHistoryViewProps> = {},
): NativeJourneyHistoryViewProps {
  return {
    page,
    pageLabel: 'Earlier journeys',
    offline: false,
    busy: false,
    error: false,
    authenticationRequired: false,
    journal: empty,
    onLatest: vi.fn(),
    onEarlier: vi.fn(),
    onRetry: vi.fn(),
    onOpenJournal: vi.fn(),
    onCloseJournal: vi.fn(),
    onRetryJournal: vi.fn(),
    ...overrides,
  };
}
type Node = { type: unknown; props: Record<string, unknown> };
function nodes(value: ReactNode): Node[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!isValidElement(value)) return [];
  if (typeof value.type === 'function') return nodes(value.type(value.props));
  const props = value.props as Record<string, unknown>;
  return [{ type: value.type, props }, ...nodes(props.children as ReactNode)];
}
function visibleText(tree: Node[]) {
  return tree
    .filter((node) => node.type === 'Text')
    .map((node) =>
      Array.isArray(node.props.children) ? node.props.children.join('') : node.props.children,
    )
    .join(' | ');
}
function buttons(tree: Node[]) {
  return tree.filter((node) => node.type === 'Pressable');
}

it('exposes one labelled journal action only for a completed trip and preserves history on back', () => {
  const onOpenJournal = vi.fn();
  const onCloseJournal = vi.fn();
  const history = nodes(
    createElement(NativeJourneyHistoryView, props({ onOpenJournal, onCloseJournal })),
  );
  const actions = buttons(history).filter((node) =>
    String(node.props.accessibilityLabel).startsWith('View journal for trip'),
  );
  expect(actions).toHaveLength(1);
  expect(actions[0]?.props.accessibilityRole).toBe('button');
  (actions[0]?.props.onPress as () => void)();
  expect(onOpenJournal).toHaveBeenCalledWith(id(1));
  const journal = nodes(
    createElement(
      NativeJourneyHistoryView,
      props({
        onCloseJournal,
        journal: {
          ...empty,
          selectedId: id(1),
          journal: {
            journey: page.journeys[0]!,
            annotation: {
              title: '',
              notes: '  A note\n  kept as written  ',
              version: 1,
              updatedAt: stamp,
            },
          },
        },
      }),
    ),
  );
  expect(visibleText(journal)).toContain('Trip journal');
  expect(visibleText(journal)).toContain('  A note\n  kept as written  ');
  expect(visibleText(journal)).toContain('Completed');
  const back = buttons(journal).find((node) =>
    nodes(node.props.children as ReactNode).some(
      (child) => child.props.children === 'Back to history',
    ),
  );
  (back?.props.onPress as () => void)();
  expect(onCloseJournal).toHaveBeenCalledOnce();
  expect(visibleText(history)).toContain('Earlier journeys');
});

it('renders loading, offline retained, empty, missing, session and retry states', () => {
  const selected = { ...empty, selectedId: id(1) };
  const text = (journal: NativeJournalState, offline = false) =>
    visibleText(nodes(createElement(NativeJourneyHistoryView, props({ journal, offline }))));
  expect(text({ ...selected, busy: true })).toContain('Loading trip journal');
  expect(text(selected, true)).toContain('Connect to view this journal');
  expect(text({ ...selected, failure: 'missing' })).toContain('no longer available');
  expect(text({ ...selected, failure: 'session' })).toContain('Sign in again');
  const retryTree = nodes(
    createElement(
      NativeJourneyHistoryView,
      props({ journal: { ...selected, failure: 'unavailable' } }),
    ),
  );
  expect(visibleText(retryTree)).toContain('unavailable');
  expect(
    buttons(retryTree).some((node) =>
      nodes(node.props.children as ReactNode).some(
        (child) => child.props.children === 'Retry trip journal',
      ),
    ),
  ).toBe(true);
  const loaded = {
    ...selected,
    journal: {
      journey: page.journeys[0]!,
      annotation: { title: '', notes: '', version: 0, updatedAt: null },
    },
  };
  expect(text(loaded)).toContain('No notes have been added');
  expect(text(loaded, true)).toContain('already loaded in this session');
});
