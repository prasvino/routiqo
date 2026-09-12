import { IDBFactory, IDBObjectStore } from 'fake-indexeddb';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  acknowledgeBrowserJournalDraft,
  cacheBrowserTripJournal,
  readBrowserJournal,
  listBrowserJournals,
  discardBrowserJournalDraft,
  retireBrowserJournalPartition,
  saveBrowserJournalDraft,
  type BrowserJournalDraft,
} from '../apps/web/lib/journal-storage';

const account = '00000000-0000-4000-8000-000000000001';
const otherAccount = '00000000-0000-4000-8000-000000000002';
const journeyId = '00000000-0000-4000-8000-000000000003';
const firstMutation = '00000000-0000-4000-8000-000000000004';
const secondMutation = '00000000-0000-4000-8000-000000000005';

it('discards only the exact reviewed local draft while retaining its confirmed account journal', async () => {
  const reviewed = await cacheBrowserTripJournal(account, journal());
  await saveBrowserJournalDraft(account, draft(), null);
  await expect(
    discardBrowserJournalDraft(otherAccount, journeyId, firstMutation, reviewed),
  ).rejects.toThrow();
  expect(await discardBrowserJournalDraft(account, journeyId, firstMutation, reviewed)).toEqual(
    reviewed,
  );
  expect(await readBrowserJournal(account, journeyId)).toEqual({ journal: reviewed, draft: null });
  await expect(
    discardBrowserJournalDraft(account, journeyId, firstMutation, reviewed),
  ).rejects.toThrow();
});

it('refuses discard when another tab changed the draft or the reviewed account version', async () => {
  const reviewed = await cacheBrowserTripJournal(account, journal());
  await saveBrowserJournalDraft(account, draft(), null);
  await saveBrowserJournalDraft(account, draft(journeyId, secondMutation), firstMutation);
  await expect(
    discardBrowserJournalDraft(account, journeyId, firstMutation, reviewed),
  ).rejects.toThrow('another tab');
  await cacheBrowserTripJournal(account, journal(journeyId, 1, 'New account text'));
  await expect(
    discardBrowserJournalDraft(account, journeyId, secondMutation, reviewed),
  ).rejects.toThrow('version changed');
  expect((await readBrowserJournal(account, journeyId)).draft?.mutationId).toBe(secondMutation);
});

it('preserves drafts on failed discard transactions and rejects late discard after deletion', async () => {
  const reviewed = await cacheBrowserTripJournal(account, journal());
  await saveBrowserJournalDraft(account, draft(), null);
  const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(() => {
    throw new DOMException('Synthetic failure', 'QuotaExceededError');
  });
  await expect(
    discardBrowserJournalDraft(account, journeyId, firstMutation, reviewed),
  ).rejects.toThrow();
  put.mockRestore();
  expect((await readBrowserJournal(account, journeyId)).draft).toEqual(draft());
  await retireBrowserJournalPartition(account);
  await expect(
    discardBrowserJournalDraft(account, journeyId, firstMutation, reviewed),
  ).rejects.toThrow('deleted');
});

it('lists retained drafts independently of journey history and rejects retired accounts', async () => {
  const confirmed = await cacheBrowserTripJournal(account, journal());
  await saveBrowserJournalDraft(account, draft(), null);
  expect(await listBrowserJournals(account)).toEqual([{ journal: confirmed, draft: draft() }]);
  expect(await listBrowserJournals(otherAccount)).toEqual([]);
  await retireBrowserJournalPartition(account);
  await expect(listBrowserJournals(account)).rejects.toThrow();
});

function id(value: number): string {
  return `10000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`;
}

function journal(idValue = journeyId, version = 0, title = '', notes = '', day = 1) {
  const date = String(day).padStart(2, '0');
  return {
    journey: {
      id: idValue,
      kind: 'trip' as const,
      status: 'completed' as const,
      startedAt: `2026-08-${date}T12:00:00Z`,
      completedAt: `2026-08-${date}T13:00:00Z`,
    },
    annotation: {
      title,
      notes,
      version,
      updatedAt: version === 0 ? null : `2026-08-${date}T14:00:00Z`,
    },
  };
}

function draft(
  idValue = journeyId,
  mutationId = firstMutation,
  expectedVersion = 0,
  title = 'Synthetic journal title',
  notes = 'Synthetic journal notes',
): BrowserJournalDraft {
  return { journeyId: idValue, title, notes, expectedVersion, mutationId };
}

