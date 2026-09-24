import { expect, it, vi } from 'vitest';
import { NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import { NativeSessionRequired } from '../apps/mobile/src/auth/native-account';
import {
  createNativeJournalEditorController,
  type NativeJournalEditorPorts,
} from '../apps/mobile/src/features/journey/native-journal-editor-controller';
import type { NativeJournalDraft } from '../apps/mobile/src/storage/journal-storage';
import type { TripJournal } from '@routiqo/shared';

const journeyId = '00000000-0000-4000-8000-000000000011';
const stamp = '2026-09-12T12:00:00.000000Z';
const journal = (version = 0, title = '', notes = ''): TripJournal => ({
  journey: {
    id: journeyId,
    kind: 'trip',
    status: 'completed',
    startedAt: stamp,
    completedAt: stamp,
  },
  annotation: { title, notes, version, updatedAt: version ? stamp : null },
});
const mutation = (n: number) => `00000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
function harness() {
  let online = true;
  let cached = journal();
  let draft: NativeJournalDraft | null = null;
  let nextMutation = 1;
  const events: string[] = [];
  const ports: NativeJournalEditorPorts = {
    online: () => online,
    mutationId: () => mutation(nextMutation++),
    cache: vi.fn(async (value) => {
      if (value.annotation.version < cached.annotation.version)
        throw new Error('version regression');
      cached = value;
      return value;
    }),
    stored: vi.fn(async () => ({ draft, journal: cached })),
    save: vi.fn(async (value, previous) => {
      events.push('save');
      expect(previous).toBe(draft?.mutationId ?? null);
      draft = value;
      return value;
    }),
    write: vi.fn(async (_id, input) => {
      events.push('write');
      return journal(input.expectedVersion + 1, input.title, input.notes);
    }),
    read: vi.fn(async () => journal(2, 'Account', 'Latest')),
    acknowledge: vi.fn(async (_id, id, response) => {
      events.push('ack');
      expect(id).toBe(draft?.mutationId);
      draft = null;
      cached = response;
      return true;
    }),
    discard: vi.fn(async (_id, id, reviewed) => {
      expect(id).toBe(draft?.mutationId);
      draft = null;
      cached = reviewed;
      return reviewed;
    }),
  };
  const publish = vi.fn();
  const controller = createNativeJournalEditorController(ports, publish);
  return {
    controller,
    ports,
    events,
    publish,
    online: (value: boolean) => {
      online = value;
    },
  };
}

it('saves offline drafts durably before any send and retries exact mutation', async () => {
  const { controller, ports, events, online } = harness();
  await controller.open(journal());
  controller.edit({ title: '  Harbour ', notes: 'Line 1\nLine 2  ' });
  online(false);
  await controller.saveDraft();
  const saved = controller.state().draft!;
  expect(saved).toMatchObject({
    title: '  Harbour ',
    notes: 'Line 1\nLine 2  ',
    expectedVersion: 0,
    mutationId: mutation(1),
  });
  await controller.send();
  expect(ports.write).not.toHaveBeenCalled();
  online(true);
  await controller.send();
  expect(events).toEqual(['save', 'write', 'ack']);
  expect(vi.mocked(ports.write).mock.calls[0]?.[1]).toEqual({
    title: saved.title,
    notes: saved.notes,
    expectedVersion: saved.expectedVersion,
    mutationId: saved.mutationId,
  });
  expect(controller.state()).toMatchObject({ draft: null, dirty: false, failure: null });
});

it('adopts a draft committed during same-account renewal without sending it', async () => {
  const { controller, ports } = harness();
  await controller.open(journal());
  controller.edit({ notes: 'Still here' });
  const pending = deferred<NativeJournalDraft>();
  vi.mocked(ports.save).mockReturnValueOnce(pending.promise);
  const saving = controller.saveDraft();
  controller.sessionChanged();
  pending.resolve({
    journeyId,
    title: '',
    notes: 'Still here',
    expectedVersion: 0,
    mutationId: mutation(1),
  });
  await saving;
  expect(controller.state()).toMatchObject({
    open: true,
    notes: 'Still here',
    busy: false,
    dirty: false,
    draft: expect.objectContaining({ mutationId: mutation(1) }),
  });
  expect(ports.write).not.toHaveBeenCalled();
});

it('does not publish a late save after disposal', async () => {
  const { controller, ports, publish } = harness();
  await controller.open(journal());
  controller.edit({ notes: 'Retained only on device' });
  const pending = deferred<NativeJournalDraft>();
  vi.mocked(ports.save).mockReturnValueOnce(pending.promise);
  const saving = controller.saveDraft();
  controller.dispose();
  const count = publish.mock.calls.length;
  pending.resolve({
    journeyId,
    title: '',
    notes: 'Retained only on device',
    expectedVersion: 0,
    mutationId: mutation(1),
  });
  await saving;
  expect(publish).toHaveBeenCalledTimes(count);
});

it('classifies a session change during online checks without an unhandled send', async () => {
  const { controller, ports } = harness();
  await controller.open(journal());
  vi.spyOn(ports, 'online').mockImplementationOnce(() => {
    throw new NativeSessionRequired();
  });
  await controller.send();
  expect(controller.state().failure).toBe('session');
  expect(ports.write).not.toHaveBeenCalled();
});

it('finishes an explicitly confirmed discard across same-account renewal', async () => {
  const { controller, ports } = harness();
  await controller.open(journal());
  controller.edit({ notes: 'Mine' });
  await controller.saveDraft();
  vi.mocked(ports.write).mockRejectedValueOnce(new NativeHttpStatus(409));
  await controller.send();
  controller.edit({ notes: 'Unsaved newer text' });
  await controller.reviewLatest();
  expect(controller.state().dirty).toBe(true);
  const pending = deferred<TripJournal>();
  vi.mocked(ports.discard).mockReturnValueOnce(pending.promise);
  const discarding = controller.useAccountVersion();
  controller.sessionChanged();
  expect(controller.state()).toMatchObject({ busy: true, draft: expect.any(Object) });
  pending.resolve(journal(2, 'Account', 'Latest'));
  await discarding;
  expect(controller.state()).toMatchObject({
    busy: false,
    draft: null,
    notes: 'Latest',
    failure: null,
  });
});

it('retains the exact durable draft on timeout, failed acknowledgement and changed text', async () => {
  const { controller, ports } = harness();
  await controller.open(journal());
  controller.edit({ notes: 'First' });
  vi.mocked(ports.write).mockRejectedValueOnce(new Error('timeout'));
  await controller.send();
  const first = controller.state().draft!;
  expect(controller.state().failure).toBe('unavailable');
  vi.mocked(ports.acknowledge).mockResolvedValueOnce(false);
  await controller.send();
  expect(vi.mocked(ports.write).mock.calls[1]?.[1].mutationId).toBe(first.mutationId);
  expect(controller.state()).toMatchObject({ draft: first, failure: 'storage' });
  controller.edit({ notes: 'Second' });
  await controller.saveDraft();
  expect(controller.state().draft?.mutationId).not.toBe(first.mutationId);
  expect(controller.state().draft?.expectedVersion).toBe(first.expectedVersion);
});

it('keeps conflict work until explicit review and confirmed discard', async () => {
  const { controller, ports } = harness();
  await controller.open(journal());
  controller.edit({ notes: 'Mine' });
  vi.mocked(ports.write).mockRejectedValueOnce(new NativeHttpStatus(409));
  await controller.send();
  expect(controller.state()).toMatchObject({
    failure: 'conflict',
    draft: expect.objectContaining({ notes: 'Mine' }),
  });
  await controller.reviewLatest();
  expect(controller.state().reviewed?.annotation.notes).toBe('Latest');
  expect(controller.state().draft?.notes).toBe('Mine');
  await controller.useAccountVersion();
  expect(controller.state()).toMatchObject({ draft: null, notes: 'Latest', failure: null });
  expect(ports.discard).toHaveBeenCalledOnce();
});

it('guards unsaved closing and fences a late response on same-account session renewal', async () => {
  const { controller, ports } = harness();
  await controller.open(journal());
  controller.edit({ notes: 'Typing' });
  expect(controller.close()).toBe(false);
  await controller.saveDraft();
  expect(controller.state().dirty).toBe(false);
  const pending = deferred<TripJournal>();
  vi.mocked(ports.write).mockReturnValueOnce(pending.promise);
  const sending = controller.send();
  await Promise.resolve();
  controller.sessionChanged();
  pending.resolve(journal(1, '', 'Typing'));
  await sending;
  expect(ports.acknowledge).not.toHaveBeenCalled();
  expect(controller.state()).toMatchObject({
    open: true,
    notes: 'Typing',
    busy: false,
    draft: expect.any(Object),
  });
  expect(controller.close()).toBe(true);
});

it('reopens from a newer confirmed snapshot and an existing draft when browse data is stale', async () => {
  const { controller, ports } = harness();
  await controller.open(journal());
  controller.edit({ title: 'Saved', notes: 'Account notes' });
  await controller.send();
  expect(controller.close()).toBe(true);
  await controller.open(journal());
  expect(controller.state()).toMatchObject({
    title: 'Saved',
    notes: 'Account notes',
    journal: { annotation: { version: 1 } },
  });
  expect(ports.cache).toHaveBeenCalledTimes(1);
  controller.edit({ notes: 'A device draft' });
  await controller.saveDraft();
  expect(controller.close()).toBe(true);
  await controller.open(journal());
  expect(controller.state()).toMatchObject({
    notes: 'A device draft',
    draft: expect.objectContaining({ expectedVersion: 1 }),
  });
});
