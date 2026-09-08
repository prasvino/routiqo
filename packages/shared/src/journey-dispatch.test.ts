import { describe, expect, it, vi } from 'vitest';
import { dispatchJourneyOnce, type JourneyDispatchPort } from './journey-dispatch';
const account = '00000000-0000-4000-8000-000000000001';
const lease = '00000000-0000-4000-8000-000000000002';
const command = {
  journeyId: '00000000-0000-4000-8000-000000000003',
  action: 'start' as const,
  kind: 'trip' as const,
};
function port() {
  return {
    activeAccount: vi.fn<JourneyDispatchPort['activeAccount']>().mockResolvedValue(account),
    claim: vi.fn<JourneyDispatchPort['claim']>().mockResolvedValue({
      command,
      attempts: 1,
      nextAttemptAt: 0,
      blocked: null,
      lease: { token: lease, expiresAt: 30000 },
    }),
    send: vi
      .fn<JourneyDispatchPort['send']>()
      .mockResolvedValue({ outcome: 'success', journey: { id: command.journeyId } }),
    acknowledge: vi.fn<JourneyDispatchPort['acknowledge']>().mockResolvedValue(true),
    settle: vi.fn<JourneyDispatchPort['settle']>().mockResolvedValue(),
    now: () => 1,
    lease: () => lease,
  };
}
describe('one durable journey dispatch', () => {
  it('does not claim or send work for another active account', async () => {
    const storage = port();
    storage.activeAccount.mockResolvedValue(null);
    expect(await dispatchJourneyOnce(storage, account)).toBe('idle');
    expect(storage.claim).not.toHaveBeenCalled();
    expect(storage.send).not.toHaveBeenCalled();
  });
  it('blocks a claimed command if the account changes before sending', async () => {
    const storage = port();
    storage.activeAccount.mockResolvedValueOnce(account).mockResolvedValueOnce(null);
    expect(await dispatchJourneyOnce(storage, account)).toBe('blocked');
    expect(storage.send).not.toHaveBeenCalled();
    expect(storage.settle).toHaveBeenCalledWith(account, lease, 'authentication', 1);
  });
  it('defers network failures and blocks conflicts without acknowledging', async () => {
    const storage = port();
    storage.send.mockRejectedValueOnce(new Error('network'));
    expect(await dispatchJourneyOnce(storage, account)).toBe('deferred');
    expect(storage.settle).toHaveBeenCalledWith(account, lease, 'transient', 1);
    storage.send.mockResolvedValueOnce({ outcome: 'conflict' });
    expect(await dispatchJourneyOnce(storage, account)).toBe('blocked');
    expect(storage.acknowledge).not.toHaveBeenCalled();
  });
  it('uses atomic acknowledgement and propagates persistence failure', async () => {
    const storage = port();
    expect(await dispatchJourneyOnce(storage, account)).toBe('acknowledged');
    expect(storage.acknowledge).toHaveBeenCalledWith(account, lease, { id: command.journeyId }, 1);
    storage.acknowledge.mockRejectedValueOnce(new Error('disk full'));
    await expect(dispatchJourneyOnce(storage, account)).rejects.toThrow('disk full');
    expect(storage.settle).not.toHaveBeenCalled();
  });
  it('does not send if another worker holds the lease and ignores stale success', async () => {
    const storage = port();
    storage.claim.mockResolvedValueOnce(null);
    expect(await dispatchJourneyOnce(storage, account)).toBe('idle');
    expect(storage.send).not.toHaveBeenCalled();
    storage.acknowledge.mockResolvedValueOnce(false);
    expect(await dispatchJourneyOnce(storage, account)).toBe('stale');
  });
});
