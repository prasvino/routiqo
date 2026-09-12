import { afterEach, expect, it, vi } from 'vitest';
import { readBrowserTripJournal, saveBrowserTripJournal } from '../apps/web/lib/browser-journals';
import { proxyBrowserJourneys } from '../apps/web/lib/auth-proxy';
const account = '00000000-0000-4000-8000-000000000001';
const id = '00000000-0000-4000-8000-000000000002';
const edit = { title: 'Trip', notes: 'My notes', expectedVersion: 0, mutationId: account };
const result = {
  journey: {
    id,
    kind: 'trip',
    status: 'completed',
    startedAt: '2026-09-12T01:00:00Z',
    completedAt: '2026-09-12T02:00:00Z',
  },
  annotation: {
    title: edit.title,
    notes: edit.notes,
    version: 1,
    updatedAt: '2026-09-12T03:00:00Z',
  },
};
afterEach(() => vi.unstubAllGlobals());
it('reads an owner-bound journal and rejects another journey response', async () => {
  const fetcher = vi.fn(async () => Response.json(result));
  vi.stubGlobal('fetch', fetcher);
  expect((await readBrowserTripJournal(account, id)).annotation.notes).toBe(edit.notes);
  expect(fetcher).toHaveBeenCalledWith(
    `/api/v1/journeys/${id}/journal`,
    expect.objectContaining({ headers: { 'X-Routiqo-Account': account }, cache: 'no-store' }),
  );
  await expect(readBrowserTripJournal(account, account)).rejects.toMatchObject({ status: 503 });
});
it('preserves the supplied mutation ID and rejects mismatched acknowledgements', async () => {
  for (const version of [1, 2]) {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'synthetic-journal-csrf-for-tests' }))
      .mockResolvedValueOnce(
        Response.json({ ...result, annotation: { ...result.annotation, version } }),
      );
    vi.stubGlobal('fetch', fetcher);
    if (version === 1)
      expect((await saveBrowserTripJournal(account, id, edit)).annotation.version).toBe(1);
    else
      await expect(saveBrowserTripJournal(account, id, edit)).rejects.toMatchObject({
        status: 503,
      });
    expect(fetcher).toHaveBeenLastCalledWith(
      `/api/v1/journeys/${id}/journal`,
      expect.objectContaining({ method: 'POST', body: JSON.stringify(edit) }),
    );
  }
});
it('preserves conflict/auth outcomes and bounds oversized responses', async () => {
  for (const status of [401, 404, 409, 429]) {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status })),
    );
    await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status });
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('x'.repeat(32769))),
  );
  await expect(readBrowserTripJournal(account, id)).rejects.toMatchObject({ status: 503 });
});
it('allows only GET and POST on the fixed journal suffix without query parameters', async () => {
  const config = {
    clientId: 'test.apps.googleusercontent.com',
    upstream: 'http://localhost:8080',
    origin: 'http://localhost:3000',
  };
  const fetcher = vi.fn(async () => Response.json(result));
  for (const method of ['GET', 'POST']) {
    const request = new Request(`http://localhost:3000/api/v1/journeys/${id}/journal`, {
      method,
      headers: { Origin: config.origin, 'Content-Type': 'application/json' },
      ...(method === 'POST' ? { body: JSON.stringify(edit) } : {}),
    });
    expect((await proxyBrowserJourneys(request, [id, 'journal'], config, fetcher)).status).toBe(
      200,
    );
  }
  expect(
    (
      await proxyBrowserJourneys(
        new Request(`http://localhost:3000/api/v1/journeys/${id}/journal`, { method: 'DELETE' }),
        [id, 'journal'],
        config,
        fetcher,
      )
    ).status,
  ).toBe(405);
  expect(
    (
      await proxyBrowserJourneys(
        new Request(`http://localhost:3000/api/v1/journeys/${id}/journal?owner=other`),
        [id, 'journal'],
        config,
        fetcher,
      )
    ).status,
  ).toBe(400);
  expect(fetcher).toHaveBeenCalledTimes(2);
});
