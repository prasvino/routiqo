import { proxyBrowserAuth, readBrowserAuthConfig } from '../../../../../lib/auth-proxy';
export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
async function handle(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const path = (await context.params).path.join('/');
  try {
    const config = readBrowserAuthConfig(process.env);
    if (path === 'config' && request.method === 'GET') {
      return Response.json(
        { enabled: Boolean(config), clientId: config?.clientId ?? null },
        { headers: { 'Cache-Control': 'no-store' } },
      );
    }
    return await proxyBrowserAuth(request, path, config);
  } catch {
    return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
export const GET = handle;
export const POST = handle;
