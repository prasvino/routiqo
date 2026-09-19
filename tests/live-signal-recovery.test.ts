import { describe, expect, it } from 'vitest';
import {
  LiveSignalRecoveryCoordinator,
  LiveSignalRecoveryError,
  type LiveSignalRecoveryClock,
} from '../apps/web/lib/live-signal-recovery';

const accountId = '00000000-0000-4000-8000-000000000001';
const otherAccountId = '00000000-0000-4000-8000-000000000002';
const journeyId = '00000000-0000-4000-8000-000000000003';
const otherJourneyId = '00000000-0000-4000-8000-000000000004';
const commandId = (value: number) =>
  `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`;

class TestClock implements LiveSignalRecoveryClock {
  wall = Date.parse('2026-09-19T10:00:00Z');
  monotonic = 1_000;

  wallNow() {
    return this.wall;
  }

  monotonicNow() {
    return this.monotonic;
  }
}

const acceptedReceipt = (command: string) => ({
  commandId: command,
  status: 'accepted' as const,
  receivedAt: '2026-09-19T10:00:00.123456789Z',
  expiresAt: '2026-09-19T10:15:00.123456789Z',
  retainUntil: '2026-09-20T10:00:00.123456789Z',
});

const terminalReceipt = (command: string, status: 'withdrawn' | 'superseded' = 'withdrawn') => ({
  ...acceptedReceipt(command),
  status,
});

function coordinator(clock = new TestClock()) {
  const recovery = new LiveSignalRecoveryCoordinator(clock);
  recovery.setAccount(accountId);
  return { recovery, clock };
}

function expectKind(action: () => unknown, kind: LiveSignalRecoveryError['kind']) {
  try {
    action();
    throw new Error('Expected recovery operation to fail.');
  } catch (error) {
    expect(error).toBeInstanceOf(LiveSignalRecoveryError);
    expect((error as LiveSignalRecoveryError).kind).toBe(kind);
    expect((error as Error).message).toBe('Private signal recovery state rejected.');
  }
}

