import { canonicalPlanningState } from './backup';
import { readJourneyInstant } from './journey-snapshots';
import type { JourneyPlan, PlanningState } from './planning';

/** Server bound on the stored canonical document (ADR 0062). */
export const ACCOUNT_PLANNING_MAX_BYTES = 256 * 1024;
/** Request body bound shared by the Next proxy and the core API for the planning write path. */
export const ACCOUNT_PLANNING_MAX_REQUEST_BYTES = 264 * 1024;
const MAX_VERSION = Number.MAX_SAFE_INTEGER;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

export interface AccountPlanningCopy {
  /** 0 means the account has no copy. */
  version: number;
  updatedAt: string | null;
  state: PlanningState;
}
export interface AccountPlanningWrite {
  plans: JourneyPlan[];
  saved: string[];
  expectedVersion: number;
  mutationId: string;
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** Validates a server copy with the same rules as local storage. Any invalid entry rejects the whole copy. */
export function readAccountPlanning(value: unknown): AccountPlanningCopy {
  if (
    !record(value) ||
    typeof value.version !== 'number' ||
    !Number.isSafeInteger(value.version) ||
    value.version < 0 ||
    value.version > MAX_VERSION ||
    !Array.isArray(value.plans) ||
    !Array.isArray(value.saved)
  )
    throw new Error('Invalid account planning copy.');
  if (new Set(value.saved).size !== value.saved.length)
    throw new Error('Invalid account planning copy.');
  const updatedAt = value.updatedAt === null ? null : readJourneyInstant(value.updatedAt);
  let state: PlanningState;
  try {
    state = canonicalPlanningState({
      version: 1,
      plans: value.plans as JourneyPlan[],
      saved: value.saved as string[],
    });
  } catch {
    throw new Error('Invalid account planning copy.');
  }
  const empty = state.plans.length === 0 && state.saved.length === 0;
  if (value.version === 0 ? !empty || updatedAt !== null : updatedAt === null)
    throw new Error('Invalid account planning copy.');
  return { version: value.version, updatedAt, state };
}

export function createAccountPlanningWrite(
  state: PlanningState,
  expectedVersion: number,
  mutationId: string,
): AccountPlanningWrite {
  if (
    !Number.isSafeInteger(expectedVersion) ||
    expectedVersion < 0 ||
    expectedVersion >= MAX_VERSION ||
    !uuid.test(mutationId)
  )
    throw new Error('Invalid account planning write.');
  const canonical = canonicalPlanningState(state);
  return { plans: canonical.plans, saved: canonical.saved, expectedVersion, mutationId };
}

/** UTF-8 byte length of a string, counting unpaired surrogates as the three-byte replacement encoding. */
export function utf8ByteLength(text: string): number {
  let bytes = 0;
  for (const character of text) {
    const point = character.codePointAt(0)!;
    bytes += point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
  }
  return bytes;
}

/** True when the account copy holds exactly this device's planning content. */
export function samePlanningContent(left: PlanningState, right: PlanningState): boolean {
  return (
    JSON.stringify(canonicalPlanningState(left)) === JSON.stringify(canonicalPlanningState(right))
  );
}
