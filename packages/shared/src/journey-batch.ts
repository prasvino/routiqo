import type { JourneyDispatchPort } from './journey-dispatch';
import { dispatchJourneyOnce } from './journey-dispatch';

/** A bounded foreground drain. Cancellation stops future sends, never acknowledgement in flight. */
export async function dispatchJourneyBatch(
  port: JourneyDispatchPort,
  accountId: string,
  options: { maximum?: number; signal?: AbortSignal } = {},
): Promise<{
  acknowledged: number;
  reason: 'idle' | 'stale' | 'deferred' | 'blocked' | 'cancelled' | 'budget';
}> {
  const maximum = options.maximum ?? 5;
  if (!Number.isInteger(maximum) || maximum < 1 || maximum > 10)
    throw new Error('Journey dispatch batch must contain between one and ten attempts.');
  let acknowledged = 0;
  for (let attempt = 0; attempt < maximum; attempt++) {
    if (options.signal?.aborted) return { acknowledged, reason: 'cancelled' };
    const result = await dispatchJourneyOnce(port, accountId);
    if (result !== 'acknowledged') return { acknowledged, reason: result };
    acknowledged++;
  }
  return { acknowledged, reason: 'budget' };
}
