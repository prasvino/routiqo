import { ModeratorWorkspace } from '../components/moderator-workspace';
import { readAdminProxyConfig } from '../lib/admin-proxy';

export const dynamic = 'force-dynamic';

export default function Page() {
  let config;
  try {
    config = readAdminProxyConfig(process.env);
  } catch {
    config = null;
  }
  if (!config)
    return (
      <main className="disabled-workspace">
        <span className="logo">routiqo.</span>
        <h1>Moderation workspace unavailable</h1>
        <p>V3 moderator access is disabled or staging configuration is incomplete.</p>
      </main>
    );
  return (
    <ModeratorWorkspace
      clientId={config.googleClientId}
      grantAdminEnabled={config.grantAdminEnabled}
    />
  );
}
