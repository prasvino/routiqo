import { readPlanningState, type PlanningState } from '@routiqo/shared';

export interface PlanningStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

/** Read immediately before writing; publish UI state only after storage accepts the change. */
export function updateStoredPlans(
  storage: PlanningStorage,
  key: string,
  transform: (current: PlanningState) => PlanningState,
): PlanningState {
  const current = readPlanningState(storage.getItem(key));
  const next = transform(current);
  storage.setItem(key, JSON.stringify(next));
  return next;
}
