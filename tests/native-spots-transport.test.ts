import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { createNativeTransport, NativeHttpStatus } from '../apps/mobile/src/auth/safe-transport';

const origin = 'https://staging.routiqo.example';
const account = '00000000-0000-4000-8000-000000000001';
const credential = 'A'.repeat(43);
const version = '00000000-0000-4000-8000-0000000000aa';
const etag = `"${version}"`;
const catalogPath = '/api/v1/native/spots/catalog';
const activityPath = '/api/v1/native/spots/activity';

type DriverResponse = { status: number; body: string; etag?: string | null };
function transportFor(response: DriverResponse) {
  const request = vi.fn(async (..._arguments: unknown[]) => response);
  return { request, transport: createNativeTransport({ request }, origin) };
}

describe('Spot catalog transport', () => {
  it('reads the catalog with identity, returns the strict ETag and sends no If-None-Match by default', async () => {
    const { request, transport } = transportFor({
      status: 200,
      body: `{"version":"${version}"}`,
      etag,
    });
    await expect(transport.spotCatalog({ credential, accountId: account })).resolves.toEqual({
      status: 'catalog',
      etag,
      catalog: { version },
    });
    expect(request).toHaveBeenCalledWith(
      origin,
      catalogPath,
      'GET',
      credential,
      account,
      null,
      null,
    );
  });

  it('accepts 304 only when If-None-Match was sent and the ETag matches with an empty body', async () => {
    const current = transportFor({ status: 304, body: '', etag });
    await expect(
      current.transport.spotCatalog({ credential, accountId: account, ifNoneMatch: etag }),
    ).resolves.toEqual({ status: 'not-modified', etag });
    expect(current.request).toHaveBeenCalledWith(
      origin,
      catalogPath,
      'GET',
      credential,
      account,
      null,
      etag,
    );

    const unsolicited = transportFor({ status: 304, body: '', etag });
    await expect(
      unsolicited.transport.spotCatalog({ credential, accountId: account }),
    ).rejects.toBeInstanceOf(NativeHttpStatus);

    const otherTag = transportFor({
      status: 304,
      body: '',
      etag: '"00000000-0000-4000-8000-0000000000bb"',
    });
    await expect(
      otherTag.transport.spotCatalog({ credential, accountId: account, ifNoneMatch: etag }),
    ).rejects.toThrow('Native server response is invalid.');

    const withBody = transportFor({ status: 304, body: '{}', etag });
    await expect(
      withBody.transport.spotCatalog({ credential, accountId: account, ifNoneMatch: etag }),
    ).rejects.toThrow('Native server response is invalid.');
  });

  it('rejects malformed identity or If-None-Match before any request', async () => {
    const { request, transport } = transportFor({ status: 200, body: '{}', etag });
    for (const ifNoneMatch of [
      version,
      `W/${etag}`,
      '*',
      `"${version.toUpperCase()}"`,
      `${etag}, ${etag}`,
    ])
      await expect(
        transport.spotCatalog({ credential, accountId: account, ifNoneMatch }),
      ).rejects.toThrow('Native request is invalid.');
    await expect(
      transport.spotCatalog({ credential: 'short', accountId: account }),
    ).rejects.toThrow('Native request is invalid.');
    await expect(
      transport.spotCatalog({ credential, accountId: 'not-an-account' }),
    ).rejects.toThrow('Native request is invalid.');
    expect(request).not.toHaveBeenCalled();
  });

  it('requires a strict ETag, UTF-8 JSON and at most 256 KiB on 200', async () => {
    for (const response of [
      { status: 200, body: '{}' },
      { status: 200, body: '{}', etag: version },
      { status: 200, body: 'not json', etag },
      { status: 200, body: `"${'x'.repeat(256 * 1024)}"`, etag },
      { status: 201, body: '{}', etag },
    ]) {
      const { transport } = transportFor(response);
      await expect(transport.spotCatalog({ credential, accountId: account })).rejects.toThrow(
        'Native server response is invalid.',
      );
    }
    const limited = transportFor({ status: 429, body: '' });
    await expect(limited.transport.spotCatalog({ credential, accountId: account })).rejects.toEqual(
      new NativeHttpStatus(429),
    );
  });

  it('keeps the catalog off the generic request path', async () => {
    const { request, transport } = transportFor({ status: 200, body: '{}', etag });
    for (const method of ['GET', 'POST'] as const)
      await expect(
        transport.request(catalogPath, method, { credential, accountId: account }),
      ).rejects.toThrow('Native request is invalid.');
    expect(request).not.toHaveBeenCalled();
  });
});

