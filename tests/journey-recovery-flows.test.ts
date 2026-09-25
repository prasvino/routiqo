import { IDBFactory } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JourneyCommand } from '../packages/shared/src/journey-outbox';
import { readBrowserJourney } from '../apps/web/lib/browser-journeys';
import {
  dispatchBrowserJourneyBatch,
  dispatchBrowserJourneyOnce,
  restoreBrowserJourneyAuthentication,
} from '../apps/web/lib/journey-dispatch';
import { restoreRecentBrowserJourneyHistory } from '../apps/web/lib/journey-restoration';
import {
  discardBrowserJourneyAction,
  queueBrowserJourneyAction,
  readBrowserJourneyPartition,
  reconcileBrowserJourney,
  retireBrowserJourneyPartition,
} from '../apps/web/lib/journey-storage';
import { SyntheticJourneyServer } from './fixtures/synthetic-journey-server';

// Confirms the supported web recovery flows end to end, across two devices that share one
// synthetic server: history restore, exact replay after another device acted, reconcile and discard
// of a refused action, re-authentication, and account isolation on a shared device.

const alice = '00000000-0000-4000-8000-0000000000a1';
const bob = '00000000-0000-4000-8000-0000000000b2';
const commute = '00000000-0000-4000-8000-000000000301';
const trip = '00000000-0000-4000-8000-000000000302';
const bobTrip = '00000000-0000-4000-8000-000000000401';

let server: SyntheticJourneyServer;
const devices = { phone: new IDBFactory(), laptop: new IDBFactory() };
type Device = keyof typeof devices;

/** Run storage work on one device. Each device has its own IndexedDB; the server is shared. */
async function on<T>(device: Device, work: () => Promise<T>): Promise<T> {
  vi.stubGlobal('indexedDB', devices[device]);
  return work();
}
const start = (journeyId: string, kind: 'trip' | 'commute'): JourneyCommand => ({
  journeyId,
  action: 'start',
  kind,
});
const finish = (journeyId: string): JourneyCommand => ({ journeyId, action: 'complete' });
const queue = (account: string, command: JourneyCommand) =>
  queueBrowserJourneyAction(account, command, server.now);
const summary = async (account: string) => {
  const partition = await readBrowserJourneyPartition(account);
  return {
    pending: partition.outbox.entries.map((entry) => ({
      command: entry.command,
      blocked: entry.blocked,
    })),
    journeys: partition.snapshots.journeys.map(({ id, kind, status }) => ({ id, kind, status })),
  };
};
/** The observation a "Check server status" read provides for a blocked action. */
async function observe(account: string, journeyId: string) {
  const journey = await readBrowserJourney(account, journeyId);
  return journey ? { kind: journey.kind, status: journey.status } : null;
}

