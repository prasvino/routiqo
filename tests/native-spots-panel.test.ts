import { createElement, isValidElement, type ReactNode } from '../apps/mobile/node_modules/react';
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../apps/mobile/node_modules/react-native', () => ({
  AppState: { currentState: 'active', addEventListener: vi.fn() },
  Pressable: 'Pressable',
  Text: 'Text',
  TextInput: 'TextInput',
  View: 'View',
  StyleSheet: { create: (styles: unknown) => styles },
}));

import {
  spotsEnabled,
  spotsPanelModel,
  type SpotsPanelInput,
} from '../apps/mobile/src/features/spots/spots-model';
import { SpotsPanelView } from '../apps/mobile/src/features/spots/spots-panel-view';
import type { SpotAhead, SpotActivity } from '../packages/shared/src/spots';

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

const version = '00000000-0000-4000-8000-0000000000aa';
const spotId = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const ahead = (count: number, first = 400): SpotAhead[] =>
  Array.from({ length: count }, (_, index) => ({
    spot: {
      id: spotId(index + 1),
      name: `Toll ${index + 1}`,
      nameTa: 'சுங்கம்',
      kind: 'toll',
      coordinate: [80, 12 + index * 0.01],
      district: 'villupuram',
      corridors: ['gst-trunk'],
      categories: ['traffic'],
    },
    aheadMetres: first + index * 5_000,
    here: index === 0 && first <= 200,
  }));
const activity = (states: Array<'live' | 'fading' | 'quiet'>): SpotActivity => ({
  serverTime: '2026-11-05T06:30:00Z',
  catalogVersion: version,
  spots: states.map((state, index) => ({ id: spotId(index + 1), state, alertIds: [] })),
});
const input = (overrides: Partial<SpotsPanelInput> = {}): SpotsPanelInput => ({
  catalog: { loaded: true, available: true, refreshing: false },
  hasRoute: true,
  matchedCount: 8,
  ahead: ahead(8),
  fromStart: false,
  activity: { status: 'ready', activity: activity(['quiet']), receivedAt: 0 },
  online: true,
  confirmed: true,
  now: 30_000,
  size: 'collapsed',
  selectedId: null,
  ...overrides,
});
const render = (overrides: Partial<SpotsPanelInput> = {}) =>
  nodes(
    createElement(SpotsPanelView, {
      ...spotsPanelModel(input(overrides)),
      onSelect: vi.fn(),
      onResize: vi.fn(),
    }),
  );

