import { describe, expect, it } from 'vitest';
import { updateStoredPlans } from '../apps/web/lib/planning-storage';
import { emptyPlanningState, mergePlanningBackup } from '../packages/shared/src';

describe('planning storage failures', () => {
  it('reads the latest persisted state before merging a backup', () => {
    let persisted = JSON.stringify({ ...emptyPlanningState(), saved: ['kodaikanal'] });
    const storage = {
      getItem: () => persisted,
      setItem: (_key: string, value: string) => {
        persisted = value;
      },
    };
    const next = updateStoredPlans(
      storage,
      'plans',
      (current) =>
        mergePlanningBackup(current, { ...emptyPlanningState(), saved: ['pondicherry'] }).state,
    );
    expect(next.saved).toEqual(['kodaikanal', 'pondicherry']);
    expect(JSON.parse(persisted)).toEqual(next);
  });
  it('surfaces quota failures without returning a successful new state', () => {
    const persisted = JSON.stringify({ ...emptyPlanningState(), saved: ['kodaikanal'] });
    const storage = {
      getItem: () => persisted,
      setItem: () => {
        throw new Error('Storage quota exceeded');
      },
    };
    expect(() => updateStoredPlans(storage, 'plans', () => emptyPlanningState())).toThrow('quota');
    expect(JSON.parse(persisted).saved).toEqual(['kodaikanal']);
  });
  it('never writes over corrupt stored data during a restore', () => {
    let writes = 0;
    const storage = {
      getItem: () => '{corrupt',
      setItem: () => {
        writes++;
      },
    };
    expect(() => updateStoredPlans(storage, 'plans', () => emptyPlanningState())).toThrow();
    expect(writes).toBe(0);
  });
});
