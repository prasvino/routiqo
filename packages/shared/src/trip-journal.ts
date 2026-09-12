import type { components } from '@routiqo/api-client';
import { readJourneyInstant, readServerJourney } from './journey-snapshots';
export type TripJournal = components['schemas']['TripJournal'];
export type TripJournalWrite = components['schemas']['TripJournalWrite'];
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
function annotationText(value: unknown, notes: boolean): string {
  if (typeof value !== 'string' || value.length > (notes ? 4000 : 120))
    throw new Error('Journal text exceeds its limit.');
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code <= 31 || (code >= 127 && code <= 159)) {
      if (!notes || ![9, 10, 13].includes(code))
        throw new Error('Journal text contains an unsupported character.');
    }
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++index);
      if (!(next >= 0xdc00 && next <= 0xdfff))
        throw new Error('Journal text contains an invalid character.');
    } else if (code >= 0xdc00 && code <= 0xdfff)
      throw new Error('Journal text contains an invalid character.');
  }
  return value;
}
export function readTripJournalWrite(value: unknown): TripJournalWrite {
  if (
    !record(value) ||
    typeof value.expectedVersion !== 'number' ||
    !Number.isSafeInteger(value.expectedVersion) ||
    value.expectedVersion < 0 ||
    value.expectedVersion >= Number.MAX_SAFE_INTEGER ||
    typeof value.mutationId !== 'string' ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value.mutationId)
  )
    throw new Error('Invalid journal edit.');
  return {
    title: annotationText(value.title, false),
    notes: annotationText(value.notes, true),
    expectedVersion: value.expectedVersion,
    mutationId: value.mutationId,
  };
}
export function readTripJournal(value: unknown): TripJournal {
  if (!record(value) || !record(value.annotation)) throw new Error('Invalid trip journal.');
  const journey = readServerJourney(value.journey);
  if (journey.kind !== 'trip' || journey.status !== 'completed')
    throw new Error('This journey is not a completed trip.');
  const input = value.annotation;
  if (
    typeof input.version !== 'number' ||
    !Number.isSafeInteger(input.version) ||
    input.version < 0
  )
    throw new Error('Invalid journal version.');
  const title = annotationText(input.title, false),
    notes = annotationText(input.notes, true);
  const updatedAt = input.updatedAt === null ? null : readJourneyInstant(input.updatedAt);
  if (input.version === 0 ? title !== '' || notes !== '' || updatedAt !== null : updatedAt === null)
    throw new Error('Invalid journal annotation.');
  return { journey, annotation: { title, notes, version: input.version, updatedAt } };
}
