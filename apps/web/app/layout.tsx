import type { Metadata } from 'next';
import '@routiqo/design-tokens/css';
import 'mapbox-gl/dist/mapbox-gl.css';
import './globals.css';
import { PlanningProvider } from '../components/planning-provider';
import { Shell } from '../components/shell';
import { SessionMaintenance } from '../components/session-maintenance';
export const metadata: Metadata = {
  title: { default: 'Routiqo — Every route has a little more', template: '%s · Routiqo' },
  description:
    'Discover places, save your next escape, and plan everyday journeys. Private by design.',
};
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <SessionMaintenance />
        <PlanningProvider>
          <Shell>{children}</Shell>
        </PlanningProvider>
      </body>
    </html>
  );
}
