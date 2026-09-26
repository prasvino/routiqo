import { isValidElement, type ReactNode } from '../apps/mobile/node_modules/react';
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../apps/mobile/node_modules/react-native', () => ({
  AppState: { currentState: 'active', addEventListener: vi.fn() },
  Alert: { alert: vi.fn() },
  Modal: 'Modal',
  Pressable: 'Pressable',
  Switch: 'Switch',
  Text: 'Text',
  TextInput: 'TextInput',
  View: 'View',
  StyleSheet: { create: (styles: unknown) => styles },
}));
vi.mock('../apps/mobile/node_modules/expo-crypto', () => ({ randomUUID: vi.fn() }));
vi.mock('../apps/mobile/node_modules/expo-sqlite', () => ({ useSQLiteContext: vi.fn() }));
vi.mock('../apps/mobile/src/auth/native-account-provider', () => ({ useNativeAccount: vi.fn() }));

import { spotDetailModel } from '../apps/mobile/src/features/spots/spot-detail-model';
import {
  GhostModeSwitch,
  SpotDetailContent,
  SpotReportSheet,
} from '../apps/mobile/src/features/spots/spot-detail-view';
import {
  NoJourneyForSpot,
  contributionMessage,
} from '../apps/mobile/src/features/spots/spot-contribution-controls';
import {
  GhostModeOn,
  switchGhostMode,
  type GhostSwitchPorts,
} from '../apps/mobile/src/features/spots/spot-contributions-provider';
import { NativeSpotsError } from '../apps/mobile/src/features/spots/native-spots';
import {
  SpotOutboxFull,
  type QueuedSpotContribution,
} from '../packages/shared/src/spot-contributions';
import type { SpotActivityEntry } from '../packages/shared/src/spots';

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
    .join(' | ');
type PressableProps = {
  accessibilityLabel: string;
  accessibilityRole: string;
  disabled?: boolean;
  onPress(): void;
};
const buttons = (tree: Node[]) =>
  tree.filter((node) => node.type === 'Pressable') as { props: PressableProps }[];
const button = (tree: Node[], label: string) =>
  buttons(tree).find((node) => node.props.accessibilityLabel === label)!;

const id = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const NOW = Date.parse('2026-11-05T06:30:00Z');
const entry: SpotActivityEntry = {
  id: id(1),
  state: 'live',
  alertIds: [],
  signals: [
    {
      ref: id(10),
      category: 'traffic',
      value: 'slow',
      values: [
        { value: 'slow', reports: 2 },
        { value: 'moving', reports: 1 },
      ],
      latestAt: '2026-11-05T06:18:00Z',
      stillTrue: 1,
      viewerVote: 'still_true',
    },
  ],
  posts: [
    {
      ref: id(20),
      alias: 'Calm Auto',
      text: 'Lane 3 moving',
      type: 'traffic',
      capturedAt: '2026-11-05T06:25:00Z',
      expiresAt: '2026-11-05T07:55:00Z',
      stillTrue: 0,
      viewerVote: null,
      mine: false,
    },
    {
      ref: id(21),
      alias: 'Blue Kite',
      text: 'My own tip',
      type: 'place',
      capturedAt: '2026-11-05T06:00:00Z',
      expiresAt: '2026-11-06T06:00:00Z',
      stillTrue: 2,
      viewerVote: null,
      mine: true,
    },
    {
      ref: id(22),
      alias: 'Old Bus',
      text: 'Already expired',
      type: 'traffic',
      capturedAt: '2026-11-05T04:00:00Z',
      expiresAt: '2026-11-05T05:30:00Z',
      stillTrue: 0,
      viewerVote: null,
      mine: false,
    },
  ],
  postsTruncated: false,
  highlights: [{ text: 'Clean restrooms at the back', createdAt: '2026-11-03T06:00:00Z' }],
};
const spot = { kind: 'toll' as const, categories: ['traffic' as const, 'queue' as const] };
const queued: QueuedSpotContribution = {
  kind: 'post',
  clientKey: id(30),
  spotId: id(1),
  journeyId: id(40),
  capturedAt: '2026-11-05T06:29:00Z',
  type: 'traffic',
  text: 'Waiting for signal to come back',
  attempts: 0,
  nextAttemptAt: NOW,
};
const actions = () => ({
  onVote: vi.fn(),
  onReport: vi.fn(),
  onBlock: vi.fn(),
  onDelete: vi.fn(),
  onSignal: vi.fn(),
  onWrite: vi.fn(),
});

