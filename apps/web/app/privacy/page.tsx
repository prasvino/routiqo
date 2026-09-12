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
          <h2>Location only when you choose.</h2>
          <p>
            Chennai is the starting context for our curated collection, not a detected location.
            Route planning can request one browser location reading when you choose “Use my current
            location.” Routiqo does not watch your location in the background, publish your
            position, or show live traveller presence.
          </p>
          <p>
            When route planning is enabled, submitting a place search sends that text to Routiqo’s
            Photon search service. Calculating a route sends your selected endpoints to Routiqo’s
            Valhalla routing service, including a location reading if you chose one. These services
            run on infrastructure configured for Routiqo. Place results and route estimates stay in
            the current view; they are not saved to plans, planning backups or Routiqo’s database.
          </p>
          <p>
            Choosing “Show map” loads MapLibre and requests map resources from this site for the
            area you view. Browser map caches, including any left by earlier versions, can remain
            after sign-out or account deletion; clear site data in your browser to remove them.
            Routiqo does not save your calculated route or download an offline map package.
          </p>
        </div>
      </div>
      <div className="privacy-section">
        <HardDrive size={24} />
        <div>
          <h2>Your plans stay on this device.</h2>
          <p>
            Planning drafts and saved destination IDs are stored in your browser. They are not
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
        Routiqo serves local image assets and has no separate analytics integration. Optional map
        display uses MapLibre with a configured map service. Traveller presence and public location
        sharing are not available.
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
            Starting or finishing an account journey saves its type, status and timestamps on
            Routiqo’s server. Pending actions and recent confirmations are also kept in an
            account-specific cache on this device so they can survive a lost connection. Signing out
            preserves that saved work; deleting the account removes its local journey cache on the
            device where you delete it. Planning drafts remain separate.
          </p>
          <p>
            Completed trip journals can keep a title and notes in an account-specific browser draft.
            Choosing “Save to account” sends that content to Routiqo’s server. Journals are private;
            they are not shared with other travellers or included in planning backups. Signing out
            keeps local drafts so you can return to them.
          </p>
          <p>
            Delete your Routiqo account from Profile to remove its server records and sign out all
            devices. You’ll need a recent Google sign-in and explicit confirmation. Local plans,
            downloaded backups and your Google account remain separate; deleting Routiqo does not
            remove those copies or your Google account.
          </p>
          <p>
            Account deletion also clears its journey and journal data from this browser. Copies in
            other browsers or on offline devices may remain until you clear their site data.
            Clearing site data removes unsent journal drafts, so save any work you want to keep
            first.
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
