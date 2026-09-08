export interface BrowserAuthConfig {
  clientId: string;
  upstream: string;
  origin: string;
}
const localHosts = new Set(['localhost', '127.0.0.1', '[::1]']);
function safeOrigin(value: string): string {
  const url = new URL(value);
  if (
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/' ||
    (url.protocol !== 'https:' && !(url.protocol === 'http:' && localHosts.has(url.hostname)))
  )
    throw new Error('Invalid authentication configuration.');
  return url.origin;
}
export function readBrowserAuthConfig(
  env: Record<string, string | undefined>,
): BrowserAuthConfig | null {
  const clientId = env.ROUTIQO_GOOGLE_CLIENT_ID;
  const upstream = env.ROUTIQO_AUTH_API_URL;
  const origin = env.ROUTIQO_WEB_ORIGIN;
  if (!clientId || !upstream || !origin) return null;
  if (!/^[A-Za-z0-9-]{1,200}\.apps\.googleusercontent\.com$/.test(clientId))
    throw new Error('Invalid authentication configuration.');
  return { clientId, upstream: safeOrigin(upstream), origin: safeOrigin(origin) };
}
const routes: Record<string, string> = {
  csrf: 'GET',
  'google/challenge': 'POST',
  'google/exchange': 'POST',
  session: 'GET',
  'session/renew': 'POST',
  'account/delete': 'POST',
  logout: 'POST',
};
const cookieNames = new Set([
  'routiqo_csrf',
  'routiqo_binding',
  'routiqo_session',
  '__Host-routiqo_csrf',
  '__Host-routiqo_binding',
  '__Host-routiqo_session',
]);
function failure(status: number): Response {
  return new Response(null, { status, headers: { 'Cache-Control': 'no-store' } });
}
async function boundedBody(
  stream: ReadableStream<Uint8Array> | null,
  max: number,
): Promise<Uint8Array> {
  if (!stream) return new Uint8Array();
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > max) {
        await reader.cancel();
        throw new RangeError('Body exceeds limit');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}
export async function proxyBrowserAuth(
  request: Request,
  path: string,
  config: BrowserAuthConfig | null,
  upstreamFetch: typeof fetch = fetch,
): Promise<Response> {
  if (!config) return failure(503);
  if (!Object.hasOwn(routes, path)) return failure(404);
  if (request.method !== routes[path]) return failure(405);
  if (new URL(request.url).search) return failure(400);
  return forwardBrowserRequest(request, `auth/${path}`, config, upstreamFetch);
}
const journeyId = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
export async function proxyBrowserJourneys(
  request: Request,
  path: string[],
  config: BrowserAuthConfig | null,
  upstreamFetch: typeof fetch = fetch,
): Promise<Response> {
  if (!config) return failure(503);
  const listing = path.length === 0;
  const detail = path.length === 1 && journeyId.test(path[0] ?? '');
  const complete = path.length === 2 && journeyId.test(path[0] ?? '') && path[1] === 'complete';
  if (!listing && !detail && !complete) return failure(404);
  if (
    !(listing
      ? ['GET', 'POST'].includes(request.method)
      : request.method === (detail ? 'GET' : 'POST'))
  )
    return failure(405);
  const query = new URL(request.url).searchParams;
  if (query.size && !(listing && request.method === 'GET')) return failure(400);
  for (const key of query.keys()) {
    if (!['limit', 'beforeStartedAt', 'beforeId'].includes(key) || query.getAll(key).length !== 1)
      return failure(400);
  }
  if (query.has('limit') && !/^(?:[1-9]|[1-4][0-9]|50)$/.test(query.get('limit') ?? ''))
    return failure(400);
  if (query.has('beforeId') !== query.has('beforeStartedAt')) return failure(400);
  if (query.has('beforeId')) {
    const time = query.get('beforeStartedAt') ?? '';
    if (
      !journeyId.test(query.get('beforeId') ?? '') ||
      time.length > 40 ||
      !Number.isFinite(Date.parse(time))
    )
      return failure(400);
  }
  const suffix = query.size ? `?${query.toString()}` : '';
  return forwardBrowserRequest(
    request,
    `journeys${listing ? '' : '/' + path.join('/')}${suffix}`,
    config,
    upstreamFetch,
  );
}
async function forwardBrowserRequest(
  request: Request,
  path: string,
  config: BrowserAuthConfig,
  upstreamFetch: typeof fetch,
): Promise<Response> {
  if (
    request.headers.get('sec-fetch-site') === 'cross-site' ||
    (request.method === 'POST' && request.headers.get('origin') !== config.origin)
  )
    return failure(403);
  const headers = new Headers();
  for (const name of [
    'origin',
    'x-xsrf-token',
    'content-type',
    'sec-fetch-site',
    'x-routiqo-account',
  ]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const cookies = (request.headers.get('cookie') ?? '')
    .split(';')
    .map((cookie) => cookie.trim())
    .filter((cookie) => cookieNames.has(cookie.split('=', 1)[0] ?? ''));
  if (cookies.length) headers.set('cookie', cookies.join('; '));
  let body: Uint8Array | undefined;
  if (request.method === 'POST') {
    if (headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase() !== 'application/json')
      return failure(415);
    try {
      body = await boundedBody(request.body, 20 * 1024);
    } catch {
      return failure(413);
    }
  }
  try {
    const result = await upstreamFetch(`${config.upstream}/api/v1/${path}`, {
      method: request.method,
      headers,
      ...(body ? { body: Buffer.from(body) } : {}),
      redirect: 'manual',
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    });
    if (result.status >= 300 && result.status < 400) {
      await result.body?.cancel();
      return failure(502);
    }
    const output = new Headers({
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    for (const name of ['content-type', 'retry-after']) {
      const value = result.headers.get(name);
      if (value) output.set(name, value);
    }
    for (const cookie of result.headers.getSetCookie()) {
      if (cookieNames.has(cookie.split('=', 1)[0] ?? '')) output.append('set-cookie', cookie);
    }
    const bytes = await boundedBody(result.body, 64 * 1024);
    return new Response(result.status === 204 ? null : Buffer.from(bytes), {
      status: result.status,
      headers: output,
    });
  } catch {
    return failure(503);
  }
}
