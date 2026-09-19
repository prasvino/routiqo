import {
  readSignalReceipt,
  readSignalStopResponse,
  readUuid,
  type LiveSignalReceipt,
} from './browser-live-private';

export type LiveSignalRecoveryPhase =
  | 'accepting'
  | 'acceptance_uncertain'
  | 'accepted'
  | 'accepted_metadata_expired'
  | 'stopping'
  | 'stop_uncertain'
  | 'stopped';

export type LiveSignalReceiptState = 'none' | 'available' | 'expired';

export interface LiveSignalRecoveryTicket {
  readonly accountId: string;
  readonly journeyId: string;
  readonly commandId: string;
}

export interface LiveSignalRecoverySnapshot {
  readonly accountId: string;
  readonly journeyId: string;
  readonly commandId: string;
  readonly phase: LiveSignalRecoveryPhase;
  readonly receiptState: LiveSignalReceiptState;
  readonly receipt: Readonly<LiveSignalReceipt> | null;
}

export interface LiveSignalRecoveryClock {
  wallNow(): number;
  monotonicNow(): number;
}

export type LiveSignalRecoveryErrorKind =
  'account' | 'blocked' | 'capacity' | 'missing' | 'phase' | 'acknowledgement';

export class LiveSignalRecoveryError extends Error {
  constructor(public readonly kind: LiveSignalRecoveryErrorKind) {
    super('Private signal recovery state rejected.');
  }
}

interface ReceiptCapture {
  receipt: Readonly<LiveSignalReceipt>;
  capturedMonotonic: number;
  monotonicDeadline: number;
  retainUntil: number;
}

interface RecoveryRecord {
  accountId: string;
  journeyId: string;
  commandId: string;
  phase: LiveSignalRecoveryPhase;
  ticket: LiveSignalRecoveryTicket | null;
  receipt: ReceiptCapture | null;
  receiptState: LiveSignalReceiptState;
}

const maximumRecords = 5;
const maximumReceiptRetentionMilliseconds = 24 * 60 * 60 * 1000;
const unresolvedPhases = new Set<LiveSignalRecoveryPhase>([
  'accepting',
  'acceptance_uncertain',
  'stopping',
  'stop_uncertain',
]);
const forgettablePhases = new Set<LiveSignalRecoveryPhase>([
  'acceptance_uncertain',
  'accepted',
  'accepted_metadata_expired',
  'stop_uncertain',
]);

const defaultClock: LiveSignalRecoveryClock = {
  wallNow: () => Date.now(),
  monotonicNow: () => performance.now(),
};

function fail(kind: LiveSignalRecoveryErrorKind): never {
  throw new LiveSignalRecoveryError(kind);
}

function ticket(accountId: string, journeyId: string, commandId: string) {
  return Object.freeze({ accountId, journeyId, commandId });
}

function copyReceipt(receipt: LiveSignalReceipt): Readonly<LiveSignalReceipt> {
  return Object.freeze({
    commandId: receipt.commandId,
    status: receipt.status,
    receivedAt: receipt.receivedAt,
    expiresAt: receipt.expiresAt,
    retainUntil: receipt.retainUntil,
  });
}

function identities(
  accountId: string,
  journeyId: string,
  commandId: string,
): [string, string, string] {
  return [readUuid(accountId), readUuid(journeyId), readUuid(commandId)];
}

export class LiveSignalRecoveryCoordinator {
  readonly #clock: LiveSignalRecoveryClock;
  readonly #records = new Map<string, RecoveryRecord>();
  #accountId: string | null = null;

  constructor(clock: LiveSignalRecoveryClock = defaultClock) {
    this.#clock = clock;
  }

