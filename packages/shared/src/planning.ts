export type JourneyKind = 'trip' | 'commute';
export interface JourneyPlan {
  id: string;
  kind: JourneyKind;
  origin: string;
  destination: string;
  date: string;
  time: string;
  days: number[];
  notes: string;
  createdAt: string;
}
export interface PlanningState {
  version: 1;
  plans: JourneyPlan[];
  saved: string[];
}
export const emptyPlanningState = (): PlanningState => ({ version: 1, plans: [], saved: [] });
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
export function validatePlan(value: unknown): value is JourneyPlan {
  if (!isRecord(value)) return false;
  const strings = ['id', 'origin', 'destination', 'date', 'time', 'notes', 'createdAt'] as const;
  if (!strings.every((key) => typeof value[key] === 'string')) return false;
  if (value.kind !== 'trip' && value.kind !== 'commute') return false;
  if (!(value.id as string).length || (value.id as string).length > 100) return false;
  if (
    ![value.origin, value.destination].every(
      (text) => (text as string).trim().length > 0 && (text as string).length <= 100,
    )
  )
    return false;
  if (
    (value.origin as string).trim().toLowerCase() ===
    (value.destination as string).trim().toLowerCase()
  )
    return false;
  if (
    (value.notes as string).length > 500 ||
    !Number.isFinite(Date.parse(value.createdAt as string))
  )
    return false;
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(value.date as string) ||
    !/^([01]\d|2[0-3]):[0-5]\d$/.test(value.time as string)
  )
    return false;
  const date = new Date((value.date as string) + 'T00:00:00Z');
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value.date)
    return false;
  if (
    !Array.isArray(value.days) ||
    !value.days.every((day) => Number.isInteger(day) && day >= 0 && day <= 6)
  )
    return false;
  if (new Set(value.days).size !== value.days.length) return false;
  return value.kind !== 'commute' || value.days.length > 0;
}
export function readPlanningState(raw: string | null): PlanningState {
  if (raw === null) return emptyPlanningState();
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new Error('Saved plans could not be read. Clear local data to start again.');
  }
  if (
    !isRecord(value) ||
    value.version !== 1 ||
    !Array.isArray(value.plans) ||
    value.plans.length > 100 ||
    !value.plans.every(validatePlan) ||
    !Array.isArray(value.saved) ||
    value.saved.length > 100 ||
    !value.saved.every((id) => typeof id === 'string' && /^[a-z0-9-]{1,80}$/.test(id)) ||
    new Set(value.plans.map((plan) => plan.id)).size !== value.plans.length
  ) {
    throw new Error('Saved data uses an unsupported format. Clear local data to start again.');
  }
  return { version: 1, plans: value.plans, saved: [...new Set(value.saved)] };
}
export function upsertPlan(state: PlanningState, plan: JourneyPlan): PlanningState {
  if (!validatePlan(plan)) throw new Error('Please check the journey details.');
  if (!state.plans.some((item) => item.id === plan.id) && state.plans.length >= 100)
    throw new Error('You have reached 100 local plans. Remove an older plan first.');
  return { ...state, plans: [plan, ...state.plans.filter((item) => item.id !== plan.id)] };
}
export function toggleSaved(state: PlanningState, id: string): PlanningState {
  if (!/^[a-z0-9-]{1,80}$/.test(id)) throw new Error('Invalid destination.');
  if (!state.saved.includes(id) && state.saved.length >= 100)
    throw new Error('Saved destinations are full.');
  return {
    ...state,
    saved: state.saved.includes(id)
      ? state.saved.filter((item) => item !== id)
      : [...state.saved, id],
  };
}
export function localDate(date = new Date()): string {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
}
export const dayLabels = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;
