export interface AdminProxyConfig {
  origin: string;
  upstream: string;
  googleClientId: string;
  grantAdminEnabled: boolean;
}

const localHosts = new Set(['localhost', '127.0.0.1', '[::1]']);
const adminCookies = new Set([
  'routiqo_admin_binding',
  'routiqo_admin_session',
  'routiqo_admin_csrf',
  '__Host-routiqo_admin_binding',
  '__Host-routiqo_admin_session',
  '__Host-routiqo_admin_csrf',
]);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const cursor = /^[A-Za-z0-9_-]{1,256}$/;

function exactOrigin(raw: string): string {
  const url = new URL(raw);
  if (
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/' ||
    (url.protocol !== 'https:' && !(url.protocol === 'http:' && localHosts.has(url.hostname)))
  )
    throw new Error('Invalid admin origin');
  return url.origin;
}

export function readAdminProxyConfig(
  env: Record<string, string | undefined>,
): AdminProxyConfig | null {
  if (env.ROUTIQO_V3_ADMIN_ENABLED !== 'true') return null;
  const origin = env.ROUTIQO_ADMIN_ORIGIN;
  const upstream = env.ROUTIQO_ADMIN_API_ORIGIN;
  const googleClientId = env.ROUTIQO_ADMIN_GOOGLE_CLIENT_ID;
  if (!origin || !upstream || !googleClientId) throw new Error('Incomplete admin configuration');
  if (!/^[A-Za-z0-9-]{1,200}\.apps\.googleusercontent\.com$/.test(googleClientId))
    throw new Error('Invalid admin Google client');
  return {
    origin: exactOrigin(origin),
    upstream: exactOrigin(upstream),
    googleClientId,
    grantAdminEnabled: env.ROUTIQO_V3_GRANT_ADMIN_ENABLED === 'true',
  };
}

function failure(status: number): Response {
  return new Response(null, { status, headers: { 'Cache-Control': 'no-store' } });
}

function route(
  path: string[],
  search: URLSearchParams,
  method: string,
  grantAdminEnabled: boolean,
): string | null {
  if (grantAdminEnabled && path[0] === 'traffic-grants' && !search.size) {
    if (path.length === 2 && path[1] === 'me' && method === 'GET') return 'traffic-grants/me';
    if (path.length === 2 && uuid.test(path[1] ?? '') && method === 'GET')
      return `traffic-grants/${path[1]}`;
    if (
      path.length === 3 &&
      uuid.test(path[1] ?? '') &&
      (path[2] === 'issue' || path[2] === 'revoke') &&
      method === 'POST'
    )
      return `traffic-grants/${path[1]}/${path[2]}`;
  }
  if (path.length === 2 && path[0] === 'auth') {
    const action = path[1];
    if (
      ((action === 'csrf' || action === 'session') && method === 'GET') ||
      (action === 'logout' && method === 'POST')
    ) {
      if (search.size) return null;
      return `auth/${action}`;
    }
  }
  if (path.length === 3 && path[0] === 'auth' && path[1] === 'google') {
    if ((path[2] === 'challenge' || path[2] === 'exchange') && method === 'POST' && !search.size)
      return `auth/google/${path[2]}`;
  }
  if (path.length === 2 && path[0] === 'community-traffic' && path[1] === 'reports') {
    if (method !== 'GET') return null;
    if ([...search.keys()].some((key) => !['cursor', 'limit'].includes(key))) return null;
    if (search.getAll('cursor').length > 1 || search.getAll('limit').length > 1) return null;
    const value = search.get('cursor');
    if (value !== null && !cursor.test(value)) return null;
    const limit = search.get('limit');
    if (limit !== null && limit !== '20') return null;
    const next = new URLSearchParams({ limit: '20' });
    if (value !== null) next.set('cursor', value);
    return `community-traffic/reports?${next.toString()}`;
  }
  if (
    path.length === 4 &&
    path[0] === 'community-traffic' &&
    path[1] === 'reports' &&
    uuid.test(path[2] ?? '') &&
    (path[3] === 'dismiss' || path[3] === 'suppress') &&
    method === 'POST' &&
    !search.size
  )
    return `community-traffic/reports/${path[2]}/${path[3]}`;
  return null;
}