  setAccount(accountId: string | null): void {
    const next = accountId === null ? null : readUuid(accountId);
    if (next === this.#accountId) return;
    this.#accountId = next;
    this.#records.clear();
  }

  canBeginIssuance(accountId: string): boolean {
    accountId = readUuid(accountId);
    return (
      accountId === this.#accountId &&
      this.#records.size < maximumRecords &&
      !Array.from(this.#records.values()).some((record) => unresolvedPhases.has(record.phase))
    );
  }

  beginAcceptance(
    accountId: string,
    journeyId: string,
    commandId: string,
  ): LiveSignalRecoveryTicket {
    [accountId, journeyId, commandId] = identities(accountId, journeyId, commandId);
    this.#requireAccount(accountId);
    if (this.#records.has(commandId)) fail('phase');
    if (this.#records.size >= maximumRecords) fail('capacity');
    if (Array.from(this.#records.values()).some((record) => unresolvedPhases.has(record.phase)))
      fail('blocked');
    const operation = ticket(accountId, journeyId, commandId);
    this.#records.set(commandId, {
      accountId,
      journeyId,
      commandId,
      phase: 'accepting',
      ticket: operation,
      receipt: null,
      receiptState: 'none',
    });
    return operation;
  }

  confirmAcceptance(operation: LiveSignalRecoveryTicket, value: unknown): boolean {
    const record = this.#current(operation, 'accepting');
    if (!record) return false;
    const receipt = readSignalReceipt(value, record.commandId, false);
    record.phase = receipt.status === 'accepted' ? 'accepted' : 'stopped';
    record.ticket = null;
    this.#capture(record, receipt);
    return true;
  }

  markAcceptanceUncertain(operation: LiveSignalRecoveryTicket): boolean {
    const record = this.#current(operation, 'accepting');
    if (!record) return false;
    record.phase = 'acceptance_uncertain';
    record.ticket = null;
    return true;
  }

  beginStop(accountId: string, journeyId: string, commandId: string): LiveSignalRecoveryTicket {
    [accountId, journeyId, commandId] = identities(accountId, journeyId, commandId);
    this.#requireAccount(accountId);
    const record = this.#records.get(commandId);
    if (!record || record.accountId !== accountId || record.journeyId !== journeyId)
      fail('missing');
    if (record.phase === 'stopped' || record.phase === 'stopping') fail('phase');
    const operation = ticket(accountId, journeyId, commandId);
    record.phase = 'stopping';
    record.ticket = operation;
    return operation;
  }

  confirmStop(operation: LiveSignalRecoveryTicket, value: unknown): boolean {
    const record = this.#current(operation, 'stopping');
    if (!record) return false;
    const result = readSignalStopResponse(value, record.commandId);
    record.phase = 'stopped';
    if (result.receipt === null && record.receiptState === 'none') {
      record.receipt = null;
      record.receiptState = 'none';
    } else if (result.receipt !== null) {
      this.#capture(record, result.receipt);
    }
    record.ticket = null;
    return true;
  }

  markStopUncertain(operation: LiveSignalRecoveryTicket): boolean {
    const record = this.#current(operation, 'stopping');
    if (!record) return false;
    record.phase = 'stop_uncertain';
    record.ticket = null;
    return true;
  }

  dismissTerminal(accountId: string, journeyId: string, commandId: string): void {
    const record = this.#record(accountId, journeyId, commandId);
    if (record.phase !== 'stopped') fail('phase');
    this.#records.delete(record.commandId);
  }

  forgetRecovery(
    accountId: string,
    journeyId: string,
    commandId: string,
    acknowledgement: { readonly acknowledgeServerMayContinue: true },
  ): void {
    if (acknowledgement?.acknowledgeServerMayContinue !== true) fail('acknowledgement');
    const record = this.#record(accountId, journeyId, commandId);
    if (!forgettablePhases.has(record.phase)) fail('phase');
    this.#records.delete(record.commandId);
  }

  snapshot(accountId: string): readonly Readonly<LiveSignalRecoverySnapshot>[] {
    accountId = readUuid(accountId);
    if (accountId !== this.#accountId) return Object.freeze([]);
    this.#expireMetadata();
    return Object.freeze(
      Array.from(this.#records.values(), (record) =>
        Object.freeze({
          accountId: record.accountId,
          journeyId: record.journeyId,
          commandId: record.commandId,
          phase: record.phase,
          receiptState: record.receiptState,
          receipt: record.receipt?.receipt ?? null,
        }),
      ),
    );
  }

  #requireAccount(accountId: string): void {
    if (accountId !== this.#accountId) fail('account');
  }

  #record(accountId: string, journeyId: string, commandId: string): RecoveryRecord {
    [accountId, journeyId, commandId] = identities(accountId, journeyId, commandId);
    this.#requireAccount(accountId);
    const record = this.#records.get(commandId);
    if (!record || record.accountId !== accountId || record.journeyId !== journeyId)
      fail('missing');
    return record;
  }

  #current(
    operation: LiveSignalRecoveryTicket,
    phase: 'accepting' | 'stopping',
  ): RecoveryRecord | null {
    const record = this.#records.get(operation.commandId);
    return record?.ticket === operation && record.phase === phase ? record : null;
  }

  #capture(record: RecoveryRecord, value: LiveSignalReceipt): void {
    const now = this.#clock.monotonicNow();
    const retainUntil = Date.parse(value.retainUntil);
    if (!Number.isFinite(now) || !Number.isFinite(retainUntil)) {
      record.receipt = null;
      record.receiptState = 'expired';
      if (record.phase === 'accepted') record.phase = 'accepted_metadata_expired';
      return;
    }
    record.receipt = {
      receipt: copyReceipt(value),
      capturedMonotonic: now,
      monotonicDeadline: now + maximumReceiptRetentionMilliseconds,
      retainUntil,
    };
    record.receiptState = 'available';
    this.#expireRecordMetadata(record);
  }

  #expireMetadata(): void {
    for (const record of this.#records.values()) this.#expireRecordMetadata(record);
  }

  #expireRecordMetadata(record: RecoveryRecord): void {
    if (!record.receipt) return;
    const wallNow = this.#clock.wallNow();
    const monotonicNow = this.#clock.monotonicNow();
    if (
      !Number.isFinite(wallNow) ||
      !Number.isFinite(monotonicNow) ||
      monotonicNow < record.receipt.capturedMonotonic ||
      monotonicNow >= record.receipt.monotonicDeadline ||
      wallNow >= record.receipt.retainUntil
    ) {
      record.receipt = null;
      record.receiptState = 'expired';
      if (record.phase === 'accepted') record.phase = 'accepted_metadata_expired';
    }
  }
}
