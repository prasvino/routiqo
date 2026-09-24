import { createElement, isValidElement, type ReactNode } from '../apps/mobile/node_modules/react';
import { expect, it, vi } from 'vitest';
import {
  NativeJournalEditorView,
  type NativeJournalEditorViewProps,
} from '../apps/mobile/src/features/journey/native-journal-editor';
import type { NativeJournalEditorState } from '../apps/mobile/src/features/journey/native-journal-editor-controller';

vi.mock('../apps/mobile/node_modules/react-native', () => ({
  Alert: { alert: vi.fn() },
  KeyboardAvoidingView: 'KeyboardAvoidingView',
  Modal: 'Modal',
  Platform: { OS: 'android' },
  Pressable: 'Pressable',
  ScrollView: 'ScrollView',
  StyleSheet: { create: (styles: unknown) => styles },
  Text: 'Text',
  TextInput: 'TextInput',
  View: 'View',
}));
vi.mock('../apps/mobile/node_modules/react-native-safe-area-context', () => ({
  SafeAreaView: 'SafeAreaView',
}));
vi.mock('../apps/mobile/node_modules/expo-crypto', () => ({ randomUUID: vi.fn() }));
vi.mock('../apps/mobile/src/auth/native-account-provider', () => ({ useNativeAccount: vi.fn() }));

type Node = { type: unknown; props: Record<string, unknown> };
function nodes(value: ReactNode): Node[] {
  if (Array.isArray(value)) return value.flatMap(nodes);
  if (!isValidElement(value)) return [];
  if (typeof value.type === 'function') return nodes(value.type(value.props));
  const props = value.props as Record<string, unknown>;
  return [{ type: value.type, props }, ...nodes(props.children as ReactNode)];
}
const state: NativeJournalEditorState = {
  open: true,
  busy: false,
  title: ' Title ',
  notes: ' Notes\n',
  journal: null,
  draft: null,
  reviewed: null,
  failure: null,
  dirty: false,
  settled: false,
};
function props(
  overrides: Partial<NativeJournalEditorViewProps> = {},
): NativeJournalEditorViewProps {
  return {
    state,
    online: true,
    onTitle: vi.fn(),
    onNotes: vi.fn(),
    onClose: vi.fn(),
    onSaveDraft: vi.fn(),
    onSend: vi.fn(),
    onReview: vi.fn(),
    onUseAccount: vi.fn(),
    ...overrides,
  };
}
function text(tree: Node[]) {
  return tree
    .filter((node) => node.type === 'Text')
    .map((node) =>
      Array.isArray(node.props.children) ? node.props.children.join('') : node.props.children,
    )
    .join(' | ');
}
function action(tree: Node[], label: string) {
  return tree.find(
    (node) =>
      node.type === 'Pressable' &&
      nodes(node.props.children as ReactNode).some((child) => child.props.children === label),
  );
}

it('exposes distinct device and account saves with bounded editable fields', () => {
  const onSaveDraft = vi.fn();
  const onSend = vi.fn();
  const tree = nodes(createElement(NativeJournalEditorView, props({ onSaveDraft, onSend })));
  const fields = tree.filter((node) => node.type === 'TextInput');
  expect(fields.map((field) => field.props.maxLength)).toEqual([120, 4000]);
  expect(fields.map((field) => field.props.value)).toEqual([' Title ', ' Notes\n']);
  (action(tree, 'Save draft on device')?.props.onPress as () => void)();
  (action(tree, 'Save to account')?.props.onPress as () => void)();
  expect(onSaveDraft).toHaveBeenCalledOnce();
  expect(onSend).toHaveBeenCalledOnce();
});

it('distinguishes unsaved typing, offline, storage failure, conflict and settled account save', () => {
  const offline = nodes(createElement(NativeJournalEditorView, props({ online: false })));
  expect(text(offline)).toContain('Offline');
  expect(action(offline, 'Save to account')?.props.disabled).toBe(true);
  expect(action(offline, 'Save draft on device')?.props.disabled).toBe(false);
  const error = nodes(
    createElement(NativeJournalEditorView, props({ state: { ...state, failure: 'storage' } })),
  );
  expect(text(error)).toContain('Could not save this journal on the device');
  const draft = {
    journeyId: '00000000-0000-4000-8000-000000000011',
    title: 'Title',
    notes: 'Notes',
    expectedVersion: 0,
    mutationId: '00000000-0000-4000-8000-000000000001',
  };
  const dirty = nodes(
    createElement(NativeJournalEditorView, props({ state: { ...state, draft, dirty: true } })),
  );
  expect(text(dirty)).toContain('latest changes are unsaved');
  const conflict = nodes(
    createElement(
      NativeJournalEditorView,
      props({ state: { ...state, draft, failure: 'conflict' } }),
    ),
  );
  expect(action(conflict, 'Review latest account journal')).toBeTruthy();
  const settled = nodes(
    createElement(NativeJournalEditorView, props({ state: { ...state, settled: true } })),
  );
  expect(text(settled)).toContain('Saved to your account');
});
