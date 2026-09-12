import { readJourneySnapshots, type JourneySnapshots } from './journey-snapshots';

export interface CommuteSummary {
  month: string;
  journeys: number;
  recordedMinutes: number;
}

const instantParts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\.(\d{6})Z$/;

function instantMicroseconds(value: string): bigint {
  const match = instantParts.exec(value);
  if (!match) throw new Error('Invalid journey timestamp.');
  const seconds = match[1];
  const microseconds = match[2];
  if (!seconds || !microseconds) throw new Error('Invalid journey timestamp.');
  const milliseconds = Date.parse(`${seconds}Z`);
  if (!Number.isSafeInteger(milliseconds)) throw new Error('Invalid journey timestamp.');
  return BigInt(milliseconds) * 1_000n + BigInt(microseconds);
}

interface CalendarFormatters {
  month: Intl.DateTimeFormat;
  yearEra: Intl.DateTimeFormat;
  positiveEra: string;
}

function era(formatter: Intl.DateTimeFormat, date: Date): string {
  const value = formatter.formatToParts(date).find((part) => part.type === 'era')?.value;
  if (!value) throw new Error('Unable to resolve journey month.');
  return value;
}

function calendarFormatters(timeZone: string): CalendarFormatters {
  if (typeof timeZone !== 'string' || timeZone.length === 0) throw new Error('Invalid time zone.');
  try {
    const month = new Intl.DateTimeFormat('en-CA-u-ca-iso8601-nu-latn', {
      timeZone,
      month: '2-digit',
    });
    const yearEra = new Intl.DateTimeFormat('en-CA-u-ca-iso8601-nu-latn', {
      timeZone,
      year: 'numeric',
      era: 'short',
    });
    return {
      month,
      yearEra,
      positiveEra: era(yearEra, new Date('2000-07-01T00:00:00.000Z')),
    };
  } catch {
    throw new Error('Invalid time zone.');
  }
}

function calendarMonth(formatters: CalendarFormatters, startedAt: string): string {
  const date = new Date(startedAt);
  const yearParts = formatters.yearEra.formatToParts(date);
  const monthParts = formatters.month.formatToParts(date);
  const year = yearParts.find((part) => part.type === 'year')?.value;
  const month = monthParts.find((part) => part.type === 'month')?.value;
  const localEra = yearParts.find((part) => part.type === 'era')?.value;
  if (
    !year ||
    !month ||
    localEra !== formatters.positiveEra ||
    !/^\d{1,4}$/.test(year) ||
    !/^(?:0[1-9]|1[0-2])$/.test(month)
  )
    throw new Error('Unable to resolve journey month.');
  return `${year.padStart(4, '0')}-${month.padStart(2, '0')}`;
}

export function summarizeCommutes(
  input: JourneySnapshots,
  accountId: string,
  timeZone: string,
): CommuteSummary[] {
  const raw = JSON.stringify(input);
  if (raw === undefined) throw new Error('Invalid journey snapshots.');
  const snapshots = readJourneySnapshots(raw, accountId);
  const formatters = calendarFormatters(timeZone);
  const grouped = new Map<string, { journeys: number; elapsedMicroseconds: bigint }>();

  for (const journey of snapshots.journeys) {
    if (journey.kind !== 'commute' || journey.status !== 'completed') continue;
    if (journey.completedAt === null) throw new Error('Invalid journey lifecycle.');
    const month = calendarMonth(formatters, journey.startedAt);
    const elapsed =
      instantMicroseconds(journey.completedAt) - instantMicroseconds(journey.startedAt);
    const current = grouped.get(month) ?? { journeys: 0, elapsedMicroseconds: 0n };
    current.journeys++;
    current.elapsedMicroseconds += elapsed;
    grouped.set(month, current);
  }

  return [...grouped.entries()]
    .sort(([left], [right]) => right.localeCompare(left))
    .map(([month, summary]) => {
      const recordedMinutes = summary.elapsedMicroseconds / 60_000_000n;
      if (recordedMinutes > BigInt(Number.MAX_SAFE_INTEGER))
        throw new Error('Commute summary exceeds its safe range.');
      return { month, journeys: summary.journeys, recordedMinutes: Number(recordedMinutes) };
    });
}