async function putRaw(key: string, value: unknown): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.open('routiqo-journal-v1', 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const db = request.result;
      const tx = db.transaction('accounts', 'readwrite');
      tx.objectStore('accounts').put(value, key);
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onabort = () => {
        db.close();
        reject(tx.error);
      };
    };
  });
}

beforeEach(() => vi.stubGlobal('indexedDB', new IDBFactory()));
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('browser IndexedDB journal partitions', () => {
  it('persists drafts across connections and isolates accounts', async () => {
    await cacheBrowserTripJournal(account, journal());
    await cacheBrowserTripJournal(otherAccount, journal());
    await saveBrowserJournalDraft(account, draft(), null);

    expect(await readBrowserJournal(account, journeyId)).toEqual({
      draft: draft(),
      journal: {
        ...journal(),
        journey: {
          ...journal().journey,
          startedAt: '2026-08-01T12:00:00.000000Z',
          completedAt: '2026-08-01T13:00:00.000000Z',
        },
      },
    });
    expect((await readBrowserJournal(otherAccount, journeyId)).draft).toBeNull();
    await expect(saveBrowserJournalDraft(account, draft(id(90)), null)).rejects.toThrow('Restore');
  });

  it('allows only one competing cross-tab draft save', async () => {
    await cacheBrowserTripJournal(account, journal());
    const competing = [
      draft(journeyId, firstMutation, 0, 'Synthetic title A'),
      draft(journeyId, secondMutation, 0, 'Synthetic title B'),
    ];
    const results = await Promise.allSettled(
      competing.map((value) => saveBrowserJournalDraft(account, value, null)),
    );

    expect(results.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    expect(results.filter((result) => result.status === 'rejected')).toHaveLength(1);
    expect(competing).toContainEqual((await readBrowserJournal(account, journeyId)).draft);
  });

  it('allows an exact saved-draft retry but rejects mutation reuse for changed text', async () => {
    await cacheBrowserTripJournal(account, journal());
    const saved = await saveBrowserJournalDraft(account, draft(), null);

    expect(await saveBrowserJournalDraft(account, draft(), firstMutation)).toEqual(saved);
    await expect(
      saveBrowserJournalDraft(
        account,
        draft(journeyId, firstMutation, 0, 'Synthetic changed title'),
        firstMutation,
      ),
    ).rejects.toThrow('cannot be reused');
    expect((await readBrowserJournal(account, journeyId)).draft).toEqual(saved);
  });

  it('rejects an unconfirmed draft base while allowing a stale confirmed base', async () => {
    await cacheBrowserTripJournal(account, journal());
    await expect(
      saveBrowserJournalDraft(account, draft(journeyId, firstMutation, 1), null),
    ).rejects.toThrow('unconfirmed');
    expect((await readBrowserJournal(account, journeyId)).draft).toBeNull();

    await cacheBrowserTripJournal(
      account,
      journal(journeyId, 2, 'Synthetic server title', 'Synthetic server notes'),
    );
    const stale = draft(journeyId, firstMutation, 1);
    expect(await saveBrowserJournalDraft(account, stale, null)).toEqual(stale);
  });

  it('keeps cache versions monotonic and does not rebase an unsent draft', async () => {
    await cacheBrowserTripJournal(
      account,
      journal(journeyId, 1, 'Synthetic server title 1', 'Synthetic server notes 1'),
    );
    const local = draft(journeyId, firstMutation, 1);
    await saveBrowserJournalDraft(account, local, null);
    await cacheBrowserTripJournal(
      account,
      journal(journeyId, 2, 'Synthetic server title 2', 'Synthetic server notes 2'),
    );

    const saved = await readBrowserJournal(account, journeyId);
    expect(saved.draft).toEqual(local);
    expect(saved.journal?.annotation.version).toBe(2);
    await expect(
      cacheBrowserTripJournal(
        account,
        journal(journeyId, 1, 'Synthetic server title 1', 'Synthetic server notes 1'),
      ),
    ).rejects.toThrow('backwards');
    await expect(
      cacheBrowserTripJournal(
        account,
        journal(journeyId, 2, 'Synthetic conflicting title', 'Synthetic server notes 2'),
      ),
    ).rejects.toThrow('conflicts');
    await expect(
      cacheBrowserTripJournal(account, {
        ...journal(journeyId, 3, 'Synthetic server title 3', 'Synthetic server notes 3'),
        journey: {
          ...journal(journeyId, 3).journey,
          completedAt: '2026-08-01T13:01:00Z',
        },
      }),
    ).rejects.toThrow('lifecycle');
    expect(await readBrowserJournal(account, journeyId)).toEqual(saved);
  });

  it('ignores a stale acknowledgement and atomically rolls back a bad current response', async () => {
    await cacheBrowserTripJournal(account, journal());
    const first = draft();
    const second = draft(
      journeyId,
      secondMutation,
      0,
      'Synthetic replacement title',
      'Synthetic replacement notes',
    );
    await saveBrowserJournalDraft(account, first, null);
    await saveBrowserJournalDraft(account, second, firstMutation);

    expect(
      await acknowledgeBrowserJournalDraft(
        account,
        journeyId,
        firstMutation,
        journal(journeyId, 1, first.title, first.notes),
      ),
    ).toBe(false);
    const beforeBadResponse = await readBrowserJournal(account, journeyId);
    await expect(
      acknowledgeBrowserJournalDraft(
        account,
        journeyId,
        secondMutation,
        journal(journeyId, 1, second.title, 'Synthetic mismatched notes'),
      ),
    ).rejects.toThrow('does not match');
    expect(await readBrowserJournal(account, journeyId)).toEqual(beforeBadResponse);

    expect(
      await acknowledgeBrowserJournalDraft(
        account,
        journeyId,
        secondMutation,
        journal(journeyId, 1, second.title, second.notes),
      ),
    ).toBe(true);
    const acknowledged = await readBrowserJournal(account, journeyId);
    expect(acknowledged.draft).toBeNull();
    expect(acknowledged.journal?.annotation).toMatchObject({
      title: second.title,
      notes: second.notes,
      version: 1,
    });
  });

  it('preserves all unsent drafts when the confirmed cache has no safe slot', async () => {
    for (let index = 1; index <= 20; index++) {
      const currentId = id(index);
      await cacheBrowserTripJournal(account, journal(currentId, 0, '', '', index));
      await saveBrowserJournalDraft(account, draft(currentId, id(index + 100)), null);
    }

    await expect(cacheBrowserTripJournal(account, journal(id(21), 0, '', '', 21))).rejects.toThrow(
      'limit',
    );
    for (let index = 1; index <= 20; index++)
      expect((await readBrowserJournal(account, id(index))).draft).not.toBeNull();
    expect(await readBrowserJournal(account, id(21))).toEqual({ draft: null, journal: null });
  });

  it('rolls back a storage failure without losing the current draft', async () => {
    await cacheBrowserTripJournal(account, journal());
    await saveBrowserJournalDraft(account, draft(), null);
    const saved = await readBrowserJournal(account, journeyId);
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(() => {
      throw new DOMException('Synthetic quota failure', 'QuotaExceededError');
    });
    await expect(
      saveBrowserJournalDraft(account, draft(journeyId, secondMutation), firstMutation),
    ).rejects.toThrow();
    put.mockRestore();
    expect(await readBrowserJournal(account, journeyId)).toEqual(saved);
  });

  it('rejects corrupted and oversized records without resetting them', async () => {
    await cacheBrowserTripJournal(account, journal());
    await putRaw(account, {
      version: 1,
      accountId: otherAccount,
      drafts: [],
      journals: [],
    });
    await expect(readBrowserJournal(account, journeyId)).rejects.toThrow('cannot be read');
    await expect(cacheBrowserTripJournal(account, journal())).rejects.toThrow('cannot be read');

    await putRaw(account, {
      version: 1,
      accountId: account,
      drafts: [],
      journals: [],
      corruptPadding: 'x'.repeat(1024 * 1024),
    });
    await expect(readBrowserJournal(account, journeyId)).rejects.toThrow('exceed');
  });

  it('uses a deletion marker that prevents late resurrection and preserves other accounts', async () => {
    await cacheBrowserTripJournal(account, journal());
    await cacheBrowserTripJournal(otherAccount, journal());
    await saveBrowserJournalDraft(account, draft(), null);
    await retireBrowserJournalPartition(account);
    await retireBrowserJournalPartition(account);

    await expect(readBrowserJournal(account, journeyId)).rejects.toThrow('deleted');
    await expect(cacheBrowserTripJournal(account, journal())).rejects.toThrow('deleted');
    await expect(saveBrowserJournalDraft(account, draft(), null)).rejects.toThrow('deleted');
    await expect(
      acknowledgeBrowserJournalDraft(
        account,
        journeyId,
        firstMutation,
        journal(journeyId, 1, draft().title, draft().notes),
      ),
    ).rejects.toThrow('deleted');
    expect((await readBrowserJournal(otherAccount, journeyId)).journal).not.toBeNull();
  });
});
