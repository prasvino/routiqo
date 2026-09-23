import { expect, it, vi } from 'vitest';
import { NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import { createNativeHistoryController } from '../apps/mobile/src/features/journey/native-history-controller';
import {
  readNativeJourneyPage,
  readNativeHistoryPage,
  type NativeHistoryCursor,
  type NativeHistoryPage,
} from '../apps/mobile/src/features/journey/native-history';
import type { createNativeAccount } from '../apps/mobile/src/auth/native-account';

const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const stamp = '2026-09-12T12:00:00.000000Z';
const journey = (n: number, startedAt = stamp) => ({
  id: id(n),
  kind: 'trip',
  status: 'completed',
  startedAt,
  completedAt: startedAt,
});
const cursor = (n: number): NativeHistoryCursor => ({ startedAt: stamp, id: id(n) });
const page = (
  start: number,
  size: number,
  next: NativeHistoryCursor | null,
): NativeHistoryPage => ({
  journeys: Array.from({ length: size }, (_, i) => journey(start - i)),
  next,
});

it('validates full pages, descending tied keys and server cursor normalization', () => {
  const first = page(30, 20, cursor(11));
  first.journeys[19]!.startedAt = '2026-09-12T12:00:00Z';
  first.journeys[19]!.completedAt = '2026-09-12T12:00:00Z';
  const result = readNativeHistoryPage(
    { ...first, next: { startedAt: '2026-09-12T12:00:00Z', id: id(11) } },
    null,
  );
  expect(result.next).toEqual(cursor(11));
  for (const fractional of ['2026-09-12T12:00:00.123Z', '2026-09-12T12:00:00.123456Z']) {
    const single = readNativeHistoryPage({ journeys: [journey(10, fractional)], next: null }, null);
    expect(single.journeys[0]?.startedAt).toBe(
      fractional === '2026-09-12T12:00:00.123Z' ? '2026-09-12T12:00:00.123000Z' : fractional,
    );
  }
  expect(
    readNativeHistoryPage({ journeys: [journey(10)], next: null }, cursor(11)).journeys,
  ).toHaveLength(1);
  for (const malformed of [
    { journeys: [journey(10), journey(10)], next: null },
    { journeys: [journey(10), journey(11)], next: null },
    { journeys: [journey(12)], next: null },
    { journeys: [journey(10)], next: cursor(10) },
    { journeys: [], next: cursor(10) },
    { journeys: [journey(10)], next: null, owner: id(99) },
    { journeys: [{ ...journey(10), owner: id(99) }], next: null },
    { journeys: Array.from({ length: 21 }, (_, i) => journey(i + 1)), next: null },
    { journeys: [journey(10)], next: { startedAt: stamp, id: [id(10)] } },
  ])
    expect(() => readNativeHistoryPage(malformed, cursor(11))).toThrow();
  expect(() =>
    readNativeHistoryPage({ journeys: [journey(10)], next: null }, {
      startedAt: stamp,
      id: [id(11)],
    } as unknown as NativeHistoryCursor),
  ).toThrow();
});

it('posts exact latest and older bodies under the account identity', async () => {
  const verifiedRequest = vi.fn(async () => ({ journeys: [], next: null }));
  const identity = { verifiedRequest } as unknown as ReturnType<typeof createNativeAccount>;
  await readNativeJourneyPage(identity, id(1), null);
  await readNativeJourneyPage(identity, id(1), cursor(11));
  expect(verifiedRequest.mock.calls).toEqual([
    ['/api/v1/native/journeys/history', 'POST', { accountId: id(1), body: {} }],
    ['/api/v1/native/journeys/history', 'POST', { accountId: id(1), body: { before: cursor(11) } }],
  ]);
  await expect(
    readNativeJourneyPage(identity, id(1), {
      startedAt: stamp,
      id: [id(11)],
    } as unknown as NativeHistoryCursor),
  ).rejects.toThrow();
  expect(verifiedRequest).toHaveBeenCalledTimes(2);
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

it('loads only by explicit controls, retries exact cursor, preserves offline page and clears after auth failure', async () => {
  const reads: Array<NativeHistoryCursor | null> = [];
  let online = true;
  let fail = false;
  const first = page(30, 20, cursor(11));
  const older = page(10, 1, null);
  const read = vi.fn(async (before: NativeHistoryCursor | null) => {
    reads.push(before);
    if (fail) throw new Error('connection');
    return before ? older : first;
  });
  const publish = vi.fn();
  const controller = createNativeHistoryController(read, () => online, publish);
  expect(read).not.toHaveBeenCalled();
  await controller.latest();
  expect(controller.state().page).toBe(first);
  expect(controller.state().pageLabel).toBe('Latest journeys');
  online = false;
  await controller.earlier();
  expect(reads).toEqual([null]);
  expect(controller.state().page).toBe(first);
  online = true;
  fail = true;
  await controller.earlier();
  expect(controller.state()).toMatchObject({ page: first, error: true });
  fail = false;
  await controller.retry();
  expect(reads.at(-1)).toEqual(cursor(11));
  expect(controller.state()).toMatchObject({ page: older, error: false });
  expect(controller.state().pageLabel).toBe('Earlier journeys');
  await controller.latest();
  expect(reads.at(-1)).toBeNull();
  const denied = createNativeHistoryController(
    async () => {
      throw new NativeHttpStatus(401);
    },
    () => true,
    vi.fn(),
  );
  await denied.latest();
  expect(denied.state()).toMatchObject({ page: null, authenticationRequired: true });
  await denied.latest();
  expect(denied.state().authenticationRequired).toBe(true);
});

it('fences late success and rejection after session-key unmount', async () => {
  const pending = deferred<NativeHistoryPage>();
  const publish = vi.fn();
  const old = createNativeHistoryController(
    () => pending.promise,
    () => true,
    publish,
  );
  const operation = old.latest();
  old.dispose();
  const count = publish.mock.calls.length;
  pending.resolve(page(3, 1, null));
  await operation;
  expect(publish).toHaveBeenCalledTimes(count);
  const replacement = createNativeHistoryController(
    async () => page(2, 1, null),
    () => true,
    vi.fn(),
  );
  await replacement.latest();
  expect(replacement.state().page?.journeys[0]?.id).toBe(id(2));
  const lateError = deferred<NativeHistoryPage>();
  const oldError = createNativeHistoryController(
    () => lateError.promise,
    () => true,
    publish,
  );
  const rejected = oldError.latest();
  oldError.dispose();
  lateError.reject(new NativeHttpStatus(401));
  await rejected;
  expect(publish).toHaveBeenCalledTimes(count + 1);
});
