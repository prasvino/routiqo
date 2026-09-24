import { afterEach, expect, it, vi } from 'vitest';
import type { createNativeAccount } from '../apps/mobile/src/auth/native-account';
import { createNativeTransport, NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import {
  readNativeConsent,
  readNativeLiveConsent,
  submitNativeConsent,
} from '../apps/mobile/src/features/live/native-consent';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const path = `/api/v1/native/journeys/${journeyId}/consent`;
const credential = 'A'.repeat(43);
const consent = {
  journeyId,
  generation: '9223372036854775807',
  sharing: false,
  journeyActive: true,
};
type Account = ReturnType<typeof createNativeAccount>;
function identity(
  request: (path: string, method: string, options: unknown) => Promise<unknown>,
  revision = () => 1,
  activeAccount = () => accountId,
): Account {
  return { verifiedRequest: request, revision, activeAccount } as unknown as Account;
}
afterEach(() => vi.useRealTimers());

it('allows only the exact private consent path, status 200 and bounded JSON response', async () => {
  const driver = vi.fn(async () => ({ status: 200, body: JSON.stringify(consent) }));
  const transport = createNativeTransport({ request: driver }, 'https://staging.routiqo.example');
  expect(await transport.request(path, 'GET', { credential, accountId })).toEqual(consent);
  expect(driver).toHaveBeenCalledWith(
    'https://staging.routiqo.example',
    path,
    'GET',
    credential,
    accountId,
    null,
  );
  expect(
    await transport.request(path, 'POST', {
      credential,
      accountId,
      body: { expectedGeneration: '0', sharing: false },
    }),
  ).toEqual(consent);
  expect(driver).toHaveBeenLastCalledWith(
    'https://staging.routiqo.example',
    path,
    'POST',
    credential,
    accountId,
    '{"expectedGeneration":"0","sharing":false}',
  );
  for (const invalid of [path + '?x=1', path + '\n', path + '/extra'])
    await expect(transport.request(invalid, 'GET', { credential, accountId })).rejects.toThrow();
  expect(driver).toHaveBeenCalledTimes(2);
  for (const status of [201, 204, 206]) {
    const wrong = createNativeTransport(
      { request: async () => ({ status, body: JSON.stringify(consent) }) },
      'https://staging.routiqo.example',
    );
    await expect(wrong.request(path, 'GET', { credential, accountId })).rejects.toThrow(
      'Native server response is invalid.',
    );
  }
  const denied = createNativeTransport(
    { request: async () => ({ status: 429, body: 'private' }) },
    'https://staging.routiqo.example',
  );
  await expect(denied.request(path, 'GET', { credential, accountId })).rejects.toEqual(
    new NativeHttpStatus(429),
  );
  const large = createNativeTransport(
    { request: async () => ({ status: 200, body: 'x'.repeat(65_537) }) },
    'https://staging.routiqo.example',
  );
  await expect(large.request(path, 'GET', { credential, accountId })).rejects.toThrow();
  const malformedUtf8 = createNativeTransport(
    { request: async () => ({ status: 200, body: '\ud800' }) },
    'https://staging.routiqo.example',
  );
  await expect(malformedUtf8.request(path, 'GET', { credential, accountId })).rejects.toThrow();
});

it('validates exact identities, response fields and canonical long generation strings', async () => {
  expect(readNativeLiveConsent(consent, journeyId)).toEqual(consent);
  for (const invalid of [
    { ...consent, generation: '00' },
    { ...consent, generation: '1\n' },
    { ...consent, generation: '9223372036854775808' },
    { ...consent, generation: 2 },
    { ...consent, sharing: 'false' },
    { ...consent, journeyActive: false, sharing: true },
    { ...consent, journeyId: `${journeyId}\n` },
    { ...consent, journeyId: accountId },
    { ...consent, extra: true },
  ])
    expect(() => readNativeLiveConsent(invalid, journeyId)).toThrow();
  expect(() => readNativeLiveConsent(consent, `${journeyId}\n`)).toThrow();
  const request = vi.fn(async () => consent);
  const actor = identity(request);
  for (const bad of [accountId + '\n', '00000000-0000-0000-0000-000000000000'])
    await expect(readNativeConsent(actor, bad, journeyId)).rejects.toThrow();
  for (const bad of [journeyId + '\n', '00000000-0000-0000-0000-000000000000'])
    await expect(readNativeConsent(actor, accountId, bad)).rejects.toThrow();
  for (const expectedGeneration of ['01', '-1', '9223372036854775808', '1.0'])
    await expect(
      submitNativeConsent(actor, accountId, journeyId, { expectedGeneration, sharing: false }),
    ).rejects.toThrow();
  await expect(
    submitNativeConsent(actor, accountId, journeyId, {
      expectedGeneration: '0',
      sharing: 1,
    } as never),
  ).rejects.toThrow();
  await expect(
    submitNativeConsent(actor, accountId, journeyId, {
      expectedGeneration: '0',
      sharing: false,
      extra: true,
    } as never),
  ).rejects.toThrow();
  expect(request).not.toHaveBeenCalled();
});

it('snapshots request input before credential access and preserves exact decimal strings', async () => {
  let finish!: (value: unknown) => void;
  const request = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const actor = identity(request);
  const input = { expectedGeneration: '9223372036854775807', sharing: false };
  const pending = submitNativeConsent(actor, accountId, journeyId, input);
  input.expectedGeneration = '0';
  input.sharing = true;
  expect(request).toHaveBeenCalledWith(path, 'POST', {
    accountId,
    body: { expectedGeneration: '9223372036854775807', sharing: false },
    signal: expect.any(AbortSignal),
  });
  finish(consent);
  await expect(pending).resolves.toEqual(consent);
});

it('accepts only an acknowledgement matching enable or stop intent', async () => {
  const request = vi.fn(async () => ({
    journeyId,
    generation: '1',
    sharing: true,
    journeyActive: true,
  }));
  const actor = identity(request);
  await expect(
    submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '0', sharing: true }),
  ).resolves.toMatchObject({ sharing: true });
  for (const invalid of [
    { journeyId, generation: '1', sharing: false, journeyActive: true },
    { journeyId, generation: '2', sharing: true, journeyActive: true },
    { journeyId, generation: '1', sharing: true, journeyActive: false },
  ]) {
    request.mockResolvedValueOnce(invalid);
    await expect(
      submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '0', sharing: true }),
    ).rejects.toThrow();
  }
  request.mockResolvedValueOnce({
    journeyId,
    generation: '2',
    sharing: false,
    journeyActive: true,
  });
  await expect(
    submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '0', sharing: false }),
  ).resolves.toMatchObject({ sharing: false });
  request.mockResolvedValueOnce({
    journeyId,
    generation: '4',
    sharing: false,
    journeyActive: true,
  });
  await expect(
    submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '4', sharing: false }),
  ).rejects.toThrow();
  request.mockResolvedValueOnce({
    journeyId,
    generation: '9223372036854775807',
    sharing: false,
    journeyActive: true,
  });
  await expect(
    submitNativeConsent(actor, accountId, journeyId, {
      expectedGeneration: '9223372036854775807',
      sharing: false,
    }),
  ).resolves.toMatchObject({ sharing: false, journeyActive: true });
  request.mockResolvedValueOnce({
    journeyId,
    generation: '2',
    sharing: false,
    journeyActive: false,
  });
  await expect(
    submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '5', sharing: false }),
  ).resolves.toMatchObject({ generation: '2', journeyActive: false });
  request.mockResolvedValueOnce({
    journeyId,
    generation: '0',
    sharing: false,
    journeyActive: false,
  });
  await expect(
    submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '0', sharing: false }),
  ).resolves.toMatchObject({ journeyActive: false });
  request.mockResolvedValueOnce({ journeyId, generation: '1', sharing: true, journeyActive: true });
  await expect(
    submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '0', sharing: false }),
  ).rejects.toThrow();
  request.mockResolvedValueOnce({
    journeyId,
    generation: '0',
    sharing: false,
    journeyActive: true,
  });
  await expect(
    submitNativeConsent(actor, accountId, journeyId, { expectedGeneration: '1', sharing: false }),
  ).rejects.toThrow();
});