async function bounded(
  stream: ReadableStream<Uint8Array> | null,
  max: number,
  signal?: AbortSignal,
) {
  if (!stream) return new Uint8Array();
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const read = reader.read();
      let onAbort: (() => void) | undefined;
      const abort = new Promise<never>((_, reject) => {
        onAbort = () => reject(new Error('Deadline exceeded'));
        if (signal?.aborted) onAbort();
        else signal?.addEventListener('abort', onAbort, { once: true });
      });
      let item: ReadableStreamReadResult<Uint8Array>;
      try {
        item = signal ? await Promise.race([read, abort]) : await read;
      } finally {
        if (signal && onAbort) signal.removeEventListener('abort', onAbort);
      }
      const { value, done } = item;
      if (done) break;
      size += value.byteLength;
      if (size > max) {
        await reader.cancel();
        throw new RangeError('Body too large');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const output = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    output.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return output;
}

function onlyAdminCookies(raw: string | null): string | null {
  const selected = (raw ?? '')
    .split(';')
    .map((part) => part.trim())
    .filter((part) => adminCookies.has(part.split('=', 1)[0] ?? '') && !/[\r\n]/.test(part));
  return selected.length ? selected.join('; ') : null;
}

function safeSetCookie(raw: string): boolean {
  const [pair, ...attributes] = raw.split(';');
  const name = pair?.split('=', 1)[0]?.trim();
  if (!name || !adminCookies.has(name) || /[\r\n]/.test(raw)) return false;
  if (attributes.some((attribute) => /^\s*domain\s*=/i.test(attribute))) return false;
  return attributes.some((attribute) => /^\s*path\s*=\s*\/\s*$/i.test(attribute));
}

export async function proxyAdminRequest(
  request: Request,
  path: string[],
  config: AdminProxyConfig | null,
  upstreamFetch: typeof fetch = fetch,
  timeoutMs = 10_000,
): Promise<Response> {
  if (!config) return failure(404);
  const url = new URL(request.url);
  if (url.origin !== config.origin) return failure(403);
  const suffix = route(path, url.searchParams, request.method, config.grantAdminEnabled);
  if (!suffix) return failure(404);
  if (
    request.headers.get('sec-fetch-site') === 'cross-site' ||
    (request.method === 'POST' && request.headers.get('origin') !== config.origin)
  )
    return failure(403);
  const headers = new Headers({ Accept: 'application/json' });
  const cookies = onlyAdminCookies(request.headers.get('cookie'));
  if (cookies) headers.set('Cookie', cookies);
  let body: Uint8Array | undefined;
  if (request.method === 'POST') {
    if (
      request.headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase() !==
      'application/json'
    )
      return failure(415);
    const csrf = request.headers.get('x-xsrf-token');
    if (!csrf || csrf.length > 256 || !/^[A-Za-z0-9_-]+$/.test(csrf)) return failure(403);
    headers.set('X-XSRF-TOKEN', csrf);
    headers.set('Origin', config.origin);
    headers.set('Content-Type', 'application/json');
    try {
      body = await bounded(request.body, 4096);
    } catch {
      return failure(413);
    }
  }
  const deadline = AbortSignal.timeout(timeoutMs);
  try {
    const upstream = await upstreamFetch(`${config.upstream}/api/v1/admin/${suffix}`, {
      method: request.method,
      headers,
      ...(body ? { body: new Uint8Array(body).buffer } : {}),
      cache: 'no-store',
      redirect: 'manual',
      signal: deadline,
    });
    if (upstream.status >= 300 && upstream.status < 400) return failure(502);
    let result: Uint8Array;
    try {
      result = await bounded(upstream.body, 128 * 1024, deadline);
    } catch {
      return failure(502);
    }
    const responseHeaders = new Headers({ 'Cache-Control': 'no-store' });
    if (
      result.length &&
      upstream.headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase() ===
        'application/json'
    )
      responseHeaders.set('Content-Type', 'application/json');
    for (const cookie of upstream.headers.getSetCookie()) {
      if (safeSetCookie(cookie)) responseHeaders.append('Set-Cookie', cookie);
    }
    return new Response(result.length ? new Uint8Array(result).buffer : null, {
      status: upstream.status,
      headers: responseHeaders,
    });
  } catch {
    return failure(503);
  }
}
