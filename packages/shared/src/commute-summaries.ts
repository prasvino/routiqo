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

/**
 * Resolves only the local month number. The year is derived arithmetically: time-zone offsets are under
 * a day, so the local year is the UTC year except across a year boundary. This avoids the ICU `era`
 * and year parts, which differ between runtimes (ICU 78 omits `era` for some calendars).
 */
function monthFormatter(timeZone: string): Intl.DateTimeFormat {
  if (typeof timeZone !== 'string' || timeZone.length === 0) throw new Error('Invalid time zone.');
  try {
    return new Intl.DateTimeFormat('en-CA-u-ca-gregory-nu-latn', {
      timeZone,
      month: 'numeric',
      day: 'numeric',
    });
  } catch {
    throw new Error('Invalid time zone.');
  }
}

function calendarMonth(formatter: Intl.DateTimeFormat, startedAt: string): string {
  const date = new Date(startedAt);
  const utcYear = date.getUTCFullYear();
  const utcMonth = date.getUTCMonth() + 1;
  const monthText = formatter.formatToParts(date).find((part) => part.type === 'month')?.value;
  if (!monthText || !/^\d{1,2}$/.test(monthText))
    throw new Error('Unable to resolve journey month.');
  const month = Number(monthText);
  if (month < 1 || month > 12 || !Number.isSafeInteger(utcYear))
    throw new Error('Unable to resolve journey month.');
  const year =
    utcMonth === 1 && month === 12
      ? utcYear - 1
      : utcMonth === 12 && month === 1
        ? utcYear + 1
        : utcYear;
  if (year < 1 || year > 9999) throw new Error('Unable to resolve journey month.');
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`;
}

export function summarizeCommutes(
  input: JourneySnapshots,
  accountId: string,
  timeZone: string,
): CommuteSummary[] {
  const raw = JSON.stringify(input);
  if (raw === undefined) throw new Error('Invalid journey snapshots.');
  const snapshots = readJourneySnapshots(raw, accountId);
  const formatter = monthFormatter(timeZone);
  const grouped = new Map<string, { journeys: number; elapsedMicroseconds: bigint }>();

  for (const journey of snapshots.journeys) {
    if (journey.kind !== 'commute' || journey.status !== 'completed') continue;
    if (journey.completedAt === null) throw new Error('Invalid journey lifecycle.');
    const month = calendarMonth(formatter, journey.startedAt);
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
