import { expect, it, vi } from 'vitest';
import { NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import { createNativeJournalController } from '../apps/mobile/src/features/journey/native-journal-controller';
import type { NativeHistoryPage } from '../apps/mobile/src/features/journey/native-history';
import type { TripJournal } from '@routiqo/shared';

const id = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const stamp = '2026-09-12T12:00:00.000000Z';
const trip = (n: number): TripJournal => ({
  journey: { id: id(n), kind: 'trip', status: 'completed', startedAt: stamp, completedAt: stamp },
  annotation: { title: '', notes: '', version: 0, updatedAt: null },
});
const page: NativeHistoryPage = {
  journeys: [
    trip(1).journey,
    { ...trip(2).journey, kind: 'commute' },
    { ...trip(3).journey, status: 'active', completedAt: null },
  ],
  next: null,
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

it('reads only explicitly selected completed trips and retries only on request', async () => {
  let online = false;
  const read = vi.fn(async () => trip(1));
  const journal = createNativeJournalController(read, () => online, vi.fn());
  expect(read).not.toHaveBeenCalled();
  await journal.open(id(2), page);
  await journal.open(id(3), page);
  await journal.open(id(4), page);
  expect(journal.state().selectedId).toBeNull();
  await journal.open(id(1), page);
  expect(journal.state()).toMatchObject({ selectedId: id(1), journal: null, busy: false });
  expect(read).not.toHaveBeenCalled();
  online = true;
  expect(read).not.toHaveBeenCalled();
  await journal.retry();
  expect(journal.state().journal).toEqual(trip(1));
  online = false;
  journal.offline();
  expect(journal.state().journal).toEqual(trip(1));
  await journal.retry();
  expect(read).toHaveBeenCalledTimes(1);
  journal.close();
  expect(journal.state().journal).toBeNull();
});

it('fences selection, close, offline and dispose against late results', async () => {
  const a = deferred<TripJournal>();
  const b = deferred<TripJournal>();
  const read = vi.fn((key: string) => (key === id(1) ? a.promise : b.promise));
  const publish = vi.fn();
  const controller = createNativeJournalController(read, () => true, publish);
  const two: NativeHistoryPage = { journeys: [trip(1).journey, trip(2).journey], next: null };
  const first = controller.open(id(1), two);
  const second = controller.open(id(2), two);
  a.resolve(trip(1));
  await first;
  expect(controller.state().journal).toBeNull();
  b.resolve(trip(2));
  await second;
  expect(controller.state().journal).toEqual(trip(2));
  const pending = deferred<TripJournal>();
  const next = createNativeJournalController(
    () => pending.promise,
    () => true,
    publish,
  );
  const operation = next.open(id(1), page);
  next.offline();
  const count = publish.mock.calls.length;
  pending.resolve(trip(1));
  await operation;
  expect(publish).toHaveBeenCalledTimes(count);
  next.dispose();
  controller.close();
  expect(controller.state().journal).toBeNull();

  const closing = deferred<TripJournal>();
  const closeController = createNativeJournalController(
    () => closing.promise,
    () => true,
    vi.fn(),
  );
  const closingRead = closeController.open(id(1), page);
  closeController.close();
  closing.resolve(trip(1));
  await closingRead;
  expect(closeController.state()).toMatchObject({ selectedId: null, journal: null });

  const rejecting = deferred<TripJournal>();
  const disposePublish = vi.fn();
  const disposed = createNativeJournalController(
    () => rejecting.promise,
    () => true,
    disposePublish,
  );
  const disposedRead = disposed.open(id(1), page);
  disposed.dispose();
  const before = disposePublish.mock.calls.length;
  rejecting.reject(new NativeHttpStatus(401));
  await disposedRead;
  expect(disposePublish).toHaveBeenCalledTimes(before);
});

it('distinguishes absence, session denial and recoverable errors', async () => {
  for (const [failure, expected] of [
    [new NativeHttpStatus(404), 'missing'],
    [new NativeHttpStatus(409), 'missing'],
    [new NativeHttpStatus(401), 'session'],
    [new NativeHttpStatus(403), 'session'],
    [new Error('network'), 'unavailable'],
  ] as const) {
    const controller = createNativeJournalController(
      async () => {
        throw failure;
      },
      () => true,
      vi.fn(),
    );
    await controller.open(id(1), page);
    expect(controller.state()).toMatchObject({ failure: expected, journal: null, busy: false });
  }
  const mismatch = createNativeJournalController(
    async () => trip(2),
    () => true,
    vi.fn(),
  );
  await mismatch.open(id(1), page);
  expect(mismatch.state()).toMatchObject({ journal: null, failure: 'unavailable' });

  let denied = false;
  const session = createNativeJournalController(
    async () => {
      if (denied) throw new NativeHttpStatus(401);
      return trip(1);
    },
    () => true,
    vi.fn(),
  );
  await session.open(id(1), page);
  expect(session.state().journal).toEqual(trip(1));
  denied = true;
  await session.retry();
  expect(session.state()).toMatchObject({ journal: null, failure: 'session' });

  for (const [failure, retained] of [
    [new NativeHttpStatus(404), false],
    [new Error('network'), true],
  ] as const) {
    let fail = false;
    const reader = createNativeJournalController(
      async () => {
        if (fail) throw failure;
        return trip(1);
      },
      () => true,
      vi.fn(),
    );
    await reader.open(id(1), page);
    fail = true;
    await reader.retry();
    expect(reader.state().journal === null).toBe(!retained);
  }
});
