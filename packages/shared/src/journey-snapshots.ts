import type { components } from '@routiqo/api-client';
import type { JourneyCommand } from './journey-outbox';
export type ServerJourney = components['schemas']['Journey'];
export interface JourneySnapshots {
  version: 1;
  accountId: string;
  journeys: ServerJourney[];
}
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
function instant(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid journey timestamp.');
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?Z$/.exec(value);
  if (!match || value.startsWith('0000')) throw new Error('Invalid journey timestamp.');
  const date = new Date(`${match[1]}Z`);
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 19) !== match[1])
    throw new Error('Invalid journey timestamp.');
  return `${match[1]}.${(match[2] ?? '').padEnd(6, '0')}Z`;
}
export function readServerJourney(value: unknown): ServerJourney {
  if (
    !record(value) ||
    typeof value.id !== 'string' ||
    !uuid.test(value.id) ||
    (value.kind !== 'trip' && value.kind !== 'commute') ||
    (value.status !== 'active' && value.status !== 'completed')
  )
    throw new Error('Invalid server journey.');
  const startedAt = instant(value.startedAt);
  const completedAt = value.completedAt === null ? null : instant(value.completedAt);
  if (
    (value.status === 'active' && completedAt !== null) ||
    (value.status === 'completed' && (completedAt === null || completedAt < startedAt))
  )
    throw new Error('Invalid journey lifecycle.');
  return { id: value.id, kind: value.kind, status: value.status, startedAt, completedAt };
}
export function readJourneySnapshots(raw: string | null, accountId: string): JourneySnapshots {
  if (!uuid.test(accountId)) throw new Error('Invalid snapshot account.');
  if (raw === null) return { version: 1, accountId, journeys: [] };
  if (raw.length > 64 * 1024 || new TextEncoder().encode(raw).length > 64 * 1024)
    throw new Error('Journey snapshots exceed their storage limit.');
  const value: unknown = JSON.parse(raw);
  if (
    !record(value) ||
    value.version !== 1 ||
    value.accountId !== accountId ||
    !Array.isArray(value.journeys) ||
    value.journeys.length > 100
  )
    throw new Error('Invalid journey snapshots.');
  const journeys = value.journeys.map(readServerJourney);
  if (new Set(journeys.map((journey) => journey.id)).size !== journeys.length)
    throw new Error('Duplicate journey snapshots.');
  return { version: 1, accountId, journeys };
}
export function recordJourneyResult(
  state: JourneySnapshots,
  command: JourneyCommand,
  input: unknown,
): JourneySnapshots {
  const journey = readServerJourney(input);
  if (
    journey.id !== command.journeyId ||
    (command.action === 'start' && journey.kind !== command.kind) ||
    (command.action === 'complete' && journey.status !== 'completed')
  )
    throw new Error('Server result does not match the pending action.');
  const current = state.journeys.find((item) => item.id === journey.id);
  if (
    current &&
    (current.kind !== journey.kind ||
      current.startedAt !== journey.startedAt ||
      (current.status === 'completed' &&
        (journey.status !== 'completed' || current.completedAt !== journey.completedAt)))
  )
    throw new Error('Server result conflicts with the saved lifecycle.');
  const journeys = [...state.journeys.filter((item) => item.id !== journey.id), journey].sort(
    (a, b) => b.startedAt.localeCompare(a.startedAt) || b.id.localeCompare(a.id),
  );
  if (journeys.length > 100) {
    let prune = journeys.length - 1;
    while (
      prune >= 0 &&
      (journeys[prune]!.status !== 'completed' || journeys[prune]!.id === journey.id)
    )
      prune--;
    if (prune < 0) throw new Error('Active journey snapshots cannot be discarded.');
    journeys.splice(prune, 1);
  }
  return { version: 1, accountId: state.accountId, journeys };
}