describe('memory-only LIVE signal recovery', () => {
  it('is account-bound, freezes tickets and clears records while fencing old operations', () => {
    const { recovery } = coordinator();
    const operation = recovery.beginAcceptance(accountId, journeyId, commandId(10));
    expect(Object.isFrozen(operation)).toBe(true);
    expect(recovery.snapshot(otherAccountId)).toEqual([]);

    recovery.setAccount(otherAccountId);
    expect(recovery.snapshot(otherAccountId)).toEqual([]);
    expect(recovery.confirmAcceptance(operation, acceptedReceipt(commandId(10)))).toBe(false);
    expectKind(() => recovery.beginAcceptance(accountId, journeyId, commandId(11)), 'account');

    recovery.setAccount(null);
    expectKind(() => recovery.beginAcceptance(otherAccountId, journeyId, commandId(12)), 'account');
  });

  it('blocks additional issuance while any operation is unresolved', () => {
    const { recovery } = coordinator();
    const operation = recovery.beginAcceptance(accountId, journeyId, commandId(20));
    expect(recovery.canBeginIssuance(accountId)).toBe(false);
    expectKind(
      () =>
        recovery.forgetRecovery(accountId, journeyId, commandId(20), {
          acknowledgeServerMayContinue: true,
        }),
      'phase',
    );
    expectKind(() => recovery.beginAcceptance(accountId, journeyId, commandId(21)), 'blocked');

    expect(recovery.markAcceptanceUncertain(operation)).toBe(true);
    expect(recovery.canBeginIssuance(accountId)).toBe(false);
    recovery.forgetRecovery(accountId, journeyId, commandId(20), {
      acknowledgeServerMayContinue: true,
    });
    expect(recovery.canBeginIssuance(accountId)).toBe(true);
  });

  it('bounds all handles to five without eviction and frees capacity only explicitly', () => {
    const { recovery } = coordinator();
    for (let index = 1; index <= 5; index += 1) {
      const command = commandId(100 + index);
      const operation = recovery.beginAcceptance(accountId, journeyId, command);
      expect(recovery.confirmAcceptance(operation, acceptedReceipt(command))).toBe(true);
    }
    expect(recovery.snapshot(accountId)).toHaveLength(5);
    expect(recovery.canBeginIssuance(accountId)).toBe(false);
    expectKind(() => recovery.beginAcceptance(accountId, journeyId, commandId(106)), 'capacity');

    expectKind(() => recovery.dismissTerminal(accountId, journeyId, commandId(101)), 'phase');
    recovery.forgetRecovery(accountId, journeyId, commandId(101), {
      acknowledgeServerMayContinue: true,
    });
    expect(recovery.snapshot(accountId)).toHaveLength(4);
    expect(recovery.canBeginIssuance(accountId)).toBe(true);
  });

  it('fences delayed acceptance when stop starts and keeps failed stop uncertain', () => {
    const { recovery } = coordinator();
    const command = commandId(200);
    const acceptance = recovery.beginAcceptance(accountId, journeyId, command);
    const stopping = recovery.beginStop(accountId, journeyId, command);

    expect(recovery.confirmAcceptance(acceptance, acceptedReceipt(command))).toBe(false);
    expect(recovery.markAcceptanceUncertain(acceptance)).toBe(false);
    expect(recovery.confirmAcceptance(stopping, acceptedReceipt(command))).toBe(false);
    expect(recovery.markStopUncertain(stopping)).toBe(true);
    expect(recovery.snapshot(accountId)[0]).toMatchObject({
      commandId: command,
      phase: 'stop_uncertain',
      receiptState: 'none',
      receipt: null,
    });
    expect(recovery.canBeginIssuance(accountId)).toBe(false);

    const retry = recovery.beginStop(accountId, journeyId, command);
    expect(recovery.markAcceptanceUncertain(retry)).toBe(false);
    expect(
      recovery.confirmStop(retry, { commandId: command, status: 'stopped', receipt: null }),
    ).toBe(true);
    expect(
      recovery.confirmStop(stopping, { commandId: command, status: 'stopped', receipt: null }),
    ).toBe(false);
    expect(recovery.snapshot(accountId)[0]).toMatchObject({
      phase: 'stopped',
      receiptState: 'none',
    });
    expect(recovery.canBeginIssuance(accountId)).toBe(true);
  });

  it('classifies terminal acceptance responses and separates dismissal from forgetting', () => {
    const { recovery } = coordinator();
    const command = commandId(300);
    const operation = recovery.beginAcceptance(accountId, journeyId, command);
    expect(recovery.confirmAcceptance(operation, terminalReceipt(command, 'superseded'))).toBe(
      true,
    );
    expect(recovery.snapshot(accountId)[0]).toMatchObject({
      phase: 'stopped',
      receiptState: 'available',
      receipt: { commandId: command, status: 'superseded' },
    });
    expectKind(
      () =>
        recovery.forgetRecovery(accountId, journeyId, command, {
          acknowledgeServerMayContinue: true,
        }),
      'phase',
    );
    recovery.dismissTerminal(accountId, journeyId, command);
    expect(recovery.snapshot(accountId)).toEqual([]);
  });

  it('does not let a null stop receipt erase locally confirmed acceptance evidence', () => {
    const { recovery } = coordinator();
    const command = commandId(350);
    const acceptance = recovery.beginAcceptance(accountId, journeyId, command);
    recovery.confirmAcceptance(acceptance, acceptedReceipt(command));
    const stopping = recovery.beginStop(accountId, journeyId, command);

    recovery.confirmStop(stopping, { commandId: command, status: 'stopped', receipt: null });
    expect(recovery.snapshot(accountId)[0]).toMatchObject({
      phase: 'stopped',
      receiptState: 'available',
      receipt: { commandId: command, status: 'accepted' },
    });
  });

  it('requires explicit forgetting and exact account, journey and command identity', () => {
    const { recovery } = coordinator();
    const command = commandId(400);
    const operation = recovery.beginAcceptance(accountId, journeyId, command);
    recovery.confirmAcceptance(operation, acceptedReceipt(command));

    expectKind(
      () =>
        recovery.forgetRecovery(accountId, journeyId, command, {
          acknowledgeServerMayContinue: false as true,
        }),
      'acknowledgement',
    );
    expectKind(() => recovery.beginStop(accountId, otherJourneyId, command), 'missing');
    expectKind(() => recovery.beginStop(accountId, journeyId, commandId(401)), 'missing');
    expect(recovery.snapshot(accountId)).toHaveLength(1);
  });

  it('copies only minimized receipt metadata into immutable snapshots', () => {
    const { recovery } = coordinator();
    const command = commandId(500);
    const operation = recovery.beginAcceptance(accountId, journeyId, command);
    const receipt = acceptedReceipt(command);
    recovery.confirmAcceptance(operation, receipt);
    const snapshots = recovery.snapshot(accountId);
    const snapshot = snapshots[0]!;

    expect(Object.isFrozen(snapshots)).toBe(true);
    expect(Object.isFrozen(snapshot)).toBe(true);
    expect(Object.isFrozen(snapshot.receipt)).toBe(true);
    expect(Object.keys(snapshot).sort()).toEqual([
      'accountId',
      'commandId',
      'journeyId',
      'phase',
      'receipt',
      'receiptState',
    ]);
    expect(Object.keys(snapshot.receipt!).sort()).toEqual([
      'commandId',
      'expiresAt',
      'receivedAt',
      'retainUntil',
      'status',
    ]);
    expect(JSON.stringify(snapshot)).not.toMatch(/label|value|fingerprint|anchor/i);
  });

  it('expires accepted metadata by server time without losing its stop handle', () => {
    const { recovery, clock } = coordinator();
    const command = commandId(600);
    const operation = recovery.beginAcceptance(accountId, journeyId, command);
    recovery.confirmAcceptance(operation, acceptedReceipt(command));

    clock.wall = Date.parse('2026-09-20T10:00:00.124Z');
    expect(recovery.snapshot(accountId)[0]).toEqual({
      accountId,
      journeyId,
      commandId: command,
      phase: 'accepted_metadata_expired',
      receiptState: 'expired',
      receipt: null,
    });
    expect(() => recovery.beginStop(accountId, journeyId, command)).not.toThrow();
  });

  it('marks already-expired accepted metadata during confirmation', () => {
    const { recovery, clock } = coordinator();
    const command = commandId(650);
    const operation = recovery.beginAcceptance(accountId, journeyId, command);
    clock.wall = Date.parse('2026-09-20T10:00:00.124Z');

    expect(recovery.confirmAcceptance(operation, acceptedReceipt(command))).toBe(true);
    expect(recovery.snapshot(accountId)[0]).toMatchObject({
      phase: 'accepted_metadata_expired',
      receiptState: 'expired',
      receipt: null,
    });
  });

  it('bounds metadata by monotonic elapsed time and never changes stopped to an unconfirmed phase', () => {
    const { recovery, clock } = coordinator();
    const accepted = commandId(700);
    const terminal = commandId(701);
    const first = recovery.beginAcceptance(accountId, journeyId, accepted);
    recovery.confirmAcceptance(first, acceptedReceipt(accepted));
    const second = recovery.beginAcceptance(accountId, journeyId, terminal);
    recovery.confirmAcceptance(second, acceptedReceipt(terminal));
    const stopping = recovery.beginStop(accountId, journeyId, terminal);
    recovery.confirmStop(stopping, {
      commandId: terminal,
      status: 'stopped',
      receipt: terminalReceipt(terminal),
    });

    clock.wall = Date.parse('2026-09-19T09:00:00Z');
    clock.monotonic += 24 * 60 * 60 * 1000;
    expect(recovery.snapshot(accountId)).toEqual([
      expect.objectContaining({
        commandId: accepted,
        phase: 'accepted_metadata_expired',
        receiptState: 'expired',
        receipt: null,
      }),
      expect.objectContaining({
        commandId: terminal,
        phase: 'stopped',
        receiptState: 'expired',
        receipt: null,
      }),
    ]);
  });

  it('rejects malformed current results but ignores malformed stale results', () => {
    const { recovery } = coordinator();
    const command = commandId(800);
    const acceptance = recovery.beginAcceptance(accountId, journeyId, command);
    expect(
      recovery.confirmStop(acceptance, { commandId: command, status: 'stopped', receipt: null }),
    ).toBe(false);
    expect(() =>
      recovery.confirmAcceptance(acceptance, {
        ...acceptedReceipt(command),
        commandId: commandId(801),
      }),
    ).toThrow('Private LIVE request failed.');

    const stopping = recovery.beginStop(accountId, journeyId, command);
    expect(recovery.confirmAcceptance(acceptance, { private: 'content' })).toBe(false);
    expect(() =>
      recovery.confirmStop(stopping, {
        commandId: command,
        status: 'stopped',
        receipt: { ...terminalReceipt(command), expiresAt: 'invalid' },
      }),
    ).toThrow('Private LIVE request failed.');
  });
});
