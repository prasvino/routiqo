import { proxyBrowserPublicIntents, readBrowserAuthConfig } from '../../../../lib/auth-proxy';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request: Request) {
  try {
    return await proxyBrowserPublicIntents(
      request,
      readBrowserAuthConfig(process.env),
      fetch,
      process.env.ROUTIQO_PUBLIC_SIGNAL_INTENT_API_ENABLED === 'true',
    );
  } catch {
    return new Response(null, { status: 503, headers: { 'Cache-Control': 'no-store' } });
  }
}
