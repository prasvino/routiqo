import { proxyBrowserPlaceSearch, readBrowserAuthConfig } from '../../../../../lib/auth-proxy';
export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
export async function POST(request: Request) {
  try {
    return await proxyBrowserPlaceSearch(request, readBrowserAuthConfig(process.env));
  } catch {
    return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
