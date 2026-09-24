import { afterEach, expect, it, vi } from 'vitest';
import { createNativeAccount } from '../apps/mobile/src/auth/native-account';
import { createNativeTransport, NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';
import { createSessionVault } from '../apps/mobile/src/auth/session-vault';
import {
  bindNativeRouteContext,
  readNativeRouteBindingResult,
  readNativeRouteContext,
  readNativeRouteContextResult,
  type NativeRouteBindingInput,
} from '../apps/mobile/src/features/live/native-route-context';

const accountId = '00000000-0000-4000-8000-000000000001';
const journeyId = '00000000-0000-4000-8000-000000000002';
const contextId = '00000000-0000-4000-8000-000000000003';
const anchorOne = '00000000-0000-4000-8000-000000000004';
const anchorTwo = '00000000-0000-4000-8000-000000000005';
const path = `/api/v1/native/journeys/${journeyId}/route-context`;
const credential = 'A'.repeat(43);
const context = {
  contextId,
  revision: '9223372036854775807',
  anchorIds: [anchorOne, anchorTwo],
  issuedAt: '2026-09-24T10:00:00.123456789Z',
  expiresAt: '2026-09-24T10:15:00.123456789Z',
};
const input: NativeRouteBindingInput = {
  mode: 'driving',
  origin: [80, 13],
  destination: [79, 12],
  alternativeIndex: 1,
  expectedContextId: contextId,
};
type Account = ReturnType<typeof createNativeAccount>;
function actor(
  verifiedRequest: Account['verifiedRequest'],
  revision = () => 1,
  activeAccount = () => accountId,
): Account {
  return { verifiedRequest, revision, activeAccount } as unknown as Account;
}
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it('allows only the exact GET/POST leaf with JSON, strict 200 and 64 KiB UTF-8', async () => {
  const driver = vi.fn(async () => ({ status: 200, body: JSON.stringify({ context }) }));
  const transport = createNativeTransport({ request: driver }, 'https://staging.routiqo.example');
  expect(await transport.request(path, 'GET', { credential, accountId })).toEqual({ context });
  expect(await transport.request(path, 'POST', { credential, accountId, body: input })).toEqual({
    context,
  });
  expect(driver).toHaveBeenLastCalledWith(
    'https://staging.routiqo.example',
    path,
    'POST',
    credential,
    accountId,
    JSON.stringify(input),
  );
  for (const invalid of [path + '?x=1', path + '\n', path + '/extra'])
    await expect(transport.request(invalid, 'GET', { credential, accountId })).rejects.toThrow();
  expect(driver).toHaveBeenCalledTimes(2);
  for (const status of [201, 204]) {
    const wrong = createNativeTransport(
      { request: async () => ({ status, body: JSON.stringify({ context }) }) },
      'https://staging.routiqo.example',
    );
    await expect(wrong.request(path, 'GET', { credential, accountId })).rejects.toThrow(
      'Native server response is invalid.',
    );
  }
  const base = JSON.stringify({ context });
  const bounded = createNativeTransport(
    { request: async () => ({ status: 200, body: base + ' '.repeat(65_536 - base.length) }) },
    'https://staging.routiqo.example',
  );
  await expect(bounded.request(path, 'GET', { credential, accountId })).resolves.toEqual({
    context,
  });
  const oversized = createNativeTransport(
    { request: async () => ({ status: 200, body: base + ' '.repeat(65_537 - base.length) }) },
    'https://staging.routiqo.example',
  );
  await expect(oversized.request(path, 'GET', { credential, accountId })).rejects.toThrow();
  const malformed = createNativeTransport(
    { request: async () => ({ status: 200, body: '\ud800' }) },
    'https://staging.routiqo.example',
  );
  await expect(malformed.request(path, 'GET', { credential, accountId })).rejects.toThrow();
});

it('strictly validates context revision, sorted anchors and nanosecond lifetime', () => {
  expect(readNativeRouteContextResult({ context })).toEqual({ context });
  expect(readNativeRouteContextResult({ context: null })).toEqual({ context: null });
  for (const invalid of [
    { ...context, revision: '00' },
    { ...context, revision: '1\n' },
    { ...context, revision: '9223372036854775808' },
    { ...context, contextId: `${contextId}\n` },
    { ...context, contextId: '00000000-0000-0000-0000-000000000000' },
    { ...context, anchorIds: [] },
    { ...context, anchorIds: [anchorTwo, anchorOne] },
    { ...context, anchorIds: [anchorOne, anchorOne] },
    {
      ...context,
      anchorIds: Array.from(
        { length: 129 },
        (_, index) => `00000000-0000-4000-9000-${String(index).padStart(12, '0')}`,
      ),
    },
    { ...context, expiresAt: '2026-09-24T10:15:00.123456790Z' },
    { ...context, expiresAt: context.issuedAt },
    { ...context, issuedAt: '2026-02-30T10:00:00Z' },
    { ...context, issuedAt: '2026-09-24T10:00:00.1234567891Z' },
    { ...context, extra: true },
  ])
    expect(() => readNativeRouteContextResult({ context: invalid })).toThrow();
  expect(() => readNativeRouteContextResult({ context, extra: true })).toThrow();
});

it('requires bound to carry context and empty outcomes to carry null', () => {
  expect(readNativeRouteBindingResult({ status: 'bound', context })).toEqual({
    status: 'bound',
    context,
  });
  for (const status of ['no_route', 'no_eligible_anchors'] as const)
    expect(readNativeRouteBindingResult({ status, context: null })).toEqual({
      status,
      context: null,
    });
  for (const invalid of [
    { status: 'bound', context: null },
    { status: 'no_route', context },
    { status: 'no_eligible_anchors', context },
    { status: 'unknown', context: null },
    { status: 'bound', context, extra: true },
  ])
    expect(() => readNativeRouteBindingResult(invalid)).toThrow();
});

it('copies validated binding input before credentials and rejects coerced or extra fields', async () => {
  let finish!: (value: unknown) => void;
  const verifiedRequest = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const mutable: NativeRouteBindingInput = {
    ...input,
    origin: [...input.origin],
    destination: [...input.destination],
  };
  const pending = bindNativeRouteContext(actor(verifiedRequest), accountId, journeyId, mutable);
  mutable.origin[0] = 70;
  mutable.alternativeIndex = 2;
  mutable.expectedContextId = null;
  expect(verifiedRequest).toHaveBeenCalledWith(path, 'POST', {
    accountId,
    body: input,
    signal: expect.any(AbortSignal),
  });
  finish({ status: 'bound', context });
  await expect(pending).resolves.toEqual({ status: 'bound', context });
  for (const invalid of [
    { ...input, alternativeIndex: 3 },
    { ...input, alternativeIndex: 0.5 },
    { ...input, expectedContextId: `${contextId}\n` },
    { ...input, expectedContextId: undefined },
    { ...input, origin: [181, 13] },
    { ...input, extra: true },
  ])
    expect(() =>
      bindNativeRouteContext(
        actor(verifiedRequest),
        accountId,
        journeyId,
        invalid as NativeRouteBindingInput,
      ),
    ).toThrow();
  expect(verifiedRequest).toHaveBeenCalledTimes(1);
});

it('maps HTTP failures to safe codes and rejects malformed response', async () => {
  for (const [status, code] of [
    [400, 'invalid'],
    [401, 'session'],
    [403, 'forbidden'],
    [404, 'not_found'],
    [409, 'conflict'],
    [429, 'rate_limited'],
    [503, 'unavailable'],
  ] as const) {
    const identity = actor(
      vi.fn(async () => {
        throw new NativeHttpStatus(status);
      }),
    );
    await expect(readNativeRouteContext(identity, accountId, journeyId)).rejects.toMatchObject({
      code,
      status,
    });
  }
  const malformed = actor(vi.fn(async () => ({ context: { ...context, revision: '1\n' } })));
  await expect(readNativeRouteContext(malformed, accountId, journeyId)).rejects.toThrow();
  const failure = actor(
    vi.fn(async () => {
      throw new Error('private provider detail');
    }),
  );
  await expect(readNativeRouteContext(failure, accountId, journeyId)).rejects.toMatchObject({
    code: 'unavailable',
    message: 'Private route preparation is unavailable. Try again.',
  });
});

it('honors cancellation without DOMException and fences late account results', async () => {
  vi.stubGlobal('DOMException', undefined);
  let finish!: (value: unknown) => void;
  let revision = 1;
  const verifiedRequest = vi.fn(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }),
  );
  const identity = actor(verifiedRequest, () => revision);
  const prior = new AbortController();
  prior.abort();
  await expect(
    readNativeRouteContext(identity, accountId, journeyId, prior.signal),
  ).rejects.toMatchObject({ name: 'AbortError' });
  expect(verifiedRequest).not.toHaveBeenCalled();
  const cancellation = new AbortController();
  const cancelled = bindNativeRouteContext(
    identity,
    accountId,
    journeyId,
    input,
    cancellation.signal,
  );
  cancellation.abort();
  await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' });
  finish({ status: 'bound', context });
  const stale = readNativeRouteContext(identity, accountId, journeyId);
  revision++;
  finish({ context });
  await expect(stale).rejects.toMatchObject({ code: 'session' });
  expect(verifiedRequest).toHaveBeenCalledTimes(2);
});

