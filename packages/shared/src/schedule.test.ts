import { describe, expect, it } from 'vitest';
import { nextDeparture, schedulePlans, departureLabel } from './schedule';
import { localDate, type JourneyPlan } from './planning';

const commute: JourneyPlan = {
  id: 'commute',
  kind: 'commute',
  origin: 'Navalur',
  destination: 'DLF',
  date: '2026-09-01',
  time: '08:15',
  days: [1, 2, 3, 4, 5],
  notes: '',
  createdAt: '2026-09-01T00:00:00Z',
};
describe('local departure schedules', () => {
  it('skips nonexistent daylight-saving departure times', () => {
    const previousTimezone = process.env.TZ;
    process.env.TZ = 'America/New_York';
    try {
      const next = nextDeparture(
        { ...commute, date: '2026-03-01', time: '02:30', days: [0] },
        new Date('2026-03-08T00:00:00'),
      );
      expect(next && localDate(next)).toBe('2026-03-15');
      expect(next?.getHours()).toBe(2);
    } finally {
      if (previousTimezone === undefined) delete process.env.TZ;
      else process.env.TZ = previousTimezone;
    }
  });
  it('rolls a Friday commute to Monday after the departure minute', () => {
    const next = nextDeparture(commute, new Date('2026-09-04T08:16:00'));
    expect(next && localDate(next)).toBe('2026-09-07');
    expect(next?.getHours()).toBe(8);
    expect(next?.getMinutes()).toBe(15);
  });
  it('keeps the current minute and respects a future first departure', () => {
    expect(
      departureLabel(
        nextDeparture(commute, new Date('2026-09-04T08:15:59')),
        new Date('2026-09-04T08:15:59'),
      ),
    ).toBe('Today · 08:15');
    const next = nextDeparture(
      { ...commute, date: '2027-01-02', days: [1] },
      new Date('2026-12-31T12:00:00'),
    );
    expect(next && localDate(next)).toBe('2027-01-04');
  });
  it('does not mark passed trips complete or roll them forward', () => {
    const trip = { ...commute, kind: 'trip' as const, date: '2026-09-04', days: [] };
    expect(nextDeparture(trip, new Date('2026-09-04T08:16:00'))).toBeNull();
    expect(trip).not.toHaveProperty('status');
  });
  it('sorts upcoming trips and commutes before earlier plans without mutating input', () => {
    const trip = { ...commute, id: 'trip', kind: 'trip' as const, date: '2026-09-05', days: [] };
    const past = { ...trip, id: 'past', date: '2026-08-01' };
    const plans = Object.freeze([commute, past, trip]);
    expect(
      schedulePlans(plans, new Date('2026-09-04T09:00:00')).map((item) => item.plan.id),
    ).toEqual(['trip', 'commute', 'past']);
    expect(plans.map((plan) => plan.id)).toEqual(['commute', 'past', 'trip']);
  });
  it('labels tomorrow across a year boundary and handles empty schedules', () => {
    expect(departureLabel(new Date('2027-01-01T08:15:00'), new Date('2026-12-31T18:00:00'))).toBe(
      'Tomorrow · 08:15',
    );
    expect(schedulePlans([], new Date())).toEqual([]);
  });
});
