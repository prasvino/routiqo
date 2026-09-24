import { afterEach, expect, it, vi } from 'vitest';
import type { createNativeAccount } from '../apps/mobile/src/auth/native-account';
import { readNativeJournal } from '../apps/mobile/src/features/journey/native-journal';
import { readNativeConsent } from '../apps/mobile/src/features/live/native-consent';
import { calculateNativeRoute } from '../apps/mobile/src/features/journey/native-routing';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
type Account = ReturnType<typeof createNativeAccount>;
const journal = {
  journey: {
    id: journeyId,
    kind: 'trip',
    status: 'completed',
    startedAt: '2026-09-12T12:00:00Z',
    completedAt: '2026-09-12T13:00:00Z',
  },
  annotation: { title: '', notes: '', version: 0, updatedAt: null },
};
const consent = { journeyId, generation: '0', sharing: false, journeyActive: true };
const route = { provider: 'mapbox', calculatedAt: '2026-09-09T12:00:00Z', routes: [] };
const routeInput = {
  mode: 'driving' as const,
  origin: [80, 13] as [number, number],
  destination: [79, 12] as [number, number],
};
const operations = [
  {
    call: (account: Account, signal: AbortSignal) =>
      readNativeJournal(account, accountId, journeyId, signal),
    result: journal,
  },
  {
    call: (account: Account, signal: AbortSignal) =>
      readNativeConsent(account, accountId, journeyId, signal),
    result: consent,
  },
  {
    call: (account: Account, signal: AbortSignal) =>
      calculateNativeRoute(account, accountId, routeInput, signal),
    result: route,
  },
];
afterEach(() => vi.unstubAllGlobals());

it('uses a portable AbortError before dispatch when Hermes has no DOMException', async () => {
  vi.stubGlobal('DOMException', undefined);
  const verifiedRequest = vi.fn(async () => undefined);
  const account = {
    verifiedRequest,
    activeAccount: () => accountId,
    revision: () => 1,
  } as unknown as Account;
  for (const operation of operations) {
    const controller = new AbortController();
    controller.abort();
    await expect(operation.call(account, controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
  }
  expect(verifiedRequest).not.toHaveBeenCalled();
});

it('rejects in-flight work and ignores late bridge results without DOMException', async () => {
  vi.stubGlobal('DOMException', undefined);
  for (const operation of operations) {
    let finish!: (value: unknown) => void;
    const verifiedRequest = vi.fn(
      () =>
        new Promise<unknown>((resolve) => {
          finish = resolve;
        }),
    );
    const account = {
      verifiedRequest,
      activeAccount: () => accountId,
      revision: () => 1,
    } as unknown as Account;
    const controller = new AbortController();
    const pending = operation.call(account, controller.signal);
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    finish(operation.result);
    await Promise.resolve();
    expect(verifiedRequest).toHaveBeenCalledTimes(1);
  }
});
