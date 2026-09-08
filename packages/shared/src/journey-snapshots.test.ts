import { describe, expect, it } from 'vitest';
import { readServerJourney, readJourneySnapshots, recordJourneyResult } from './journey-snapshots';
const owner = '00000000-0000-4000-8000-000000000001';
const id = '00000000-0000-4000-8000-000000000002';
const active = {
  id,
  kind: 'trip',
  status: 'active',
  startedAt: '2026-09-08T12:00:00.123456Z',
  completedAt: null,
};
const command = { action: 'start' as const, journeyId: id, kind: 'trip' as const };
describe('validated journey snapshots', () => {
  it('keeps exact microseconds and discards unknown fields', () => {
    expect(readServerJourney({ ...active, credential: 'discard', ownerId: 'discard' })).toEqual(
      active,
    );
    expect(readServerJourney({ ...active, startedAt: '2026-09-08T12:00:00Z' }).startedAt).toBe(
      '2026-09-08T12:00:00.000000Z',
    );
  });
  it('rejects invalid dates and lifecycle transitions at microsecond precision', () => {
    for (const startedAt of [
      '2026-02-30T12:00:00Z',
      '2026-09-08T24:00:00Z',
      '2026-09-08T12:00:00.1234567Z',
      '0000-01-01T00:00:00Z',
    ])
      expect(() => readServerJourney({ ...active, startedAt })).toThrow();
    expect(() =>
      readServerJourney({
        ...active,
        status: 'completed',
        completedAt: '2026-09-08T12:00:00.123455Z',
      }),
    ).toThrow();
    expect(() => readServerJourney({ ...active, completedAt: active.startedAt })).toThrow();
    expect(() => readServerJourney({ ...active, status: 'completed' })).toThrow();
  });
  it('matches response identity and rejects regression of completed records', () => {
    let state = recordJourneyResult(readJourneySnapshots(null, owner), command, active);
    expect(() =>
      recordJourneyResult(state, { action: 'complete', journeyId: id }, active),
    ).toThrow();
    expect(() => recordJourneyResult(state, { ...command, kind: 'commute' }, active)).toThrow();
    expect(() => recordJourneyResult(state, { ...command, journeyId: owner }, active)).toThrow();
    const completed = {
      ...active,
      status: 'completed',
      completedAt: '2026-09-08T12:00:01.000000Z',
    };
    state = recordJourneyResult(state, { action: 'complete', journeyId: id }, completed);
    expect(recordJourneyResult(state, command, completed)).toEqual(state);
    expect(() => recordJourneyResult(state, command, active)).toThrow();
    expect(() =>
      recordJourneyResult(state, command, { ...completed, completedAt: '2026-09-08T12:00:02Z' }),
    ).toThrow();
  });
  it('prunes only old completed records and retains the acknowledged result', () => {
    const journeys = Array.from({ length: 100 }, (_, index) =>
      readServerJourney({
        ...active,
        id: `10000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
        status: 'completed',
        completedAt: '2026-09-08T12:00:01Z',
      }),
    );
    const state = readJourneySnapshots(
      JSON.stringify({ version: 1, accountId: owner, journeys }),
      owner,
    );
    const next = recordJourneyResult(state, command, active);
    expect(next.journeys).toHaveLength(100);
    expect(next.journeys.some((item) => item.id === id)).toBe(true);
    expect(() =>
      recordJourneyResult(
        {
          ...state,
          journeys: journeys.map((item) => ({ ...item, status: 'active', completedAt: null })),
        },
        command,
        active,
      ),
    ).toThrow();
  });
  it('rejects corrupt, oversized and wrong-account caches', () => {
    expect(() => readJourneySnapshots('{broken', owner)).toThrow();
    expect(() => readJourneySnapshots(' '.repeat(65537), owner)).toThrow();
    expect(() =>
      readJourneySnapshots(JSON.stringify({ version: 1, accountId: id, journeys: [] }), owner),
    ).toThrow();
    expect(() =>
      readJourneySnapshots(
        JSON.stringify({ version: 1, accountId: owner, journeys: [active, active] }),
        owner,
      ),
    ).toThrow();
  });
});
