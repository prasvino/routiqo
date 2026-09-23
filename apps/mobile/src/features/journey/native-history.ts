import { readJourneyInstant, readServerJourney, type ServerJourney } from '@routiqo/shared';
import type { createNativeAccount } from '../../auth/native-account';

type Account = ReturnType<typeof createNativeAccount>;
export interface NativeHistoryCursor {
  startedAt: string;
  id: string;
}
export interface NativeHistoryPage {
  journeys: ServerJourney[];
  next: NativeHistoryCursor | null;
}

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const exact = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every((key) => key in value);

function cursor(value: unknown, canonical: boolean): NativeHistoryCursor {
  if (
    !record(value) ||
    !exact(value, ['startedAt', 'id']) ||
    typeof value.id !== 'string' ||
    !uuid.test(value.id)
  )
    throw new Error('Invalid account history cursor.');
  const startedAt = readJourneyInstant(value.startedAt);
  if (canonical && startedAt !== value.startedAt)
    throw new Error('Invalid account history cursor.');
  return { startedAt, id: value.id as string };
}

function key(left: NativeHistoryCursor, right: NativeHistoryCursor) {
  return left.startedAt.localeCompare(right.startedAt) || left.id.localeCompare(right.id);
}

export function readNativeHistoryPage(
  value: unknown,
  requested: NativeHistoryCursor | null,
): NativeHistoryPage {
  const validatedRequest = requested === null ? null : cursor(requested, true);
  if (
    !record(value) ||
    !exact(value, ['journeys', 'next']) ||
    !Array.isArray(value.journeys) ||
    value.journeys.length > 20
  )
    throw new Error('Invalid account history page.');
  const journeys = value.journeys.map((entry: unknown) => {
    if (!record(entry) || !exact(entry, ['id', 'kind', 'status', 'startedAt', 'completedAt']))
      throw new Error('Invalid account history journey.');
    return readServerJourney(entry);
  });
  const seen = new Set<string>();
  let previous = validatedRequest;
  for (const journey of journeys) {
    if (seen.has(journey.id)) throw new Error('Duplicate account history journey.');
    seen.add(journey.id);
    const current = { startedAt: journey.startedAt, id: journey.id };
    if (previous && key(current, previous) >= 0)
      throw new Error('Account history is not descending.');
    previous = current;
  }
  if (value.next === null) return { journeys, next: null };
  if (journeys.length !== 20) throw new Error('Invalid account history cursor.');
  const next = cursor(value.next, false);
  const last = journeys[19]!;
  if (next.startedAt !== last.startedAt || next.id !== last.id)
    throw new Error('Invalid account history cursor.');
  return { journeys, next };
}

export async function readNativeJourneyPage(
  identity: Account,
  accountId: string,
  before: NativeHistoryCursor | null,
): Promise<NativeHistoryPage> {
  if (!uuid.test(accountId)) throw new Error('Invalid account identity.');
  const validated = before === null ? null : cursor(before, true);
  const raw = await identity.verifiedRequest('/api/v1/native/journeys/history', 'POST', {
    accountId,
    body: validated === null ? {} : { before: validated },
  });
  return readNativeHistoryPage(raw, validated);
}
