export interface NativeHttpDriver {
  request(
    origin: string,
    path: string,
    method: 'GET' | 'POST',
    credential: string | null,
    accountId: string | null,
    payload: string | null,
  ): Promise<{ status: number; body: string }>;
}

const account = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const credentialPattern = /^[A-Za-z0-9_-]{43}$/;
const journeyId = '[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}';
const nativePath = new RegExp(
  `^/api/v1/native/(?:auth/(?:google/(?:challenge|exchange)|session(?:/renew)?|logout|account/delete)|journeys(?:/${journeyId}(?:/complete)?)?)$`,
);

export function nativeApiOrigin(value: string | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (
      url.protocol !== 'https:' ||
      url.username ||
      url.password ||
      url.pathname !== '/' ||
      url.search ||
      url.hash ||
      url.port
    )
      return null;
    return url.origin;
  } catch {
    return null;
  }
}

export class NativeHttpStatus extends Error {
  constructor(readonly status: number) {
    super('Native request was not accepted.');
  }
}

export function createNativeTransport(
  driver: NativeHttpDriver,
  configuredOrigin: string | undefined,
) {
  const origin = nativeApiOrigin(configuredOrigin);
  async function request(
    path: string,
    method: 'GET' | 'POST',
    options: { credential?: string; accountId?: string; body?: unknown } = {},
  ): Promise<unknown> {
    if (!origin) throw new Error('Secure server connection is not configured.');
    if (!nativePath.test(path) || path.includes('?') || path.includes('#'))
      throw new Error('Native request is invalid.');
    const credential = options.credential ?? null;
    const accountId = options.accountId ?? null;
    if (credential !== null && !credentialPattern.test(credential))
      throw new Error('Native request is invalid.');
    if (accountId !== null && !account.test(accountId))
      throw new Error('Native request is invalid.');
    if (path.startsWith('/api/v1/native/journeys')) {
      if (!credential || !accountId) throw new Error('Native journey session is unavailable.');
    } else if (path.startsWith('/api/v1/native/auth/google/')) {
      if (credential || accountId) throw new Error('Native request is invalid.');
    } else if (!credential || accountId) {
      throw new Error('Native request is invalid.');
    }
    if (method === 'GET' && options.body !== undefined)
      throw new Error('Native request is invalid.');
    const payload = method === 'POST' ? JSON.stringify(options.body ?? {}) : null;
    if (payload !== null && new TextEncoder().encode(payload).length > 20 * 1024)
      throw new Error('Native request is too large.');
    let response: { status: number; body: string };
    try {
      response = await driver.request(origin, path, method, credential, accountId, payload);
    } catch {
      throw new Error('Secure server connection failed. Try again.');
    }
    if (
      !Number.isInteger(response.status) ||
      response.status < 200 ||
      response.status > 599 ||
      typeof response.body !== 'string' ||
      new TextEncoder().encode(response.body).length > 64 * 1024
    )
      throw new Error('Native server response is invalid.');
    if (response.status < 200 || response.status >= 300)
      throw new NativeHttpStatus(response.status);
    if (response.status === 204) return null;
    try {
      return JSON.parse(response.body) as unknown;
    } catch {
      throw new Error('Native server response is invalid.');
    }
  }
  return { request, configured: origin !== null };
}
