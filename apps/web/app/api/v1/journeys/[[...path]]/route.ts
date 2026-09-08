import { proxyBrowserJourneys, readBrowserAuthConfig } from '../../../../../lib/auth-proxy';
export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
async function handle(request: Request, context: { params: Promise<{ path?: string[] }> }) {
  try {
    return await proxyBrowserJourneys(
      request,
      (await context.params).path ?? [],
      readBrowserAuthConfig(process.env),
    );
  } catch {
    return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
export const GET = handle;
export const POST = handle;
