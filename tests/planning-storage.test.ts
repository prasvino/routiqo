import { describe, expect, it, vi } from 'vitest';
import { updateStoredPlans } from '../apps/web/lib/planning-storage';
import {
  emptyPlanningState,
  mergePlanningBackup,
  type PlanningState,
} from '../packages/shared/src';

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
  it('propagates read access denials without transforming or writing and recovers from fresh state', () => {
    let persisted = JSON.stringify({ ...emptyPlanningState(), saved: ['kodaikanal'] });
    let allowRead = false;
    let setItemCalls = 0;
    const storage = {
      getItem: () => {
        if (!allowRead) {
          throw new DOMException('The operation is insecure.', 'SecurityError');
        }
        return persisted;
      },
      setItem: (_key: string, value: string) => {
        setItemCalls++;
        persisted = value;
      },
    };
    const transform = vi.fn((current) => ({
      ...current,
      saved: [...current.saved, 'pondicherry'],
    }));

    expect(() => updateStoredPlans(storage, 'plans', transform)).toThrowError(
      expect.objectContaining({ name: 'SecurityError' }),
    );
    expect(transform).not.toHaveBeenCalled();
    expect(setItemCalls).toBe(0);
    expect(JSON.parse(persisted).saved).toEqual(['kodaikanal']);

    allowRead = true;
    persisted = JSON.stringify({ ...emptyPlanningState(), saved: ['kodaikanal', 'ooty'] });
    const recovered = updateStoredPlans(storage, 'plans', transform);

    expect(transform).toHaveBeenCalledTimes(1);
    expect(recovered.saved).toEqual(['kodaikanal', 'ooty', 'pondicherry']);
    expect(JSON.parse(persisted)).toEqual(recovered);
    expect(setItemCalls).toBe(1);
  });
  it('preserves persisted plans when transformation throws and recovers from fresh state', () => {
    let persisted = JSON.stringify({ ...emptyPlanningState(), saved: ['kodaikanal'] });
    let shouldThrow = true;
    let setItemCalls = 0;
    const storage = {
      getItem: () => persisted,
      setItem: (_key: string, value: string) => {
        setItemCalls++;
        persisted = value;
      },
    };
    const transform = (current: PlanningState) => {
      if (shouldThrow) {
        throw new Error('Transformation failed');
      }
      return { ...current, saved: [...current.saved, 'pondicherry'] };
    };

    expect(() => updateStoredPlans(storage, 'plans', transform)).toThrow('Transformation failed');
    expect(setItemCalls).toBe(0);
    expect(JSON.parse(persisted).saved).toEqual(['kodaikanal']);

    shouldThrow = false;
    persisted = JSON.stringify({ ...emptyPlanningState(), saved: ['kodaikanal', 'munnar'] });
    const recovered = updateStoredPlans(storage, 'plans', transform);

    expect(recovered.saved).toEqual(['kodaikanal', 'munnar', 'pondicherry']);
    expect(JSON.parse(persisted)).toEqual(recovered);
    expect(setItemCalls).toBe(1);
  });
});
