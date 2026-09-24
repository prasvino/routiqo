import { afterEach, expect, it, vi } from 'vitest';
import type { createNativeAccount } from '../apps/mobile/src/auth/native-account';
import {
  readNativeJournal,
  readNativeTripJournal,
} from '../apps/mobile/src/features/journey/native-journal';
import { createNativeTransport, NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const path = `/api/v1/native/journeys/${journeyId}/journal`;
const credential = 'A'.repeat(43);
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

afterEach(() => {
  vi.useRealTimers();
});

it('validates the complete private shape and exact requested journey', () => {
  expect(readNativeTripJournal(journal, journeyId).journey.id).toBe(journeyId);
  for (const invalid of [
    { ...journal, accountId },
    { ...journal, journey: { ...journal.journey, ownerId: accountId } },
    { ...journal, annotation: { ...journal.annotation, secret: 'extra' } },
    { ...journal, annotation: { ...journal.annotation, version: 1 } },
    { ...journal, journey: { ...journal.journey, kind: 'commute' } },
  ])
    expect(() => readNativeTripJournal(invalid, journeyId)).toThrow();
  expect(() =>
    readNativeTripJournal(
      { ...journal, journey: { ...journal.journey, id: accountId } },
      journeyId,
    ),
  ).toThrow();
});

it('uses the exact GET and rejects method, path, status and oversized replies', async () => {
  const request = vi.fn(async () => ({ status: 200, body: JSON.stringify(journal) }));
  const transport = createNativeTransport({ request }, 'https://staging.routiqo.example');
  const result = await transport.request(path, 'GET', { credential, accountId });
  expect(readNativeTripJournal(result, journeyId).journey.id).toBe(journeyId);
  expect(request).toHaveBeenCalledWith(
    'https://staging.routiqo.example',
    path,
    'GET',
    credential,
    accountId,
    null,
  );
  await expect(
    transport.request(path, 'POST', { credential, accountId, body: {} }),
  ).rejects.toThrow();
  await expect(
    transport.request(path + '?x=1', 'GET', { credential, accountId }),
  ).rejects.toThrow();
  for (const suffix of ['\n', '\r', '\u2028', '\u2029'])
    await expect(
      transport.request(path + suffix, 'GET', { credential, accountId }),
    ).rejects.toThrow();
  await expect(transport.request(path, 'GET', { credential })).rejects.toThrow();
  expect(request).toHaveBeenCalledTimes(1);
  const unexpected = createNativeTransport(
    { request: async () => ({ status: 204, body: '' }) },
    'https://staging.routiqo.example',
  );
  await expect(unexpected.request(path, 'GET', { credential, accountId })).rejects.toThrow();
  const denied = createNativeTransport(
    { request: async () => ({ status: 404, body: 'private' }) },
    'https://staging.routiqo.example',
  );
  await expect(denied.request(path, 'GET', { credential, accountId })).rejects.toEqual(
    new NativeHttpStatus(404),
  );
  const oversized = createNativeTransport(
    { request: async () => ({ status: 200, body: 'x'.repeat(32769) }) },
    'https://staging.routiqo.example',
  );
  await expect(oversized.request(path, 'GET', { credential, accountId })).rejects.toThrow(
    'Native server response is invalid.',
  );
});

it('settles cancelled and timed-out reads without accepting late native results', async () => {
  const pending: { resolve?: (value: unknown) => void } = {};
  const verifiedRequest = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        pending.resolve = resolve;
      }),
  );
  let revision = 0;
  let activeAccount: string | null = accountId;
  const identity = {
    verifiedRequest,
    revision: () => revision,
    activeAccount: () => activeAccount,
  } as unknown as ReturnType<typeof createNativeAccount>;
  const controller = new AbortController();
  const cancelled = readNativeJournal(identity, accountId, journeyId, controller.signal);
  controller.abort();
  await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
  pending.resolve?.(journal);
  expect(verifiedRequest).toHaveBeenCalledWith(path, 'GET', {
    accountId,
    signal: expect.any(AbortSignal),
  });

  vi.useFakeTimers();
  const stalled = readNativeJournal(identity, accountId, journeyId);
  const assertion = expect(stalled).rejects.toThrow('timed out');
  await vi.advanceTimersByTimeAsync(12_000);
  await assertion;
  pending.resolve?.(journal);
  const alreadyCancelled = new AbortController();
  alreadyCancelled.abort();
  await expect(
    readNativeJournal(identity, accountId, journeyId, alreadyCancelled.signal),
  ).rejects.toMatchObject({ name: 'AbortError' });
  expect(verifiedRequest).toHaveBeenCalledTimes(2);
  vi.useRealTimers();
  const stale = readNativeJournal(identity, accountId, journeyId);
  revision++;
  pending.resolve?.(journal);
  await expect(stale).rejects.toThrow('account changed');
  activeAccount = null;
  await expect(readNativeJournal(identity, accountId, journeyId)).rejects.toThrow(
    'account changed',
  );
  await expect(
    readNativeJournal(identity, '00000000-0000-0000-0000-000000000000', journeyId),
  ).rejects.toThrow('Invalid');
});