describe('Spot detail model', () => {
  it('shows unattributed summaries, current posts, highlights and queued items', () => {
    const model = spotDetailModel({
      spot,
      entry,
      now: NOW,
      contributions: { ghost: false, journey: true, queued: [queued] },
    });
    expect(model.signals[0]).toMatchObject({
      category: 'Traffic',
      summary: 'Slow · 2 reports, Moving · 1 · latest 12 min ago',
      stillTrue: 'Still true · 1',
      viewerVote: 'still_true',
      canAct: true,
    });
    expect(model.posts.map((post) => post.byline)).toEqual([
      'Calm Auto · 5 min ago',
      'You · 30 min ago',
    ]);
    expect(model.highlights).toEqual([
      { text: 'Clean restrooms at the back', when: 'Traveller tip · 2 days ago' },
    ]);
    expect(model.contribute).toMatchObject({
      notice: null,
      enabled: true,
      defaultPostType: 'traffic',
      waiting: ['Waiting to send: “Waiting for signal to come back”'],
    });
    expect(
      model.contribute!.choices.map((choice) => choice.values.map((value) => value.label)),
    ).toEqual([
      ['Moving', 'Slow', 'Stopped'],
      ['Under 5 min', '5–15 min', '15–30 min', 'Over 30 min'],
    ]);
  });

  it('disables sending in Ghost Mode, without a journey, and when contributions are off', () => {
    const ghost = spotDetailModel({
      spot,
      entry,
      now: NOW,
      contributions: { ghost: true, journey: true, queued: [] },
    });
    expect(ghost.contribute).toMatchObject({
      enabled: false,
      notice: 'Ghost Mode is on. Nothing is sent from this phone.',
    });
    expect(ghost.posts.every((post) => !post.canAct)).toBe(true);
    const noJourney = spotDetailModel({
      spot,
      entry,
      now: NOW,
      contributions: { ghost: false, journey: false, queued: [] },
    });
    expect(noJourney.contribute).toMatchObject({
      enabled: false,
      notice: 'Posting needs an active journey.',
    });
    const off = spotDetailModel({ spot, entry, now: NOW, contributions: null });
    expect(off.contribute).toBeNull();
    expect(off.posts.every((post) => !post.canAct)).toBe(true);
    const empty = spotDetailModel({
      spot,
      entry: { ...entry, signals: [], posts: [], highlights: [] },
      now: NOW,
      contributions: null,
    });
    expect(empty.postsNote).toBe('No recent posts or reports here.');
  });
});

describe('Spot detail view', () => {
  const render = (ghost: boolean | null) => {
    const model = spotDetailModel({
      spot,
      entry,
      now: NOW,
      contributions: { ghost, journey: true, queued: [queued] },
    });
    const handlers = actions();
    const tree = nodes(SpotDetailContent({ ...model, ...handlers, busy: false, message: null }));
    return { tree, handlers };
  };

  it('offers votes, Report and Block on others, and only Delete on your own post', () => {
    const { tree, handlers } = render(false);
    expect(text(tree)).toContain('Calm Auto · 5 min ago');
    expect(text(tree)).not.toContain('Already expired');
    expect(text(tree)).toContain('Waiting to send');
    button(tree, 'Still true: this post').props.onPress();
    expect(handlers.onVote).toHaveBeenCalledWith(id(20), 'still_true');
    button(tree, 'Block the author of this post').props.onPress();
    expect(handlers.onBlock).toHaveBeenCalledWith(id(20));
    button(tree, 'Report the Traffic summary').props.onPress();
    expect(handlers.onReport).toHaveBeenCalledWith(id(10));
    button(tree, 'Report Traffic: Stopped').props.onPress();
    expect(handlers.onSignal).toHaveBeenCalledWith('traffic', 'stopped');
    button(tree, 'Delete my post').props.onPress();
    expect(handlers.onDelete).toHaveBeenCalledWith(id(21));
    // Summaries are unattributed: nothing on them can block anyone.
    expect(
      buttons(tree).filter((node) => /Block/.test(node.props.accessibilityLabel)),
    ).toHaveLength(1);
    expect(buttons(tree).every((node) => node.props.accessibilityRole === 'button')).toBe(true);
  });

  it('disables every sending control in Ghost Mode but keeps Delete my post', () => {
    const { tree } = render(true);
    for (const label of [
      'Still true: this post',
      'Report this post',
      'Block the author of this post',
      'Report Traffic: Slow',
      'Write a post',
      'Report the Traffic summary',
    ])
      expect(button(tree, label).props.disabled).toBe(true);
    expect(button(tree, 'Delete my post').props.disabled).toBe(false);
    expect(text(tree)).toContain('Ghost Mode is on');
  });

  it('renders the Ghost Mode switch and report reasons plainly', () => {
    const onChange = vi.fn();
    const ghost = nodes(GhostModeSwitch({ ghost: false, onChange }));
    const toggle = ghost.find((node) => node.type === 'Switch')!;
    expect(toggle.props).toMatchObject({
      accessibilityLabel: 'Ghost Mode',
      value: false,
      disabled: false,
    });
    (toggle.props.onValueChange as (on: boolean) => void)(true);
    expect(onChange).toHaveBeenCalledWith(true);
    expect(
      nodes(GhostModeSwitch({ ghost: null, onChange })).find((node) => node.type === 'Switch')!
        .props.disabled,
    ).toBe(true);
    const onPick = vi.fn();
    const sheet = nodes(SpotReportSheet({ visible: true, onPick, onClose: vi.fn() }));
    expect(buttons(sheet).map((node) => node.props.accessibilityLabel)).toEqual([
      'Not accurate',
      'Abuse or hate',
      'Spam or advertising',
      'Personal information',
      'Unsafe',
      'Cancel',
    ]);
    button(sheet, 'Spam or advertising').props.onPress();
    expect(onPick).toHaveBeenCalledWith('spam');
  });
});