describe('Spot activity transport', () => {
  const body = { spotIds: ['00000000-0000-4000-8000-000000000011'] };

  it('posts Spot IDs in the body with identity and parses the 200 response', async () => {
    const response = '{"serverTime":"2026-10-01T00:00:00Z","spots":[],"alerts":[]}';
    const { request, transport } = transportFor({ status: 200, body: response });
    await expect(
      transport.request(activityPath, 'POST', { credential, accountId: account, body }),
    ).resolves.toEqual(JSON.parse(response));
    expect(request).toHaveBeenCalledWith(
      origin,
      activityPath,
      'POST',
      credential,
      account,
      JSON.stringify(body),
    );
  });

  it('rejects GET, query strings and missing identity before any request', async () => {
    const { request, transport } = transportFor({ status: 200, body: '{}' });
    await expect(
      transport.request(activityPath, 'GET', { credential, accountId: account }),
    ).rejects.toThrow('Native request is invalid.');
    await expect(
      transport.request(`${activityPath}?id=1`, 'POST', { credential, accountId: account, body }),
    ).rejects.toThrow('Native request is invalid.');
    await expect(transport.request(activityPath, 'POST', { credential, body })).rejects.toThrow(
      'Native journey session is unavailable.',
    );
    await expect(
      transport.request('/api/v1/native/spots', 'POST', { credential, accountId: account, body }),
    ).rejects.toThrow('Native request is invalid.');
    expect(request).not.toHaveBeenCalled();
  });

  it('caps the response at 128 KiB, requires 200 and strict UTF-8', async () => {
    for (const response of [
      { status: 200, body: `"${'x'.repeat(128 * 1024)}"` },
      { status: 204, body: '' },
      { status: 200, body: '"\uD800"' },
    ]) {
      const { transport } = transportFor(response);
      await expect(
        transport.request(activityPath, 'POST', { credential, accountId: account, body }),
      ).rejects.toThrow('Native server response is invalid.');
    }
    for (const status of [409, 429, 503]) {
      const { transport } = transportFor({ status, body: '' });
      await expect(
        transport.request(activityPath, 'POST', { credential, accountId: account, body }),
      ).rejects.toEqual(new NativeHttpStatus(status));
    }
  });
});

describe('Spot write transport', () => {
  const item = '00000000-0000-4000-8000-0000000000c1';
  const send = (
    transport: ReturnType<typeof createNativeTransport>,
    path: string,
    method: 'GET' | 'POST' = 'POST',
  ) =>
    transport.request(path, method, {
      credential,
      accountId: account,
      body: method === 'POST' ? {} : undefined,
    });

  it('allows only the Spot write paths, POST only, with identity', async () => {
    const receipt = '{"ref":"' + item + '","status":"active","expiresAt":"2026-11-05T07:30:00Z"}';
    for (const path of [
      '/api/v1/native/spots/signals',
      '/api/v1/native/spots/posts',
      `/api/v1/native/spots/items/${item}/vote`,
      `/api/v1/native/spots/items/${item}/delete`,
    ]) {
      const { request, transport } = transportFor({ status: 200, body: receipt });
      await expect(send(transport, path)).resolves.toEqual(JSON.parse(receipt));
      expect(request).toHaveBeenCalledWith(origin, path, 'POST', credential, account, '{}');
      await expect(send(transport, path, 'GET')).rejects.toThrow('Native request is invalid.');
      await expect(transport.request(path, 'POST', { credential, body: {} })).rejects.toThrow(
        'Native journey session is unavailable.',
      );
    }
    for (const path of [
      `/api/v1/native/spots/items/${item}/hide`,
      `/api/v1/native/spots/items/not-a-ref/vote`,
      `/api/v1/native/spots/items/${item.toUpperCase()}/vote`,
      '/api/v1/native/spots/signals/extra',
    ]) {
      const { transport } = transportFor({ status: 200, body: receipt });
      await expect(send(transport, path)).rejects.toThrow('Native request is invalid.');
    }
  });

  it('accepts exactly 202 JSON for reports and an empty 204 for block', async () => {
    const reports = `/api/v1/native/spots/items/${item}/reports`;
    const block = `/api/v1/native/spots/items/${item}/block-author`;
    const report =
      '{"receivedAt":"2026-11-05T06:30:00Z","receiptExpiresAt":"2026-11-12T06:30:00Z"}';
    await expect(
      send(transportFor({ status: 202, body: report }).transport, reports),
    ).resolves.toEqual(JSON.parse(report));
    await expect(
      send(transportFor({ status: 200, body: report }).transport, reports),
    ).rejects.toThrow('Native server response is invalid.');
    await expect(
      send(transportFor({ status: 204, body: '' }).transport, block),
    ).resolves.toBeNull();
    await expect(send(transportFor({ status: 204, body: '{}' }).transport, block)).rejects.toThrow(
      'Native server response is invalid.',
    );
    await expect(send(transportFor({ status: 200, body: '{}' }).transport, block)).rejects.toThrow(
      'Native server response is invalid.',
    );
    const refused = send(
      transportFor({ status: 410, body: '' }).transport,
      '/api/v1/native/spots/posts',
    );
    await expect(refused).rejects.toBeInstanceOf(NativeHttpStatus);
  });
});

