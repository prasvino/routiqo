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
const journalPath = new RegExp(`^/api/v1/native/journeys/${journeyId}/journal$`);
const consentPath = new RegExp(`^/api/v1/native/journeys/${journeyId}/consent$`);
const routePath = '/api/v1/native/routes';
const placePath = '/api/v1/native/routes/places';
const nativePath = new RegExp(
  `^/api/v1/native/(?:auth/(?:google/(?:challenge|exchange)|session(?:/renew)?|logout|account/delete)|journeys(?:/history|/${journeyId}(?:/(?:complete|journal|consent))?)?|routes(?:/places)?)$`,
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
    if (nativePath.exec(path)?.[0] !== path || path.includes('?') || path.includes('#'))
      throw new Error('Native request is invalid.');
    if (path === '/api/v1/native/journeys/history' && method !== 'POST')
      throw new Error('Native request is invalid.');
    if ((path === routePath || path === placePath) && method !== 'POST')
      throw new Error('Native request is invalid.');
    const credential = options.credential ?? null;
    const accountId = options.accountId ?? null;
    if (credential !== null && !credentialPattern.test(credential))
      throw new Error('Native request is invalid.');
    if (accountId !== null && !account.test(accountId))
      throw new Error('Native request is invalid.');
    if (path.startsWith('/api/v1/native/journeys') || path === routePath || path === placePath) {
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
      new TextEncoder().encode(response.body).length >
        (journalPath.test(path) ? 32 : path === routePath ? 1024 : path === placePath ? 256 : 64) *
          1024
    )
      throw new Error('Native server response is invalid.');
    if (response.status < 200 || response.status >= 300)
      throw new NativeHttpStatus(response.status);
    if (
      (journalPath.test(path) ||
        consentPath.test(path) ||
        path === routePath ||
        path === placePath) &&
      response.status !== 200
    )
      throw new Error('Native server response is invalid.');
    if (consentPath.test(path) || path === routePath || path === placePath) {
      const encoded = new TextEncoder().encode(response.body);
      if (new TextDecoder('utf-8', { fatal: true }).decode(encoded) !== response.body)
        throw new Error('Native server response is invalid.');
    }
    if (response.status === 204) return null;
    try {
      return JSON.parse(response.body) as unknown;
    } catch {
      throw new Error('Native server response is invalid.');
    }
  }
  return { request, configured: origin !== null };
}
