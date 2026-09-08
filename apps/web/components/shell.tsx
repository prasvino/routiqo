'use client';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Compass, House, Route, UserRound, ShieldCheck, ArrowUpRight, MapPin } from 'lucide-react';
import { usePlanning } from './planning-provider';
const nav = [
  { href: '/', label: 'Home', icon: House },
  { href: '/explore', label: 'Explore', icon: Compass },
  { href: '/trips', label: 'Trips', icon: Route },
  { href: '/profile', label: 'Profile', icon: UserRound },
];
export function Brand() {
  return (
    <Link href="/" className="brand" aria-label="Routiqo home">
      <span className="brand-icon">
        <Route size={25} strokeWidth={2} />
      </span>
      routiqo<span className="brand-dot">.</span>
    </Link>
  );
}
export function Shell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const { message, error } = usePlanning();
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <aside className="sidebar">
        <Brand />
        <p className="sidebar-caption">A little more in every route.</p>
        <nav aria-label="Main navigation">
          {nav.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              aria-current={path === href ? 'page' : undefined}
              className={path === href ? 'nav-item active' : 'nav-item'}
            >
              <Icon size={21} />
              <span>{label}</span>
              {path === href && <span className="active-dot" />}
            </Link>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="privacy-mini">
            <ShieldCheck size={22} />
            <div>
              <strong>Your journey. Your privacy.</strong>
              <p>Explore at your own pace.</p>
            </div>
          </div>
          <Link className="text-link" href="/privacy">
            Our privacy promise <ArrowUpRight size={15} />
          </Link>
          <span className="edition">THE FIRST MILE · EARLY PREVIEW</span>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="mobile-brand">
            <Brand />
          </div>
          <span className="topbar-label">GOOD ROADS. BETTER DISCOVERIES.</span>
          <span className="location-label">
            <MapPin size={15} /> Chennai & beyond
          </span>
          <Link href="/profile" className="avatar-button" aria-label="Your profile">
            <UserRound size={19} />
          </Link>
        </header>
        {error && (
          <div className="error-banner" role="alert">
            {error} <Link href="/profile">Manage local data</Link>
          </div>
        )}
        <main id="main" tabIndex={-1}>
          {children}
        </main>
        <footer className="footer">
          <span>Made for the journey, not just the destination.</span>
          <Link href="/privacy">
            <ShieldCheck size={14} /> Private by design
          </Link>
        </footer>
      </div>
      <nav className="bottom-nav" aria-label="Mobile navigation">
        {nav.map(({ href, label, icon: Icon }) => (
          <Link key={href} href={href} aria-current={path === href ? 'page' : undefined}>
            <Icon size={21} />
            <span>{label}</span>
          </Link>
        ))}
      </nav>
      <div className="sr-only" role="status" aria-live="polite">
        {message}
      </div>
    </>
  );
}