it('uses separate twelve and thirty second operation deadlines', async () => {
  vi.useFakeTimers();
  const verifiedRequest = vi.fn(() => new Promise<unknown>(() => undefined));
  const identity = actor(verifiedRequest);
  const read = readNativeRouteContext(identity, accountId, journeyId);
  const readAssertion = expect(read).rejects.toMatchObject({ code: 'timeout' });
  await vi.advanceTimersByTimeAsync(12_000);
  await readAssertion;
  const bind = bindNativeRouteContext(identity, accountId, journeyId, input);
  const bindAssertion = expect(bind).rejects.toMatchObject({ code: 'timeout' });
  await vi.advanceTimersByTimeAsync(29_999);
  expect(verifiedRequest).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(1);
  await bindAssertion;
});

it('never dispatches after abort or timeout during real account credential loading', async () => {
  const now = Date.parse('2026-09-24T10:00:00Z');
  const session = { accountId, credential, expiresAt: now + 600_000 };
  let stored: string | null = null;
  const vault = createSessionVault(
    {
      read: async () => stored,
      write: async (value) => {
        stored = value;
      },
      remove: async () => {
        stored = null;
      },
    },
    () => now,
  );
  await vault.commit(vault.beginWrite(), session);
  const driver = vi.fn(async (_origin: string, requestPath: string) => ({
    status: 200,
    body:
      requestPath === '/api/v1/native/auth/session'
        ? JSON.stringify({ accountId })
        : JSON.stringify({ context }),
  }));
  const identity = createNativeAccount(
    vault,
    createNativeTransport({ request: driver }, 'https://staging.routiqo.example'),
    { idToken: async () => null },
    () => now,
  );
  expect(await identity.restore()).toBe(accountId);
  const dispatched = driver.mock.calls.length;
  const load = vi.spyOn(vault, 'load');
  vi.stubGlobal('DOMException', undefined);

  let releaseAbort!: (value: typeof session) => void;
  load.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        releaseAbort = resolve;
      }),
  );
  const cancellation = new AbortController();
  const aborted = readNativeRouteContext(identity, accountId, journeyId, cancellation.signal);
  cancellation.abort();
  await expect(aborted).rejects.toMatchObject({ name: 'AbortError' });
  releaseAbort(session);
  await Promise.resolve();
  await Promise.resolve();
  expect(driver.mock.calls.length).toBe(dispatched);

  vi.useFakeTimers();
  let releaseTimeout!: (value: typeof session) => void;
  load.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        releaseTimeout = resolve;
      }),
  );
  const timedOut = bindNativeRouteContext(identity, accountId, journeyId, input);
  const assertion = expect(timedOut).rejects.toMatchObject({ code: 'timeout' });
  await vi.advanceTimersByTimeAsync(30_000);
  await assertion;
  releaseTimeout(session);
  await Promise.resolve();
  await Promise.resolve();
  expect(driver.mock.calls.length).toBe(dispatched);
});