describe('Spots flag', () => {
  it('is on only for the exact value true', () => {
    expect(spotsEnabled('true')).toBe(true);
    for (const value of ['TRUE', 'True', '1', 'yes', ' true', 'true ', '', undefined])
      expect(spotsEnabled(value)).toBe(false);
  });

  it('keeps the panel out of Journey mode and never ships sample Spots', () => {
    const screen = readFileSync('apps/mobile/src/features/journey/journey-mode-screen.tsx', 'utf8');
    expect(screen).toContain('const spotsOn = enabled && spotsEnabled();');
    expect(screen).toMatch(/spotsPanel=\{\s*spotsOn \? \(/);
    for (const file of [
      'spots-model.ts',
      'spots-panel.tsx',
      'spots-panel-view.tsx',
      'spots-provider.tsx',
    ])
      expect(readFileSync(`apps/mobile/src/features/spots/${file}`, 'utf8')).not.toMatch(
        /sample|placeholder|fixture|mock/i,
      );
    expect(readFileSync('apps/mobile/.env.example', 'utf8')).toContain(
      'EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED=false',
    );
  });
});

describe('Spots-ahead panel states', () => {
  it('covers catalog, route and empty states honestly', () => {
    expect(
      text(render({ catalog: { loaded: false, available: false, refreshing: false } })),
    ).toContain('Loading Spots…');
    expect(
      text(render({ catalog: { loaded: true, available: false, refreshing: false } })),
    ).toContain('Spots will appear once the Spot list downloads.');
    expect(
      text(render({ catalog: { loaded: true, available: false, refreshing: true } })),
    ).toContain('Downloading the Spot list…');
    expect(text(render({ hasRoute: false }))).toContain(
      'Spots ahead need a route chosen when the journey starts.',
    );
    expect(text(render({ matchedCount: 0, ahead: [] }))).toContain('No Spots on this route.');
    expect(text(render({ ahead: [] }))).toContain('No more Spots ahead on this route.');
  });

  it('covers activity states: quiet, offline, unconfirmed, rate limited, unavailable, loading', () => {
    expect(text(render())).toContain(
      'No recent reports on the Spots ahead. That does not mean the road is clear.',
    );
    expect(text(render({ online: false, now: 14 * 60_000 }))).toContain(
      'Offline. Showing the last update.',
    );
    expect(text(render({ online: false, now: 14 * 60_000 }))).toContain('Last updated 14 min ago');
    expect(
      text(
        render({ online: false, activity: { status: 'idle', activity: null, receivedAt: null } }),
      ),
    ).toContain('Offline. Spot updates resume when you are back online.');
    expect(text(render({ confirmed: false }))).toContain(
      'Spot updates start once your journey reaches the server.',
    );
    const limited = {
      status: 'rate-limited' as const,
      activity: activity(['quiet']),
      receivedAt: 0,
    };
    expect(text(render({ activity: limited, now: 3 * 60_000 }))).toContain(
      'Updates paused briefly.',
    );
    expect(text(render({ activity: limited, now: 3 * 60_000 }))).toContain(
      'Last updated 3 min ago',
    );
    expect(text(render({ activity: { ...limited, status: 'unavailable' } }))).toContain(
      'Spot updates are unavailable right now.',
    );
    expect(
      text(render({ activity: { status: 'loading', activity: null, receivedAt: null } })),
    ).toContain('Checking the Spots ahead…');
    // Fresh data shows no staleness label; old data does even when the last request succeeded.
    expect(text(render())).not.toContain('Last updated');
    expect(text(render({ now: 3 * 60_000 }))).toContain('Last updated 3 min ago');
  });

  it('shows rows with bilingual names, distance, state chip and one screen-reader label', () => {
    const tree = render({
      activity: { status: 'ready', activity: activity(['live']), receivedAt: 0 },
    });
    const all = text(tree);
    expect(all).toContain('Toll 1');
    expect(all).toContain('சுங்கம்');
    expect(all).toContain('Toll plaza · 400 m ahead');
    expect(all).toContain('Live');
    expect(all).not.toContain('No recent reports on the Spots ahead');
    const row = tree.find((node) => node.type === 'Pressable' && node.props.accessibilityLabel);
    expect(row?.props.accessibilityLabel).toBe('Toll 1, சுங்கம், Toll plaza, 400 m ahead, Live');
    expect(text(render({ fromStart: true }))).toContain('400 m ahead (from start)');
    expect(text(render({ ahead: ahead(2, 100) }))).toContain('Toll plaza · Here');
  });

  it('shows no state chip before the first activity response, never inventing activity', () => {
    const tree = render({ activity: { status: 'idle', activity: null, receivedAt: null } });
    expect(text(tree)).not.toMatch(/Live|Earlier today|No recent reports/);
  });

  it('grows from the next Spot to five to all 20, with 48 dp controls and no text entry', () => {
    const many = ahead(20);
    const rows = (size: SpotsPanelInput['size']) =>
      render({ ahead: many, size }).filter(
        (node) => node.type === 'Pressable' && node.props.accessibilityLabel,
      );
    expect(rows('collapsed')).toHaveLength(1);
    expect(rows('half')).toHaveLength(5);
    expect(rows('full')).toHaveLength(20);
    expect(spotsPanelModel(input({ ahead: many })).sizeAction).toEqual({
      label: 'Show more Spots',
      next: 'half',
    });
    expect(spotsPanelModel(input({ ahead: many, size: 'half' })).sizeAction).toEqual({
      label: 'Show all 20 Spots',
      next: 'full',
    });
    expect(spotsPanelModel(input({ ahead: many, size: 'full' })).sizeAction).toEqual({
      label: 'Show fewer Spots',
      next: 'collapsed',
    });
    expect(spotsPanelModel(input({ ahead: ahead(1) })).sizeAction).toBeNull();
    const tree = render({ ahead: many, size: 'half' });
    for (const control of tree.filter((node) => node.type === 'Pressable')) {
      const styles = [control.props.style].flat() as Array<{ minHeight?: number } | false>;
      expect(styles.some((style) => style && (style.minHeight ?? 0) >= 48)).toBe(true);
    }
    expect(tree.some((node) => node.type === 'TextInput')).toBe(false);
  });

  it('expands a Spot detail that never implies the road is clear', () => {
    const tree = render({ selectedId: spotId(1) });
    expect(text(tree)).toContain('Toll plaza · Villupuram district');
    expect(text(tree)).toContain('No recent reports. This does not mean the road is clear.');
    const row = tree.find((node) => node.type === 'Pressable' && node.props.accessibilityLabel);
    expect(row?.props.accessibilityState).toEqual({ expanded: true });
  });
});
