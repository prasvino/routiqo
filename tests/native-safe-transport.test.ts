import { expect, it, vi } from 'vitest';
import {
  createNativeTransport,
  nativeApiOrigin,
  NativeHttpStatus,
} from '../apps/mobile/src/auth/safe-transport';

const origin = 'https://staging.routiqo.example';
const account = '00000000-0000-4000-8000-000000000001';
const credential = 'A'.repeat(43);
it('accepts only a fixed bare HTTPS origin and native API paths', async () => {
  for (const value of [
    'http://example.com',
    'https://user@example.com',
    'https://example.com/path',
    'https://example.com?x=1',
    'https://example.com:8443',
  ])
    expect(nativeApiOrigin(value)).toBeNull();
  const request = vi.fn(async () => ({ status: 200, body: '{"accountId":"' + account + '"}' }));
  const transport = createNativeTransport({ request }, origin);
  expect(await transport.request('/api/v1/native/auth/session', 'GET', { credential })).toEqual({
    accountId: account,
  });
  expect(request).toHaveBeenCalledWith(
    origin,
    '/api/v1/native/auth/session',
    'GET',
    credential,
    null,
    null,
  );
  await expect(
    transport.request('/api/v1/journeys', 'GET', { credential, accountId: account }),
  ).rejects.toThrow('Native request is invalid.');
  await expect(
    transport.request('/api/v1/native/journeys?limit=1', 'GET', { credential, accountId: account }),
  ).rejects.toThrow();
  await expect(transport.request('/api/v1/native/journeys', 'GET')).rejects.toThrow(
    'Native journey session is unavailable.',
  );
  await expect(
    transport.request('/api/v1/native/journeys/' + 'a'.repeat(36), 'GET', {
      credential,
      accountId: account,
    }),
  ).rejects.toThrow('Native request is invalid.');
  await expect(
    transport.request('/api/v1/native/auth/google/challenge', 'POST', { credential }),
  ).rejects.toThrow('Native request is invalid.');
  await expect(
    transport.request('/api/v1/native/auth/google/exchange', 'POST', { accountId: account }),
  ).rejects.toThrow('Native request is invalid.');
  await expect(transport.request('/api/v1/native/auth/logout', 'POST')).rejects.toThrow(
    'Native request is invalid.',
  );
  await expect(
    transport.request('/api/v1/native/auth/logout', 'POST', { credential, accountId: account }),
  ).rejects.toThrow('Native request is invalid.');
  expect(request).toHaveBeenCalledTimes(1);
});
it('allows fixed native history POST only with a verified account and no query', async () => {
  const request = vi.fn(async () => ({ status: 200, body: '{"journeys":[],"next":null}' }));
  const transport = createNativeTransport({ request }, origin);
  await expect(
    transport.request('/api/v1/native/journeys/history', 'POST', {
      credential,
      accountId: account,
      body: {},
    }),
  ).resolves.toEqual({ journeys: [], next: null });
  expect(request).toHaveBeenCalledWith(
    origin,
    '/api/v1/native/journeys/history',
    'POST',
    credential,
    account,
    '{}',
  );
  await expect(
    transport.request('/api/v1/native/journeys/history?before=1', 'POST', {
      credential,
      accountId: account,
      body: {},
    }),
  ).rejects.toThrow();
  await expect(
    transport.request('/api/v1/native/journeys/history', 'GET', { credential, accountId: account }),
  ).rejects.toThrow();
  await expect(
    transport.request('/api/v1/native/journeys/history', 'POST', { credential, body: {} }),
  ).rejects.toThrow();
});
it('bounds and redacts failures without returning server error bodies', async () => {
  const failed = createNativeTransport(
    { request: async () => ({ status: 401, body: 'private secret' }) },
    origin,
  );
  await expect(
    failed.request('/api/v1/native/auth/session', 'GET', { credential }),
  ).rejects.toEqual(new NativeHttpStatus(401));
  const redirect = createNativeTransport(
    {
      request: async () => {
        throw new Error('Location: attacker');
      },
    },
    origin,
  );
  await expect(
    redirect.request('/api/v1/native/auth/session', 'GET', { credential }),
  ).rejects.toThrow('Secure server connection failed. Try again.');
  const oversized = createNativeTransport(
    { request: async () => ({ status: 200, body: 'x'.repeat(65537) }) },
    origin,
  );
  await expect(
    oversized.request('/api/v1/native/auth/session', 'GET', { credential }),
  ).rejects.toThrow('Native server response is invalid.');
  const request = vi.fn();
  const bounded = createNativeTransport({ request }, origin);
  await expect(
    bounded.request('/api/v1/native/auth/google/exchange', 'POST', {
      body: { idToken: 'x'.repeat(21000) },
    }),
  ).rejects.toThrow('too large');
  expect(request).not.toHaveBeenCalled();
});
