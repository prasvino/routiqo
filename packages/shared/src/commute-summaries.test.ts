import { describe, expect, it, vi } from 'vitest';
import { summarizeCommutes } from './commute-summaries';
import type { JourneySnapshots, ServerJourney } from './journey-snapshots';

const owner = '00000000-0000-4000-8000-000000000001';

function journey(
  sequence: number,
  startedAt: string,
  completedAt: string | null,
  kind: ServerJourney['kind'] = 'commute',
): ServerJourney {
  return {
    id: `10000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`,
    kind,
    status: completedAt === null ? 'active' : 'completed',
    startedAt,
    completedAt,
  };
}

function snapshots(journeys: ServerJourney[]): JourneySnapshots {
  return { version: 1, accountId: owner, journeys };
}

describe('confirmed commute summaries', () => {
  it('groups by the start month in the requested time zone and sorts newest first', () => {
    const input = snapshots([
      journey(1, '2026-03-01T00:30:00.000000Z', '2026-03-01T01:30:00.000000Z'),
      journey(2, '2026-03-01T08:30:00.000000Z', '2026-03-01T09:00:00.000000Z'),
      journey(3, '2026-04-01T07:00:00.000000Z', '2026-04-01T07:15:00.000000Z'),
    ]);

    expect(summarizeCommutes(input, owner, 'America/Los_Angeles')).toEqual([
      { month: '2026-04', journeys: 1, recordedMinutes: 15 },
      { month: '2026-03', journeys: 1, recordedMinutes: 30 },
      { month: '2026-02', journeys: 1, recordedMinutes: 60 },
    ]);
    expect(summarizeCommutes(input, owner, 'UTC').map((summary) => summary.month)).toEqual([
      '2026-04',
      '2026-03',
    ]);
  });

  it('uses UTC elapsed instants across daylight-saving transitions', () => {
    const input = snapshots([
      journey(1, '2026-03-08T06:30:00.000000Z', '2026-03-08T08:30:00.000000Z'),
      journey(2, '2026-11-01T04:30:00.000000Z', '2026-11-01T07:30:00.000000Z'),
    ]);
    expect(summarizeCommutes(input, owner, 'America/New_York')).toEqual([
      { month: '2026-11', journeys: 1, recordedMinutes: 180 },
      { month: '2026-03', journeys: 1, recordedMinutes: 120 },
    ]);
  });

  it('sums exact microseconds before flooring the monthly total', () => {
    const input = snapshots([
      journey(1, '2026-05-01T00:00:00.000000Z', '2026-05-01T00:00:29.500001Z'),
      journey(2, '2026-05-02T00:00:00.000000Z', '2026-05-02T00:00:30.499999Z'),
    ]);
    expect(summarizeCommutes(input, owner, 'UTC')).toEqual([
      { month: '2026-05', journeys: 2, recordedMinutes: 1 },
    ]);
  });

  it('excludes trips and active commutes without inventing a summary', () => {
    const input = snapshots([
      journey(1, '2026-06-01T00:00:00.000000Z', '2026-06-01T01:00:00.000000Z', 'trip'),
      journey(2, '2026-06-02T00:00:00.000000Z', null),
    ]);
    expect(summarizeCommutes(input, owner, 'UTC')).toEqual([]);
  });

  it('rejects the whole input on account mismatch, duplicates or corruption', () => {
    const valid = journey(1, '2026-07-01T00:00:00.000000Z', '2026-07-01T00:05:00.000000Z');
    expect(() =>
      summarizeCommutes({ ...snapshots([valid]), accountId: valid.id }, owner, 'UTC'),
    ).toThrow();
    expect(() => summarizeCommutes(snapshots([valid, valid]), owner, 'UTC')).toThrow();
    expect(() =>
      summarizeCommutes(
        snapshots([
          valid,
          { ...journey(2, '2026-07-02T00:00:00.000000Z', null), status: 'completed' },
        ]),
        owner,
        'UTC',
      ),
    ).toThrow();
  });

  it('rejects invalid time zones even when there are no journeys', () => {
    expect(() => summarizeCommutes(snapshots([]), owner, 'Mars/Olympus_Mons')).toThrow(
      'Invalid time zone.',
    );
  });

  it('rejects local calendar months outside the representable YYYY-MM range', () => {
    expect(() =>
      summarizeCommutes(
        snapshots([journey(1, '0001-01-01T00:00:00.000000Z', '0001-01-01T00:00:01.000000Z')]),
        owner,
        'America/New_York',
      ),
    ).toThrow('Unable to resolve journey month.');
    expect(() =>
      summarizeCommutes(
        snapshots([journey(2, '9999-12-31T23:59:58.000000Z', '9999-12-31T23:59:59.000000Z')]),
        owner,
        'Pacific/Kiritimati',
      ),
    ).toThrow('Unable to resolve journey month.');
  });

  it('handles browser ICU era labels and combined-format month fallback', () => {
    const nativeFormatToParts = Intl.DateTimeFormat.prototype.formatToParts;
    const formatToParts = vi
      .spyOn(Intl.DateTimeFormat.prototype, 'formatToParts')
      .mockImplementation(function (this: Intl.DateTimeFormat, date?: Date | number) {
        const combined =
          this.resolvedOptions().year !== undefined && this.resolvedOptions().month !== undefined;
        return nativeFormatToParts.call(this, date).map((part) => {
          if (part.type === 'era' && part.value === 'AD') return { ...part, value: 'CE' };
          if (combined && part.type === 'month' && part.value === '09')
            return { ...part, value: 'Sep' };
          return part;
        });
      });
    try {
      expect(
        summarizeCommutes(
          snapshots([journey(3, '2026-09-01T00:00:00.000000Z', '2026-09-01T00:05:00.000000Z')]),
          owner,
          'UTC',
        ),
      ).toEqual([{ month: '2026-09', journeys: 1, recordedMinutes: 5 }]);
      expect(() =>
        summarizeCommutes(
          snapshots([journey(4, '0001-01-01T00:00:00.000000Z', '0001-01-01T00:00:01.000000Z')]),
          owner,
          'America/New_York',
        ),
      ).toThrow('Unable to resolve journey month.');
    } finally {
      formatToParts.mockRestore();
    }
  });
});
