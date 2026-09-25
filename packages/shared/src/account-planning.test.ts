import { describe, expect, it } from 'vitest';
import {
  accountPlanningDocumentBytes,
  accountPlanningIssue,
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
  it('reads an absent copy without content, keeping its version', () => {
    expect(
      readAccountPlanning({ present: false, version: 0, updatedAt: null, plans: [], saved: [] }),
    ).toEqual({
      present: false,
      version: 0,
      updatedAt: null,
      state: { version: 1, plans: [], saved: [] },
    });
    expect(
      readAccountPlanning({ present: false, version: 5, updatedAt: null, plans: [], saved: [] })
        .version,
    ).toBe(5);
  });

  it('reads a stored copy with canonical timestamps and known plan fields only', () => {
    const copy = readAccountPlanning({
      present: true,
      version: 3,
      updatedAt: '2026-09-25T07:00:00.1234Z',
      plans: [{ ...plan('a'), extra: 'dropped' }],
      saved: ['ooty'],
    });
    expect(copy.present).toBe(true);
    expect(copy.version).toBe(3);
    expect(copy.updatedAt).toBe('2026-09-25T07:00:00.123400Z');
    expect(copy.state.plans).toEqual([plan('a')]);
    expect(Object.keys(copy.state.plans[0]!)).not.toContain('extra');
    expect(copy.state.saved).toEqual(['ooty']);
  });

  const at = '2026-09-25T07:00:00Z';
  it.each([
    ['not an object', null],
    ['array', []],
    ['missing present', { version: 1, updatedAt: at, plans: [], saved: [] }],
    ['string present', { present: 'true', version: 1, updatedAt: at, plans: [], saved: [] }],
    ['string version', { present: true, version: '1', updatedAt: at, plans: [], saved: [] }],
    ['fractional version', { present: true, version: 1.5, updatedAt: at, plans: [], saved: [] }],
    ['negative version', { present: false, version: -1, updatedAt: null, plans: [], saved: [] }],
    ['unsafe version', { present: true, version: 2 ** 53, updatedAt: at, plans: [], saved: [] }],
    ['present at version 0', { present: true, version: 0, updatedAt: at, plans: [], saved: [] }],
    ['absent with timestamp', { present: false, version: 2, updatedAt: at, plans: [], saved: [] }],
    [
      'absent with content',
      { present: false, version: 2, updatedAt: null, plans: [plan('a')], saved: [] },
    ],
    [
      'present without timestamp',
      { present: true, version: 1, updatedAt: null, plans: [], saved: [] },
    ],
    [
      'bad timestamp',
      { present: true, version: 1, updatedAt: '2026-09-25 07:00', plans: [], saved: [] },
    ],
    ['missing plans', { present: true, version: 1, updatedAt: at, saved: [] }],
    ['invalid plan', { present: true, version: 1, updatedAt: at, plans: [{ id: 'x' }], saved: [] }],
    [
      'same origin and destination',
      {
        present: true,
        version: 1,
        updatedAt: at,
        plans: [plan('a', { destination: 'navalur' })],
        saved: [],
      },
    ],
    [
      'duplicate plan ids',
      { present: true, version: 1, updatedAt: at, plans: [plan('a'), plan('a')], saved: [] },
    ],
    [
      'duplicate saved places',
      { present: true, version: 1, updatedAt: at, plans: [], saved: ['ooty', 'ooty'] },
    ],
    ['bad saved id', { present: true, version: 1, updatedAt: at, plans: [], saved: ['Ooty'] }],
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

  it.each([
    ['control character in origin', { origin: 'Nav\u0007alur' }],
    ['DEL in destination', { destination: 'DLF\u007f' }],
    ['C1 control in id', { id: 'id\u0085' }],
    ['lone surrogate in notes', { notes: 'x\ud800' }],
    ['vertical tab in notes', { notes: 'a\u000bb' }],
    [
      'long createdAt',
      { createdAt: 'Friday, September 25, 2026 06:00:00 GMT+0000 (Coordinated Universal Time)' },
    ],
  ])('names a plan the server would reject: %s', (_, change) => {
    const bad = state([plan('a', change as Partial<JourneyPlan>)]);
    expect(accountPlanningIssue(bad)).toMatch(/^The plan “.*” has characters/);
    expect(() => createAccountPlanningWrite(bad, 0, mutation)).toThrow(/^The plan/);
  });

  it('accepts notes with tabs and newlines and ordinary Unicode', () => {
    expect(
      accountPlanningIssue(state([plan('a', { notes: 'a\tb\nc\r', origin: 'தமிழ் 🌊' })])),
    ).toBeNull();
  });

  it('measures the stored document size', () => {
    expect(accountPlanningDocumentBytes({ plans: [], saved: ['ooty'] })).toBe(
      '{"plans":[],"saved":["ooty"]}'.length,
    );
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