describe('Kotlin safe-HTTP parity (static; no Android SDK in CI)', () => {
  const kotlin = readFileSync(
    'apps/mobile/modules/routiqo-safe-http/android/src/main/java/com/routiqo/safehttp/RoutiqoSafeHttpModule.kt',
    'utf8',
  );

  it('mirrors the Spot paths, methods, identity, caps, ETag and narrow 304 rules', () => {
    for (const fragment of [
      'payload: String?, ifNoneMatch: String? ->',
      'val spotCatalogPath = path == "/api/v1/native/spots/catalog"',
      'val spotActivityPath = path == "/api/v1/native/spots/activity"',
      'require(!spotCatalogPath || method == "GET")',
      'require(!spotActivityPath || method == "POST")',
      'require(ifNoneMatch == null || spotCatalogPath && ifNoneMatch.matches(etagPattern))',
      'require(!(journeyPath || routingPath || spotPath) || credential != null && account != null)',
      'val notModified = response.code == 304 && spotCatalogPath && ifNoneMatch != null',
      'require(!response.isRedirect && (response.code !in 300..399 || notModified))',
      'require(!notModified || bytes.isEmpty())',
      'spotCatalogPath -> 256 * 1024',
      'spotActivityPath -> 128 * 1024',
      '"etag" to etag',
      'val spotWritePath = path.matches(Regex("/api/v1/native/spots/(?:signals|posts)"))',
      'val spotItemPath = path.matches(Regex("/api/v1/native/spots/items/$uuid/(?:vote|delete|reports|block-author)"))',
      'val spotPath = spotCatalogPath || spotActivityPath || spotWritePath || spotItemPath',
      'require(!(spotWritePath || spotItemPath) || method == "POST")',
      '(response.code == 200 || response.code == 202)',
    ])
      expect(kotlin).toContain(fragment);
  });
});

describe('Android adapter', () => {
  it('always calls the native module with seven arguments, null If-None-Match for generic calls', async () => {
    vi.resetModules();
    const nativeRequest = vi.fn(async (..._arguments: unknown[]) => ({
      status: 200,
      body: '{"serverTime":"2026-10-01T00:00:00Z","spots":[],"alerts":[]}',
      etag: null,
    }));
    vi.doMock('../apps/mobile/node_modules/expo', () => ({
      requireNativeModule: () => ({ request: nativeRequest }),
    }));
    vi.stubEnv('EXPO_PUBLIC_ROUTIQO_API_ORIGIN', origin);
    try {
      const { nativeTransport } = await import('../apps/mobile/src/auth/android-transport');
      await nativeTransport.request(activityPath, 'POST', {
        credential,
        accountId: account,
        body: { spotIds: ['00000000-0000-4000-8000-000000000011'] },
      });
      expect(nativeRequest.mock.calls[0]).toHaveLength(7);
      expect(nativeRequest.mock.calls[0]?.[6]).toBeNull();

      nativeRequest.mockResolvedValueOnce({ status: 304, body: '', etag });
      await nativeTransport.spotCatalog({ credential, accountId: account, ifNoneMatch: etag });
      expect(nativeRequest.mock.calls[1]).toEqual([
        origin,
        catalogPath,
        'GET',
        credential,
        account,
        null,
        etag,
      ]);
    } finally {
      vi.doUnmock('../apps/mobile/node_modules/expo');
      vi.unstubAllEnvs();
    }
  });
});
