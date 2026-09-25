import { describe, expect, it } from 'vitest';
import {
  createAccountPlanningWrite,
  readAccountPlanning,
  samePlanningContent,
  utf8ByteLength,
} from './account-planning';
import type { JourneyPlan, PlanningState } from './planning';

const plan = (id: string, extra: Partial<JourneyPlan> = {}): JourneyPlan => ({
  id,
  kind: 'commute',
  origin: 'Navalur',
  destination: 'DLF Chennai',
  date: '2026-09-28',
  time: '08:15',
  days: [1, 2, 3, 4, 5],
  notes: '',
  createdAt: '2026-09-25T06:00:00.000Z',
  ...extra,
});
const state = (plans: JourneyPlan[], saved: string[] = []): PlanningState => ({
  version: 1,
  plans,
  saved,
});
const mutation = '0f8fad5b-d9cb-469f-a165-70867728950e';

describe('account planning copy', () => {
  it('reads an absent copy as version 0 without content', () => {
    expect(readAccountPlanning({ version: 0, updatedAt: null, plans: [], saved: [] })).toEqual({
      version: 0,
      updatedAt: null,
      state: { version: 1, plans: [], saved: [] },
    });
  });

  it('reads a stored copy with canonical timestamps and known plan fields only', () => {
    const copy = readAccountPlanning({
      version: 3,
      updatedAt: '2026-09-25T07:00:00.1234Z',
      plans: [{ ...plan('a'), extra: 'dropped' }],
      saved: ['ooty'],
    });
    expect(copy.version).toBe(3);
    expect(copy.updatedAt).toBe('2026-09-25T07:00:00.123400Z');
    expect(copy.state.plans).toEqual([plan('a')]);
    expect(Object.keys(copy.state.plans[0]!)).not.toContain('extra');
    expect(copy.state.saved).toEqual(['ooty']);
  });

  it.each([
    ['not an object', null],
    ['array', []],
    ['string version', { version: '1', updatedAt: null, plans: [], saved: [] }],
    [
      'fractional version',
      { version: 1.5, updatedAt: '2026-09-25T07:00:00Z', plans: [], saved: [] },
    ],
    ['negative version', { version: -1, updatedAt: null, plans: [], saved: [] }],
    [
      'unsafe version',
      { version: 2 ** 53, updatedAt: '2026-09-25T07:00:00Z', plans: [], saved: [] },
    ],
    [
      'version 0 with timestamp',
      { version: 0, updatedAt: '2026-09-25T07:00:00Z', plans: [], saved: [] },
    ],
    ['version 0 with content', { version: 0, updatedAt: null, plans: [plan('a')], saved: [] }],
    ['stored copy without timestamp', { version: 1, updatedAt: null, plans: [], saved: [] }],
    ['bad timestamp', { version: 1, updatedAt: '2026-09-25 07:00', plans: [], saved: [] }],
    ['missing plans', { version: 1, updatedAt: '2026-09-25T07:00:00Z', saved: [] }],
    [
      'invalid plan',
      { version: 1, updatedAt: '2026-09-25T07:00:00Z', plans: [{ id: 'x' }], saved: [] },
    ],
    [
      'same origin and destination',
      {
        version: 1,
        updatedAt: '2026-09-25T07:00:00Z',
        plans: [plan('a', { destination: 'navalur' })],
        saved: [],
      },
    ],
    [
      'duplicate plan ids',
      { version: 1, updatedAt: '2026-09-25T07:00:00Z', plans: [plan('a'), plan('a')], saved: [] },
    ],
    [
      'duplicate saved places',
      { version: 1, updatedAt: '2026-09-25T07:00:00Z', plans: [], saved: ['ooty', 'ooty'] },
    ],
    ['bad saved id', { version: 1, updatedAt: '2026-09-25T07:00:00Z', plans: [], saved: ['Ooty'] }],
  ])('rejects the whole copy for %s', (_, value) => {
    expect(() => readAccountPlanning(value)).toThrow();
  });

  it('creates canonical writes with exact version and mutation identity', () => {
    const write = createAccountPlanningWrite(
      state([{ ...plan('a'), ...{ stray: true } } as JourneyPlan], ['ooty']),
      4,
      mutation,
    );
    expect(write).toEqual({
      plans: [plan('a')],
      saved: ['ooty'],
      expectedVersion: 4,
      mutationId: mutation,
    });
    expect(JSON.stringify(write)).not.toContain('stray');
  });

  it.each([
    [-1, mutation],
    [1.5, mutation],
    [Number.MAX_SAFE_INTEGER, mutation],
    [0, mutation.toUpperCase()],
    [0, 'not-a-uuid'],
  ])('rejects invalid write identity %s %s', (version, id) => {
    expect(() => createAccountPlanningWrite(state([]), version, id)).toThrow();
  });

  it('rejects invalid local state instead of uploading it', () => {
    expect(() =>
      createAccountPlanningWrite(state([plan('a', { days: [] })]), 0, mutation),
    ).toThrow();
  });

  it('counts UTF-8 bytes like the server', () => {
    expect(utf8ByteLength('abc')).toBe(3);
    expect(utf8ByteLength('é')).toBe(2);
    expect(utf8ByteLength('த')).toBe(3);
    expect(utf8ByteLength('🌊')).toBe(4);
    expect(utf8ByteLength('\ud800')).toBe(3);
  });

  it('compares planning content independent of unknown fields', () => {
    expect(samePlanningContent(state([plan('a')], ['ooty']), state([plan('a')], ['ooty']))).toBe(
      true,
    );
    expect(samePlanningContent(state([plan('a')]), state([plan('a', { notes: 'x' })]))).toBe(false);
    expect(samePlanningContent(state([], ['ooty']), state([]))).toBe(false);
  });
});
