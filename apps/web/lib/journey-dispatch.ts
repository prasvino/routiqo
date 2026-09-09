import {
  claimJourneyCommand,
  dispatchJourneyOnce,
  dispatchJourneyBatch,
  type JourneyDispatchPort,
  resumeJourneyAuthentication,
  settleJourneyCommand,
} from '@routiqo/shared';
import { browserAccount } from './browser-auth';
import { sendBrowserJourney } from './browser-journeys';
import { acknowledgeBrowserJourney, updateBrowserJourneyOutbox } from './journey-storage';

/** Releases only authentication blocks after server verification; never sends commands. */
export async function restoreBrowserJourneyAuthentication(accountId: string): Promise<boolean> {
  if ((await browserAccount())?.accountId !== accountId) return false;
  await updateBrowserJourneyOutbox(accountId, (current) =>
    resumeJourneyAuthentication(current, accountId, Date.now()),
  );
  return true;
}

/** Explicit single attempt; importing this module never schedules or uploads work. */
function browserPort(): JourneyDispatchPort {
  return {
    activeAccount: async () => (await browserAccount())?.accountId ?? null,
    claim: async (account, now, lease) => {
      const queue = await updateBrowserJourneyOutbox(account, (current) =>
        claimJourneyCommand(current, now, lease),
      );
      return queue.entries[0]?.lease?.token === lease ? queue.entries[0] : null;
    },
    send: sendBrowserJourney,
    acknowledge: acknowledgeBrowserJourney,
    settle: async (account, lease, outcome, now) => {
      await updateBrowserJourneyOutbox(account, (current) =>
        settleJourneyCommand(current, lease, outcome, now, Math.random()),
      );
    },
    now: Date.now,
    lease: () => crypto.randomUUID(),
  };
}
export function dispatchBrowserJourneyOnce(accountId: string) {
  return dispatchJourneyOnce(browserPort(), accountId);
}
export function dispatchBrowserJourneyBatch(accountId: string, signal?: AbortSignal) {
  return dispatchJourneyBatch(browserPort(), accountId, signal ? { signal } : {});
}
