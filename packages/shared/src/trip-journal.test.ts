import { expect, it } from 'vitest';
import { readTripJournal, readTripJournalWrite } from './trip-journal';
const id = '00000000-0000-4000-8000-000000000001';
const journey = {
  id,
  kind: 'trip',
  status: 'completed',
  startedAt: '2026-09-12T01:00:00Z',
  completedAt: '2026-09-12T02:00:00Z',
};
it('preserves authored Unicode text and validates lengths, controls, versions and mutation identities', () => {
  const edit = {
    title: '  A trip 🌊  ',
    notes: 'First line\n\tசென்னை\r\nSecond line',
    expectedVersion: 0,
    mutationId: id,
  };
  expect(readTripJournalWrite(edit)).toEqual(edit);
  for (const bad of [
    { title: 'x'.repeat(121) },
    { notes: 'x'.repeat(4001) },
    { title: 'bad\nline' },
    { notes: 'bad\u0000line' },
    { notes: '\ud800' },
    { notes: '\udc00' },
    { expectedVersion: 0.1 },
    { expectedVersion: Number.MAX_SAFE_INTEGER },
    { mutationId: 'wrong' },
  ])
    expect(() => readTripJournalWrite({ ...edit, ...bad })).toThrow();
});
it('accepts only completed trips and consistent derived or persisted annotations', () => {
  const value = {
    journey,
    annotation: { title: '', notes: '', version: 0, updatedAt: null },
    secret: 'discard',
  };
  expect(readTripJournal(value)).not.toHaveProperty('secret');
  for (const invalid of [
    { ...value, journey: { ...journey, kind: 'commute' } },
    { ...value, journey: { ...journey, status: 'active', completedAt: null } },
    { ...value, annotation: { ...value.annotation, notes: 'Unversioned text' } },
    { ...value, annotation: { ...value.annotation, version: 1 } },
  ])
    expect(() => readTripJournal(invalid)).toThrow();
  expect(
    readTripJournal({
      ...value,
      annotation: {
        title: 'Trip',
        notes: 'Note',
        version: 1,
        updatedAt: '2026-09-12T02:00:00.123456Z',
      },
    }).annotation.version,
  ).toBe(1);
});
