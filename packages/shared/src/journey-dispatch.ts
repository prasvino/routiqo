import type { JourneyCommand, OutboxEntry, OutboxOutcome } from './journey-outbox';
export type JourneyDelivery =
  { outcome: 'success'; journey: unknown } | { outcome: Exclude<OutboxOutcome, 'success'> };
export interface JourneyDispatchPort {
  activeAccount(): Promise<string | null>;
  claim(accountId: string, now: number, lease: string): Promise<OutboxEntry | null>;
  send(accountId: string, command: JourneyCommand): Promise<JourneyDelivery>;
  acknowledge(accountId: string, lease: string, journey: unknown, now: number): Promise<boolean>;
  settle(
    accountId: string,
    lease: string,
    outcome: Exclude<OutboxOutcome, 'success'>,
    now: number,
  ): Promise<void>;
  now(): number;
  lease(): string;
}
export async function dispatchJourneyOnce(
  port: JourneyDispatchPort,
  accountId: string,
): Promise<'idle' | 'acknowledged' | 'stale' | 'deferred' | 'blocked'> {
  if ((await port.activeAccount()) !== accountId) return 'idle';
  const lease = port.lease();
  const entry = await port.claim(accountId, port.now(), lease);
  if (!entry || entry.lease?.token !== lease) return 'idle';
  if ((await port.activeAccount()) !== accountId) {
    await port.settle(accountId, lease, 'authentication', port.now());
    return 'blocked';
  }
  let delivery: JourneyDelivery;
  try {
    delivery = await port.send(accountId, entry.command);
  } catch {
    delivery = { outcome: 'transient' };
  }
  if (delivery.outcome === 'success')
    return (await port.acknowledge(accountId, lease, delivery.journey, port.now()))
      ? 'acknowledged'
      : 'stale';
  await port.settle(accountId, lease, delivery.outcome, port.now());
  return delivery.outcome === 'transient' ? 'deferred' : 'blocked';
}
