import { afterEach, describe, expect, it, vi } from 'vitest';
import { GET as sharesGet } from '../apps/web/app/api/v1/community-shares/route';
import { GET as journeysGet } from '../apps/web/app/api/v1/journeys/[[...path]]/route';

// ADR 0065: the web proxy for community-derived public LIVE opens only for the exact value "true".
// Any missing or ambiguous value must answer 404 without contacting the core API.

const journey = '00000000-0000-4000-8000-000000000101';
const ref = '00000000-0000-4000-8000-000000000102';
const ambiguous = [undefined, '', 'false', 'TRUE', 'True', ' true', 'true ', '1', 'yes', 'on'];

function environment(flag: string | undefined) {
  vi.stubEnv('ROUTIQO_GOOGLE_CLIENT_ID', 'test-client.apps.googleusercontent.com');
  vi.stubEnv('ROUTIQO_AUTH_API_URL', 'http://127.0.0.1:18080');
  vi.stubEnv('ROUTIQO_WEB_ORIGIN', 'http://localhost:3000');
  if (flag === undefined) delete process.env.ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED;
  else vi.stubEnv('ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED', flag);
}

function request(path: string) {
  return new Request(`http://localhost:3000${path}`, {
    headers: { Origin: 'http://localhost:3000', Cookie: 'routiqo_session=synthetic' },
  });
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('community traffic proxy flag', () => {
  it.each(ambiguous.map((value) => [String(value)] as const))(
    'stays closed for %j',
    async (label) => {
      const value = label === 'undefined' ? undefined : label;
      environment(value);
      const upstream = vi.fn(async () => Response.json({}));
      vi.stubGlobal('fetch', upstream);
      const shares = await sharesGet(request('/api/v1/community-shares'));
      const feed = await journeysGet(request(`/api/v1/journeys/${journey}/community-traffic`), {
        params: Promise.resolve({ path: [journey, 'community-traffic'] }),
      });
      const report = await journeysGet(
        request(`/api/v1/journeys/${journey}/community-traffic/${ref}/reports`),
        { params: Promise.resolve({ path: [journey, 'community-traffic', ref, 'reports'] }) },
      );
      expect([shares.status, feed.status, report.status]).toEqual([404, 404, 404]);
      expect(upstream).not.toHaveBeenCalled();
    },
  );

  it('forwards only when the flag is exactly "true"', async () => {
    environment('true');
    const upstream = vi.fn(async () => Response.json({ schemaVersion: 3, moments: [] }));
    vi.stubGlobal('fetch', upstream);
    await journeysGet(request(`/api/v1/journeys/${journey}/community-traffic`), {
      params: Promise.resolve({ path: [journey, 'community-traffic'] }),
    });
    expect(upstream).toHaveBeenCalled();
  });
});
