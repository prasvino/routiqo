import { readPlanningState, type JourneyPlan, type PlanningState } from './planning';

export const MAX_BACKUP_BYTES = 512 * 1024;
export interface PlanningBackup {
  format: 'routiqo-planning';
  version: 1;
  exportedAt: string;
  data: PlanningState;
}
export interface RestoreSummary {
  addedPlans: number;
  keptPlans: number;
  addedPlaces: number;
}

function knownPlan(plan: JourneyPlan): JourneyPlan {
  return {
    id: plan.id,
    kind: plan.kind,
    origin: plan.origin,
    destination: plan.destination,
    date: plan.date,
    time: plan.time,
    days: [...plan.days],
    notes: plan.notes,
    createdAt: plan.createdAt,
  };
}
function cleanState(state: PlanningState): PlanningState {
  const valid = readPlanningState(JSON.stringify(state));
  return { version: 1, plans: valid.plans.map(knownPlan), saved: [...valid.saved] };
}

export function createPlanningBackup(state: PlanningState, now = new Date()): string {
  const backup: PlanningBackup = {
    format: 'routiqo-planning',
    version: 1,
    exportedAt: now.toISOString(),
    data: cleanState(state),
  };
  return JSON.stringify(backup, null, 2);
}

export function parsePlanningBackup(raw: string): PlanningBackup {
  if (raw.length > MAX_BACKUP_BYTES) throw new Error('Choose a backup smaller than 512 KB.');
  let bytes = 0;
  for (const character of raw) {
    const point = character.codePointAt(0)!;
    bytes += point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
    if (bytes > MAX_BACKUP_BYTES) throw new Error('Choose a backup smaller than 512 KB.');
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error();
    const envelope = value as Record<string, unknown>;
    if (
      envelope.format !== 'routiqo-planning' ||
      envelope.version !== 1 ||
      typeof envelope.exportedAt !== 'string' ||
      !Number.isFinite(Date.parse(envelope.exportedAt))
    )
      throw new Error();
    const data = readPlanningState(JSON.stringify(envelope.data ?? {}));
    return {
      format: 'routiqo-planning',
      version: 1,
      exportedAt: envelope.exportedAt,
      data: cleanState(data),
    };
  } catch {
    throw new Error(
      'This is not a supported Routiqo backup. Your existing plans have not changed.',
    );
  }
}

export function mergePlanningBackup(
  current: PlanningState,
  backup: PlanningState,
): { state: PlanningState; summary: RestoreSummary } {
  const existing = cleanState(current);
  const imported = cleanState(backup);
  const ids = new Set(existing.plans.map((plan) => plan.id));
  const added = imported.plans.filter((plan) => !ids.has(plan.id));
  const saved = [...new Set([...existing.saved, ...imported.saved])];
  if (existing.plans.length + added.length > 100 || saved.length > 100)
    throw new Error(
      'This backup would exceed 100 plans or 100 saved places. No data was restored.',
    );
  return {
    state: { version: 1, plans: [...existing.plans, ...added], saved },
    summary: {
      addedPlans: added.length,
      keptPlans: imported.plans.length - added.length,
      addedPlaces: saved.length - existing.saved.length,
    },
  };
}