beforeEach(() => {
  server = new SyntheticJourneyServer(alice);
  devices.phone = new IDBFactory();
  devices.laptop = new IDBFactory();
  vi.stubGlobal('fetch', server.fetch);
  vi.spyOn(Date, 'now').mockImplementation(() => server.now);
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('supported journey recovery flows across devices', () => {
  it('restores history on a new device and replays a late finish exactly, keeping acknowledged work', async () => {
    await on('phone', async () => {
      await queue(alice, start(commute, 'commute'));
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('acknowledged');
      // The phone goes offline after queueing its own finish.
      await queue(alice, finish(commute));
    });

    server.now += 20 * 60_000;
    const laptop = await on('laptop', async () => {
      const restored = await restoreRecentBrowserJourneyHistory(alice);
      expect(restored.recentCount).toBe(1);
      await queue(alice, finish(commute));
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('acknowledged');
      return readBrowserJourneyPartition(alice);
    });
    const completedAt = laptop.snapshots.journeys[0]?.completedAt;
    expect(completedAt).not.toBeNull();

    server.now += 5 * 60_000;
    await on('phone', async () => {
      // Restoring history while the finish is unsent shows the server's completion and keeps the
      // pending finish; nothing local is overwritten.
      await restoreRecentBrowserJourneyHistory(alice);
      expect(await summary(alice)).toEqual({
        pending: [{ command: finish(commute), blocked: null }],
        journeys: [{ id: commute, kind: 'commute', status: 'completed' }],
      });
      // The phone's stale finish replays against the already completed journey.
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('acknowledged');
      const phone = await readBrowserJourneyPartition(alice);
      expect(phone.outbox.entries).toEqual([]);
      expect(phone.snapshots.journeys).toEqual(laptop.snapshots.journeys);
      // A later restore neither duplicates nor regresses the acknowledged completion.
      await restoreRecentBrowserJourneyHistory(alice);
      expect((await readBrowserJourneyPartition(alice)).snapshots).toEqual(phone.snapshots);
    });
    expect(server.stored(alice)).toEqual([{ id: commute, kind: 'commute', status: 'completed' }]);
    expect(server.applied.filter((write) => write.endsWith(`complete:${commute}`))).toHaveLength(2);
  });

  it('discards a start refused because another device began a journey, then restores and finishes that journey', async () => {
    await on('laptop', async () => {
      await queue(alice, start(trip, 'trip'));
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('acknowledged');
    });

    const result = await on('phone', async () => {
      // Offline, the phone knows nothing of the laptop's trip and records its own commute.
      await queue(alice, start(commute, 'commute'));
      await queue(alice, finish(commute));
      expect(await dispatchBrowserJourneyBatch(alice)).toEqual({
        acknowledged: 0,
        reason: 'blocked',
      });
      expect((await summary(alice)).pending).toEqual([
        { command: start(commute, 'commute'), blocked: 'conflict' },
        { command: finish(commute), blocked: null },
      ]);
      // Retrying cannot send a refused action.
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('idle');

      const observation = await observe(alice, commute);
      expect(observation).toBeNull();
      await discardBrowserJourneyAction(alice, start(commute, 'commute'), observation);
      await restoreRecentBrowserJourneyHistory(alice);
      await queue(alice, finish(trip));
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('acknowledged');
      return summary(alice);
    });

    expect(result).toEqual({
      pending: [],
      journeys: [{ id: trip, kind: 'trip', status: 'completed' }],
    });
    expect(server.stored(alice)).toEqual([{ id: trip, kind: 'trip', status: 'completed' }]);
  });

  it('refuses to discard work the server already applied and reconciles it, continuing with following work', async () => {
    await on('phone', async () => {
      await queue(alice, start(trip, 'trip'));
      await queue(alice, finish(trip));
      // Reconciliation never removes an action that has not been refused.
      const claimed = {
        id: trip,
        kind: 'trip',
        status: 'active',
        startedAt: new Date(server.now).toISOString(),
        completedAt: null,
      };
      expect(await reconcileBrowserJourney(alice, start(trip, 'trip'), claimed)).toBe(false);
      // The server applies the start but an intermediary answers with an error.
      server.faults.push('rejected-after');
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('blocked');

      const observation = await observe(alice, trip);
      expect(observation).toEqual({ kind: 'trip', status: 'active' });
      await expect(
        discardBrowserJourneyAction(alice, start(trip, 'trip'), observation),
      ).rejects.toThrow(/Reconcile/);
      expect((await summary(alice)).pending).toHaveLength(2);

      const journey = await readBrowserJourney(alice, trip);
      expect(await reconcileBrowserJourney(alice, start(trip, 'trip'), journey)).toBe(true);
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('acknowledged');
      expect(await summary(alice)).toEqual({
        pending: [],
        journeys: [{ id: trip, kind: 'trip', status: 'completed' }],
      });
    });
    expect(server.applied).toEqual([`${alice}:start:${trip}:trip`, `${alice}:complete:${trip}`]);
  });

  it('holds work through an expired session, never releases it to another account, and replays it after sign-in', async () => {
    await on('phone', async () => {
      await queue(alice, start(commute, 'commute'));
      await queue(alice, finish(commute));
      server.faults.push('unauthorized');
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('blocked');
      const blocked = await summary(alice);
      expect(blocked.pending[0]?.blocked).toBe('authentication');

      // Bob signs in on the same phone: Alice's work stays paused and is never sent as Bob.
      server.session = bob;
      expect(await restoreBrowserJourneyAuthentication(alice)).toBe(false);
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('idle');
      expect(await summary(alice)).toEqual(blocked);

      server.session = alice;
      expect(await restoreBrowserJourneyAuthentication(alice)).toBe(true);
      expect(await dispatchBrowserJourneyBatch(alice)).toEqual({ acknowledged: 2, reason: 'idle' });
    });
    expect(server.attempts.map(({ account, operation }) => `${account}:${operation}`)).toEqual([
      `${alice}:start:${commute}:commute`,
      `${alice}:start:${commute}:commute`,
      `${alice}:complete:${commute}`,
    ]);
    expect(server.stored(bob)).toEqual([]);
  });

  it('keeps accounts isolated on a shared device, including after one account is deleted there', async () => {
    await on('laptop', async () => {
      await queue(alice, start(commute, 'commute'));
      expect(await dispatchBrowserJourneyOnce(alice)).toBe('acknowledged');
      await queue(alice, finish(commute));

      server.session = bob;
      await queue(bob, start(bobTrip, 'trip'));
      expect(await dispatchBrowserJourneyOnce(bob)).toBe('acknowledged');
      await restoreRecentBrowserJourneyHistory(bob);
      expect(await summary(bob)).toEqual({
        pending: [],
        journeys: [{ id: bobTrip, kind: 'trip', status: 'active' }],
      });
      // Alice's unsent finish is untouched by Bob's session.
      expect((await summary(alice)).pending).toEqual([{ command: finish(commute), blocked: null }]);

      await retireBrowserJourneyPartition(alice);
      await expect(readBrowserJourneyPartition(alice)).rejects.toThrow(/deleted/);
      server.session = alice;
      await expect(dispatchBrowserJourneyOnce(alice)).rejects.toThrow(/deleted/);
      await expect(restoreRecentBrowserJourneyHistory(alice)).rejects.toThrow(/deleted/);

      server.session = bob;
      expect(await summary(bob)).toEqual({
        pending: [],
        journeys: [{ id: bobTrip, kind: 'trip', status: 'active' }],
      });
    });
    expect(server.applied.filter((write) => write.startsWith(`${alice}:complete`))).toEqual([]);
    expect(server.stored(alice)).toEqual([{ id: commute, kind: 'commute', status: 'active' }]);
  });
});
