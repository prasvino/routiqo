import { proxyBrowserCommunityShares, readBrowserAuthConfig } from '../../../../lib/auth-proxy';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  try {
    return await proxyBrowserCommunityShares(
      request,
      readBrowserAuthConfig(process.env),
      fetch,
      process.env.ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED === 'true',
    );
  } catch {
    return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
