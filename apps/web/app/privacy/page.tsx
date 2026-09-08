import Link from 'next/link';
import { ShieldCheck, ArrowLeft, MapPinOff, HardDrive, UserRound } from 'lucide-react';
export const metadata = { title: 'Privacy' };
export default function Page() {
  return (
    <div className="page privacy-page">
      <Link href="/profile" className="text-link">
        <ArrowLeft size={16} /> Your space
      </Link>
      <span className="privacy-emblem">
        <ShieldCheck size={34} />
      </span>
      <span className="eyebrow">BUILT AROUND YOUR TRUST</span>
      <h1>
        Your journey.
        <br />
        Your privacy.
      </h1>
      <p className="privacy-lede">
        A useful journey should never come at the cost of being tracked by strangers.
      </p>
      <div className="privacy-section">
        <MapPinOff size={24} />
        <div>
          <h2>No location collection in this preview.</h2>
          <p>
            Chennai is the starting context for our curated collection. We do not request GPS
            access, publish your position, or show live traveller presence.
          </p>
        </div>
      </div>
      <div className="privacy-section">
        <HardDrive size={24} />
        <div>
          <h2>Your plans stay on this device.</h2>
          <p>
            Journey details and saved destination IDs are stored in your browser. They are not
            synced to an account or shared with other travellers. Avoid entering private addresses
            or sensitive information in plan notes.
          </p>
        </div>
      </div>
      <div className="privacy-section">
        <UserRound size={24} />
        <div>
          <h2>You’re in control.</h2>
          <p>
            Remove individual plans from Trips, unsave destinations, or clear all planning data from
            your Profile. Clearing your browser storage also removes these plans. You can download
            and restore a planning backup in Profile. Backup files are not encrypted and include
            your route names, dates and notes. Clearing browser data does not delete files you have
            downloaded; manage those copies separately.
          </p>
        </div>
      </div>
      <p className="fine-print">
        Routiqo serves local image assets; no analytics integration is enabled. Live journeys and
        traveller presence are not available in this preview.
      </p>
      <div className="privacy-section">
        <ShieldCheck size={24} />
        <div>
          <h2>Optional Google sign-in.</h2>
          <p>
            When sign-in is enabled, opening it loads Google’s sign-in service. Routiqo verifies
            Google’s response and stores a Google account identifier linked to your Routiqo account.
            It does not save your Google password or use your local plans for sign-in.
          </p>
          <p>
            Secure account cookies keep you signed in. Sessions expire after 15 minutes without
            renewal, or at most 12 hours after signing in. Sign out revokes that sign-in session and
            its replacements. Session records are removed by periodic cleanup after that maximum
            lifetime.
          </p>
          <p>
            Delete your Routiqo account from Profile to remove its server records and sign out all
            devices. You’ll need a recent Google sign-in and explicit confirmation. Local plans,
            downloaded backups and your Google account remain separate; deleting Routiqo does not
            remove those copies or your Google account.
          </p>
        </div>
      </div>
      <Link href="/profile" className="button primary">
        Manage local data
        <ArrowLeft className="rotate" size={17} />
      </Link>
    </div>
  );
}
