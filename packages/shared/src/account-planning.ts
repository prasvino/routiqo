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
  /** False when the account holds no copy; the version still counts removals and never repeats. */
  present: boolean;
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
    typeof value.present !== 'boolean' ||
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
  if (value.present ? value.version < 1 || updatedAt === null : !empty || updatedAt !== null)
    throw new Error('Invalid account planning copy.');
  return { present: value.present, version: value.version, updatedAt, state };
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
  const issue = accountPlanningIssue(canonical);
  if (issue) throw new Error(issue);
  return { plans: canonical.plans, saved: canonical.saved, expectedVersion, mutationId };
}

/** Matches Java's Character.isISOControl: C0, DEL and C1. */
const isoControl = (code: number) => code <= 0x1f || (code >= 0x7f && code <= 0x9f);

/** True when text has a control character (tab/newline/CR allowed if multiline) or a lone surrogate. */
function rejectedText(text: string, multiline: boolean): boolean {
  for (let index = 0; index < text.length; index++) {
    const code = text.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = text.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true;
      index++;
      continue;
    }
    if (code >= 0xdc00 && code <= 0xdfff) return true;
    if (isoControl(code) && !(multiline && (code === 0x09 || code === 0x0a || code === 0x0d)))
      return true;
  }
  return false;
}

/**
 * Server-side text rules that local storage does not enforce (for example, text restored from a backup
 * file). Returns a traveller-facing message naming the plan to fix, or null when the state can be saved.
 */
export function accountPlanningIssue(state: PlanningState): string | null {
  for (const plan of state.plans) {
    const singleLine = [plan.id, plan.kind, plan.origin, plan.destination, plan.createdAt];
    const invalid =
      singleLine.some((text) => rejectedText(text, false)) ||
      rejectedText(plan.notes, true) ||
      plan.createdAt.length > 64;
    if (invalid) {
      const name = Array.from(`${plan.origin} → ${plan.destination}`, (character) =>
        isoControl(character.codePointAt(0)!) ? ' ' : character,
      ).join('');
      return `The plan “${name.slice(0, 60)}” has characters that can’t be kept on your account. Edit it, then try again.`;
    }
  }
  return null;
}

/** UTF-8 size of the stored canonical document, which the server limits to ACCOUNT_PLANNING_MAX_BYTES. */
export function accountPlanningDocumentBytes(write: Pick<AccountPlanningWrite, 'plans' | 'saved'>) {
  return utf8ByteLength(JSON.stringify({ plans: write.plans, saved: write.saved }));
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
