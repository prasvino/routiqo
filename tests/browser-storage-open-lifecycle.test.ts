import { IDBFactory } from 'fake-indexeddb';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readBrowserJournal } from '../apps/web/lib/journal-storage';
import { readBrowserJourneyPartition } from '../apps/web/lib/journey-storage';

const account = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';

interface ControlledOpenRequest {
  result: {
    close: ReturnType<typeof vi.fn>;
    objectStoreNames: { contains: (name: string) => boolean };
    createObjectStore: ReturnType<typeof vi.fn>;
    transaction: ReturnType<typeof vi.fn>;
    onversionchange: ((event?: unknown) => void) | null;
  };
  transaction: {
    abort: ReturnType<typeof vi.fn>;
  } | null;
  onupgradeneeded: ((event?: unknown) => void) | null;
  onerror: ((event?: unknown) => void) | null;
  onblocked: ((event?: unknown) => void) | null;
  onsuccess: ((event?: unknown) => void) | null;
}

function createControlledOpenHarness() {
  const closeSpy = vi.fn();
  const dbTransactionSpy = vi.fn();
  const abortSpy = vi.fn();
  const createObjectStoreSpy = vi.fn();

  const mockDb = {
    close: closeSpy,
    objectStoreNames: { contains: () => false },
    createObjectStore: createObjectStoreSpy,
    transaction: dbTransactionSpy,
    onversionchange: null,
  };

  const mockUpgradeTx = {
    abort: abortSpy,
  };

  const request: ControlledOpenRequest = {
    result: mockDb,
    transaction: mockUpgradeTx,
    onupgradeneeded: null,
    onerror: null,
    onblocked: null,
    onsuccess: null,
  };

  const openSpy = vi.fn().mockReturnValue(request);

  vi.stubGlobal('indexedDB', {
    open: openSpy,
  });

  return { request, mockDb, mockUpgradeTx, openSpy };
}

describe('IndexedDB open lifecycle and late handle disposal', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  const targets = [
    {
      name: 'journey storage (readBrowserJourneyPartition)',
      invoke: () => readBrowserJourneyPartition(account),
      unavailableMessage: 'Journey storage is unavailable.',
      assertEmpty: (result: unknown) => {
        expect(result).toEqual({
          outbox: { version: 1, accountId: account, entries: [] },
          snapshots: { version: 1, accountId: account, journeys: [] },
        });
      },
    },
    {
      name: 'journal storage (readBrowserJournal)',
      invoke: () => readBrowserJournal(account, journeyId),
      unavailableMessage: 'Journal storage is unavailable.',
      assertEmpty: (result: unknown) => {
        expect(result).toEqual({
          draft: null,
          journal: null,
        });
      },
    },
  ];

  describe.each(targets)('$name', ({ invoke, unavailableMessage, assertEmpty }) => {
    it('rejects on blocked open, closes late connection handle, aborts late upgrade, and recovers on fresh read', async () => {
      const { request, mockDb, mockUpgradeTx } = createControlledOpenHarness();

      const readPromise = invoke();
      const rejection = expect(readPromise).rejects.toThrow(unavailableMessage);

      // Trigger onblocked
      request.onblocked?.();
      await rejection;

      // Deliver late onsuccess: close occurs and no data transaction begins
      request.onsuccess?.();
      expect(mockDb.close).toHaveBeenCalledTimes(1);
      expect(mockDb.transaction).not.toHaveBeenCalled();

      // Exercise late onupgradeneeded: transaction is aborted
      request.onupgradeneeded?.();
      expect(mockUpgradeTx.abort).toHaveBeenCalledTimes(1);

      // Restore globals and real timers before any real fake-indexeddb operation
      vi.useRealTimers();
      vi.stubGlobal('indexedDB', new IDBFactory());

      // Verify subsequent explicit read succeeds with adapter's empty result
      const freshResult = await invoke();
      assertEmpty(freshResult);
    });

    it('rejects on 5,000ms timeout, closes late connection handle, aborts late upgrade, and recovers on fresh read', async () => {
      vi.useFakeTimers();
      const { request, mockDb, mockUpgradeTx } = createControlledOpenHarness();

      const readPromise = invoke();
      const rejection = expect(readPromise).rejects.toThrow(unavailableMessage);

      // Advance fake timers by 5,000ms
      await vi.advanceTimersByTimeAsync(5000);
      await rejection;

      // Deliver late onsuccess: close occurs and no data transaction begins
      request.onsuccess?.();
      expect(mockDb.close).toHaveBeenCalledTimes(1);
      expect(mockDb.transaction).not.toHaveBeenCalled();

      // Exercise late onupgradeneeded: transaction is aborted
      request.onupgradeneeded?.();
      expect(mockUpgradeTx.abort).toHaveBeenCalledTimes(1);

      // Restore globals and real timers before any real fake-indexeddb operation
      vi.useRealTimers();
      vi.stubGlobal('indexedDB', new IDBFactory());

      // Verify subsequent explicit read succeeds with adapter's empty result
      const freshResult = await invoke();
      assertEmpty(freshResult);
    });
  });
});
