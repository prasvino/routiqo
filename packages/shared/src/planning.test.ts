import { describe, expect, it } from 'vitest';
import {
  emptyPlanningState,
  readPlanningState,
  upsertPlan,
  toggleSaved,
  validatePlan,
  type JourneyPlan,
} from './planning';
import { searchDestinations } from './catalog';
const plan: JourneyPlan = {
  id: 'p-1',
  kind: 'trip',
  origin: 'Chennai',
  destination: 'Pondicherry',
  date: '2026-10-01',
  time: '08:00',
  days: [],
  notes: '',
  createdAt: '2026-09-06T00:00:00Z',
};
describe('local planning boundary', () => {
  it('round-trips a plan and edits without duplicating it', () => {
    const state = upsertPlan(emptyPlanningState(), plan);
    const edited = upsertPlan(readPlanningState(JSON.stringify(state)), {
      ...plan,
      notes: 'Breakfast stop',
    });
    expect(edited.plans).toHaveLength(1);
    expect(edited.plans[0]?.notes).toBe('Breakfast stop');
  });
  it('rejects malformed, future-version, duplicate-id and invalid persisted data', () => {
    for (const raw of [
      '{',
      '{"version":2,"plans":[],"saved":[]}',
      JSON.stringify({ version: 1, plans: [plan, plan], saved: [] }),
      JSON.stringify({ version: 1, plans: [{ ...plan, date: '2026-02-31' }], saved: [] }),
    ])
      expect(() => readPlanningState(raw)).toThrow();
  });
  it('requires distinct locations and recurring days; rejects invalid time', () => {
    expect(validatePlan({ ...plan, origin: ' pondicherry ' })).toBe(false);
    expect(validatePlan({ ...plan, kind: 'commute' })).toBe(false);
    expect(validatePlan({ ...plan, time: '25:00' })).toBe(false);
    expect(validatePlan({ ...plan, kind: 'commute', days: [1, 2, 3] })).toBe(true);
  });
  it('saves once and removes on a second toggle', () => {
    const state = toggleSaved(emptyPlanningState(), 'pondicherry');
    expect(
      readPlanningState(JSON.stringify({ ...state, saved: ['pondicherry', 'pondicherry'] })).saved,
    ).toEqual(['pondicherry']);
    expect(toggleSaved(state, 'pondicherry').saved).toEqual([]);
  });
  it('bounds local plan storage', () => {
    const state = {
      version: 1 as const,
      plans: Array.from({ length: 100 }, (_, i) => ({ ...plan, id: String(i) })),
      saved: [],
    };
    expect(() => upsertPlan(state, plan)).toThrow('100');
  });
  it('searches case-insensitively and combines filters', () => {
    expect(searchDestinations(' PONDICHERRY ')).toHaveLength(1);
    expect(searchDestinations('Pondicherry', 'Hills')).toHaveLength(0);
    expect(searchDestinations('', 'Hills')).toHaveLength(2);
  });
});