describe('contribution messages', () => {
  it('explain refusals in plain words, never with a status code', () => {
    expect(contributionMessage(new GhostModeOn(), 'vote')).toBe(
      'Ghost Mode is on. Nothing was sent.',
    );
    expect(contributionMessage(new SpotOutboxFull(), 'queue')).toMatch(/waiting to send/);
    expect(contributionMessage(new NativeSpotsError('conflict', 409), 'report')).toBe(
      'You already reported this.',
    );
    expect(contributionMessage(new NativeSpotsError('conflict', 409), 'block')).toBe(
      'Your block list is full.',
    );
    expect(contributionMessage(new NativeSpotsError('rate-limited', 429), 'report')).toBe(
      'You have reached the report limit for now.',
    );
    expect(contributionMessage(new NativeSpotsError('forbidden', 403), 'vote')).toBe(
      "You can't vote on your own post.",
    );
    expect(contributionMessage(new Error('boom'), 'delete')).not.toMatch(/\d{3}/);
    expect(contributionMessage(new NativeSpotsError('conflict', 409), 'vote')).toBe(
      'Voting needs an active journey.',
    );
    expect(contributionMessage(new NoJourneyForSpot(), 'queue')).toBe(
      'Posting needs an active journey.',
    );
  });

  it('keeps sources free of sample content, logging and position data', () => {
    for (const file of [
      'spot-detail-model.ts',
      'spot-detail-view.tsx',
      'spot-contribution-controls.tsx',
      'spot-contributions-provider.tsx',
      'spot-outbox-controller.ts',
    ]) {
      const source = readFileSync(`apps/mobile/src/features/spots/${file}`, 'utf8');
      expect(source).not.toMatch(/sample|fixture|mock|console\./i);
      expect(source).not.toMatch(/latitude|longitude|coordinate|getCurrentPosition/i);
    }
    expect(readFileSync('apps/mobile/.env.example', 'utf8')).toContain(
      'EXPO_PUBLIC_ROUTIQO_SPOT_CONTRIBUTIONS_ENABLED=false',
    );
  });
});

describe('Ghost Mode switch', () => {
  const ports = (overrides: Partial<GhostSwitchPorts> = {}) => {
    const calls: string[] = [];
    const latch: boolean[] = [];
    const base: GhostSwitchPorts = {
      latch: (on) => {
        latch.push(on);
        calls.push(`latch:${on}`);
      },
      stopSending: () => {
        calls.push('stop');
        return false;
      },
      save: async (on) => {
        calls.push(`save:${on}`);
      },
      clearQueue: async () => {
        calls.push('clear');
      },
      reload: async () => {
        calls.push('reload');
      },
    };
    return { calls, latch, ports: { ...base, ...overrides } };
  };

  it('latches and stops sending before it touches storage', async () => {
    const { calls, ports: p } = ports();
    await expect(switchGhostMode(true, p)).resolves.toBeNull();
    expect(calls).toEqual(['latch:true', 'stop', 'save:true', 'reload']);
  });

  it('says so when something was already on its way', async () => {
    const { ports: p } = ports({ stopSending: () => true });
    await expect(switchGhostMode(true, p)).resolves.toMatch(/may still arrive/);
  });

  it('stays on and clears the queue anyway when storing fails', async () => {
    const {
      calls,
      latch,
      ports: p,
    } = ports({
      save: async () => {
        throw new Error('disk');
      },
    });
    await expect(switchGhostMode(true, p)).resolves.toMatch(/couldn't be saved/);
    expect(latch).toEqual([true]);
    expect(calls).toContain('clear');
  });

  it('turns off only after storing succeeds', async () => {
    const failing = ports({
      save: async () => {
        throw new Error('disk');
      },
    });
    await expect(switchGhostMode(false, failing.ports)).resolves.toMatch(/stays on/);
    expect(failing.latch).toEqual([]);
    const { latch, ports: p } = ports();
    await expect(switchGhostMode(false, p)).resolves.toBeNull();
    expect(latch).toEqual([false]);
  });
});
