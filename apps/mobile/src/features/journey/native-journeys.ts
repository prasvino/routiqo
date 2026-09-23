import {
  claimJourneyCommand,
  dispatchJourneyBatch,
  readServerJourney,
  settleJourneyCommand,
  type JourneyCommand,
  type JourneyDelivery,
  type JourneyDispatchPort,
} from '@routiqo/shared';
import type { SQLiteDatabase } from 'expo-sqlite';
import { randomUUID } from 'expo-crypto';
import type { createNativeAccount } from '../../auth/native-account';
import { NativeHttpStatus } from '../../auth/safe-transport';
import {
  acknowledgeJourneyResult,
  mergeMobileJourneyHistory,
  reconcileMobileJourney,
  readMobileJourneyPartition,
  resumeMobileJourneyAuthentication,
  updateJourneyOutbox,
} from '../../storage/journey-outbox';

type Account = ReturnType<typeof createNativeAccount>;
const prefix = '/api/v1/native/journeys';
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;

export function createNativeJourneys(db: SQLiteDatabase, identity: Account) {
  async function send(accountId: string, command: JourneyCommand): Promise<JourneyDelivery> {
    if (!uuid.test(command.journeyId)) return { outcome: 'rejected' };
    try {
      const response =
        command.action === 'start'
          ? await identity.verifiedRequest(prefix, 'POST', {
              accountId,
              body: { id: command.journeyId, kind: command.kind },
            })
          : await identity.verifiedRequest(`${prefix}/${command.journeyId}/complete`, 'POST', {
              accountId,
              body: {},
            });
      return { outcome: 'success', journey: readServerJourney(response) };
    } catch (error) {
      if (error instanceof NativeHttpStatus) {
        if (error.status === 401) return { outcome: 'authentication' };
        if (error.status === 409) return { outcome: 'conflict' };
        if (error.status >= 400 && error.status < 500 && error.status !== 429)
          return { outcome: 'rejected' };
      }
      return { outcome: 'transient' };
    }
  }
  function port(): JourneyDispatchPort {
    return {
      activeAccount: async () => identity.activeAccount(),
      claim: async (accountId, now, lease) => {
        const queue = await updateJourneyOutbox(db, accountId, (current) =>
          claimJourneyCommand(current, now, lease),
        );
        return queue.entries[0]?.lease?.token === lease ? queue.entries[0] : null;
      },
      send,
      acknowledge: (accountId, lease, journey, now) =>
        acknowledgeJourneyResult(db, accountId, lease, journey, now),
      settle: async (accountId, lease, outcome, now) => {
        await updateJourneyOutbox(db, accountId, (current) =>
          settleJourneyCommand(current, lease, outcome, now, Math.random()),
        );
      },
      now: Date.now,
      lease: randomUUID,
    };
  }
  async function resume(accountId: string): Promise<boolean> {
    if (identity.activeAccount() !== accountId) return false;
    return resumeMobileJourneyAuthentication(db, accountId, Date.now());
  }
  async function dispatch(accountId: string, signal?: AbortSignal) {
    return dispatchJourneyBatch(
      port(),
      accountId,
      signal ? { maximum: 5, signal } : { maximum: 5 },
    );
  }
  async function refresh(accountId: string) {
    const raw = await identity.verifiedRequest(prefix, 'GET', { accountId });
    if (
      typeof raw !== 'object' ||
      raw === null ||
      Array.isArray(raw) ||
      Object.keys(raw).length !== 1 ||
      !('journeys' in raw) ||
      !Array.isArray(raw.journeys) ||
      raw.journeys.length > 50
    )
      throw new Error('Journey history is invalid.');
    const recent = raw.journeys as unknown[];
    await mergeMobileJourneyHistory(db, accountId, recent);
    const partition = await readMobileJourneyPartition(db, accountId);
    const blocked = partition.outbox.entries[0];
    if (blocked && ['conflict', 'rejected'].includes(blocked.blocked ?? '')) {
      try {
        const detail = await identity.verifiedRequest(
          `${prefix}/${blocked.command.journeyId}`,
          'GET',
          { accountId },
        );
        await reconcileMobileJourney(db, accountId, blocked.command, detail);
      } catch (error) {
        if (!(error instanceof NativeHttpStatus && error.status === 404)) throw error;
      }
    }
    const olderActive = partition.snapshots.journeys.filter(
      (item) =>
        item.status === 'active' &&
        !recent.some(
          (entry) =>
            typeof entry === 'object' && entry !== null && 'id' in entry && entry.id === item.id,
        ),
    );
    for (const item of olderActive.slice(0, 1)) {
      try {
        const detail = await identity.verifiedRequest(`${prefix}/${item.id}`, 'GET', { accountId });
        await mergeMobileJourneyHistory(db, accountId, [detail]);
      } catch (error) {
        if (error instanceof NativeHttpStatus && error.status === 404)
          throw new Error('A saved active journey needs review.');
        throw error;
      }
    }
    return readMobileJourneyPartition(db, accountId);
  }
  return { send, resume, dispatch, refresh };
}
