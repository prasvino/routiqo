import { proxyAdminRequest, readAdminProxyConfig } from '../../../../lib/admin-proxy';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

async function handle(request: Request, context: { params: Promise<{ path?: string[] }> }) {
  let config;
  try {
    config = readAdminProxyConfig(process.env);
  } catch {
    return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
  const { path = [] } = await context.params;
  return proxyAdminRequest(request, path, config);
}

export { handle as GET, handle as POST };
