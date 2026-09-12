'use client';

import { useEffect, useMemo, useState } from 'react';
import { summarizeCommutes, type JourneySnapshots } from '@routiqo/shared';

export function CommuteSummaries({
  snapshots,
  account,
}: {
  snapshots: JourneySnapshots;
  account: string;
}) {
  const [zone, setZone] = useState<string | null>(null);
  useEffect(() => {
    try {
      setZone(Intl.DateTimeFormat().resolvedOptions().timeZone);
    } catch {
      setZone('');
    }
  }, []);
  const result = useMemo(() => {
    if (zone === null) return null;
    try {
      return { months: summarizeCommutes(snapshots, account, zone), failed: false };
    } catch {
      return { months: [], failed: true };
    }
  }, [snapshots, account, zone]);

  return (
    <details className="commute-summaries">
      <summary>Your commute summaries</summary>
      <p className="fine-print">
        Based on confirmed journeys saved on this device. Older journeys and records from other
        devices may be missing. Recorded elapsed time is the time between starting and finishing a
        journey, including any stops.
      </p>
      {result === null ? (
        <p role="status">Loading summaries…</p>
      ) : result.failed ? (
        <p role="alert">
          Commute summaries are unavailable. Your saved records have not been changed.
        </p>
      ) : result.months.length === 0 ? (
        <p>Complete a commute and save its confirmation to see a monthly summary here.</p>
      ) : (
        <>
          <p className="fine-print">Grouped by start month · {zone}</p>
          <ul>
            {result.months.map((month) => (
              <li key={month.month}>
                <h3>
                  {new Intl.DateTimeFormat('en-IN', {
                    month: 'long',
                    year: 'numeric',
                    timeZone: 'UTC',
                  }).format(new Date(`${month.month}-01T00:00:00Z`))}
                </h3>
                <p>
                  {month.journeys} confirmed {month.journeys === 1 ? 'commute' : 'commutes'} ·{' '}
                  {month.recordedMinutes === 0
                    ? 'Under 1 recorded minute'
                    : `${month.recordedMinutes.toLocaleString('en-IN')} recorded ${month.recordedMinutes === 1 ? 'minute' : 'minutes'}`}
                </p>
              </li>
            ))}
          </ul>
        </>
      )}
    </details>
  );
}
