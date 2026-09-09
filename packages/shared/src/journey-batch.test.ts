import { expect, it, vi } from 'vitest';
import { dispatchJourneyBatch } from './journey-batch';
import type { JourneyDispatchPort } from './journey-dispatch';
const account = '00000000-0000-4000-8000-000000000001';
it('does not claim or send after cancellation before a batch', async () => {
  const transport = port();
  transport.claim = vi.fn(transport.claim);
  const controller = new AbortController();
  controller.abort();
  expect(await dispatchJourneyBatch(transport, account, { signal: controller.signal })).toEqual({
    acknowledged: 0,
    reason: 'cancelled',
  });
  expect(transport.claim).not.toHaveBeenCalled();
  expect(transport.send).not.toHaveBeenCalled();
});
it('re-verifies ownership between commands and stops after an account switch', async () => {
  const transport = port();
  let owner: string | null = account;
  transport.activeAccount = async () => owner;
  transport.acknowledge = vi.fn(async () => {
    owner = null;
    return true;
  });
  expect(await dispatchJourneyBatch(transport, account)).toEqual({
    acknowledged: 1,
    reason: 'idle',
  });
  expect(transport.send).toHaveBeenCalledTimes(1);
});
function port(): JourneyDispatchPort {
  return {
    activeAccount: async () => account,
    claim: async (_, now, token) => ({
      command: { action: 'complete', journeyId: account },
      attempts: 1,
      nextAttemptAt: now,
      blocked: null,
      lease: { token, expiresAt: now + 30000 },
    }),
    send: vi.fn(async () => ({ outcome: 'success' as const, journey: {} })),
    acknowledge: vi.fn(async () => true),
    settle: vi.fn(async () => {}),
    now: () => 1,
    lease: () => crypto.randomUUID(),
  };
}
it('caps successful drains and rejects unbounded attempts', async () => {
  const transport = port();
  expect(await dispatchJourneyBatch(transport, account, { maximum: 2 })).toEqual({
    acknowledged: 2,
    reason: 'budget',
  });
  expect(transport.send).toHaveBeenCalledTimes(2);
  await expect(dispatchJourneyBatch(transport, account, { maximum: 11 })).rejects.toThrow();
  expect(transport.send).toHaveBeenCalledTimes(2);
});
it('stops after a deferred action rather than immediately retrying it', async () => {
  const transport = port();
  transport.send = vi.fn(async () => ({ outcome: 'transient' as const }));
  expect(await dispatchJourneyBatch(transport, account)).toEqual({
    acknowledged: 0,
    reason: 'deferred',
  });
  expect(transport.send).toHaveBeenCalledTimes(1);
});
it('acknowledges an in-flight success before respecting cancellation', async () => {
  const transport = port();
  const controller = new AbortController();
  transport.send = vi.fn(async () => {
    controller.abort();
    return { outcome: 'success' as const, journey: {} };
  });
  expect(await dispatchJourneyBatch(transport, account, { signal: controller.signal })).toEqual({
    acknowledged: 1,
    reason: 'cancelled',
  });
  expect(transport.acknowledge).toHaveBeenCalledTimes(1);
  expect(transport.send).toHaveBeenCalledTimes(1);
});
