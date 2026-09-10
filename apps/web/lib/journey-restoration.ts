import { readBrowserJourney, readRecentBrowserJourneys } from './browser-journeys';
import { mergeBrowserJourneyHistory, readBrowserJourneyPartition } from './journey-storage';

/** Restore a bounded recent page plus authoritative state for an older cached active journey. */
export async function restoreRecentBrowserJourneyHistory(account: string) {
  const before = await readBrowserJourneyPartition(account);
  const active = before.snapshots.journeys.filter((journey) => journey.status === 'active');
  if (active.length > 1) throw new Error('Saved journeys need individual reconciliation.');
  const recent = await readRecentBrowserJourneys(account);
  const previous = active[0];
  const extra =
    previous && !recent.some((journey) => journey.id === previous.id)
      ? await readBrowserJourney(account, previous.id)
      : undefined;
  if (extra === null) throw new Error('The saved active journey could not be confirmed.');
  const partition = await mergeBrowserJourneyHistory(account, extra ? [...recent, extra] : recent);
  return { partition, recentCount: recent.length };
}