it('fences cancellation and late account revision changes', async () => {
  let finish!: (value: unknown) => void;
  let revision = 1;
  const request = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const actor = identity(request, () => revision);
  const controller = new AbortController();
  const cancelled = submitNativeConsent(
    actor,
    accountId,
    journeyId,
    { expectedGeneration: '0', sharing: true },
    controller.signal,
  );
  controller.abort();
  await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
  finish(consent);
  const stale = readNativeConsent(actor, accountId, journeyId);
  revision++;
  finish(consent);
  await expect(stale).rejects.toThrow('could not be completed');
  const prior = new AbortController();
  prior.abort();
  await expect(readNativeConsent(actor, accountId, journeyId, prior.signal)).rejects.toMatchObject({
    name: 'AbortError',
  });
  expect(request).toHaveBeenCalledTimes(2);
});

it('applies one twelve-second deadline even when the bridge ignores abort', async () => {
  vi.useFakeTimers();
  let finish!: (value: unknown) => void;
  const request = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const pending = readNativeConsent(identity(request), accountId, journeyId);
  const assertion = expect(pending).rejects.toThrow('timed out');
  await vi.advanceTimersByTimeAsync(12_000);
  await assertion;
  finish(consent);
  expect(request).toHaveBeenCalledTimes(1);
});
