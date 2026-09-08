import { describe, expect, it } from 'vitest';
import {
  createPlanningBackup,
  parsePlanningBackup,
  mergePlanningBackup,
  MAX_BACKUP_BYTES,
} from './backup';
import { emptyPlanningState, type JourneyPlan } from './planning';
const plan: JourneyPlan = {
  id: 'one',
  kind: 'trip',
  origin: 'Chennai',
  destination: 'Pondicherry',
  date: '2026-10-01',
  time: '08:00',
  days: [],
  notes: 'Breakfast',
  createdAt: '2026-09-06T00:00:00Z',
};
const state = { ...emptyPlanningState(), plans: [plan], saved: ['pondicherry'] };
describe('local planning backups', () => {
  it('roundtrips plans and strips unknown fields from imported/exported data', () => {
    const raw = createPlanningBackup({
      ...state,
      plans: [{ ...plan, privateField: 'not-exported' } as JourneyPlan],
    });
    expect(raw).not.toContain('privateField');
    const parsed = JSON.parse(raw);
    parsed.data.plans[0].unexpected = 'not-imported';
    expect(parsePlanningBackup(JSON.stringify(parsed)).data).toEqual(state);
  });
  it('preserves local edits and imports only new ids; repeated restore is idempotent', () => {
    const local = { ...state, plans: [{ ...plan, notes: 'Locally edited' }] };
    const incoming = {
      ...state,
      plans: [plan, { ...plan, id: 'two' }],
      saved: ['pondicherry', 'kodaikanal'],
    };
    const first = mergePlanningBackup(local, incoming);
    expect(first.summary).toEqual({ addedPlans: 1, keptPlans: 1, addedPlaces: 1 });
    expect(first.state.plans[0]?.notes).toBe('Locally edited');
    expect(mergePlanningBackup(first.state, incoming).state).toEqual(first.state);
    expect(local.plans).toHaveLength(1);
  });
  it('rejects malformed, incompatible, duplicate-id and oversized backups', () => {
    const good = JSON.parse(createPlanningBackup(state));
    const invalid = [
      '{',
      'null',
      '[]',
      JSON.stringify({ ...good, version: 2 }),
      JSON.stringify({ ...good, data: { ...state, plans: [plan, plan] } }),
      JSON.stringify({ ...good, data: { ...state, plans: [{ ...plan, date: '2026-02-31' }] } }),
      ' '.repeat(MAX_BACKUP_BYTES + 1),
    ];
    for (const raw of invalid) expect(() => parsePlanningBackup(raw)).toThrow();
  });
  it('refuses capacity overflow without mutating either state', () => {
    const full = {
      ...state,
      plans: Array.from({ length: 100 }, (_, id) => ({ ...plan, id: String(id) })),
    };
    expect(() => mergePlanningBackup(full, state)).toThrow('exceed');
    expect(full.plans).toHaveLength(100);
    expect(state.plans).toHaveLength(1);
  });
  it('allows an empty backup without deleting current plans', () => {
    expect(mergePlanningBackup(state, emptyPlanningState()).state).toEqual(state);
  });
  it('bounds multibyte file size and saved-place merges', () => {
    expect(() => parsePlanningBackup('界'.repeat(180000))).toThrow('512 KB');
    const full = {
      ...emptyPlanningState(),
      saved: Array.from({ length: 100 }, (_, index) => 'place-' + index),
    };
    expect(() => mergePlanningBackup(full, state)).toThrow('exceed');
    expect(full.saved).toHaveLength(100);
  });
});
