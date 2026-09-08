'use client';
import Image from 'next/image';
import Link from 'next/link';
import { useState } from 'react';
import {
  ArrowRight,
  ArrowUpRight,
  Search,
  Route,
  Compass,
  ShieldCheck,
  Bookmark,
  BriefcaseBusiness,
} from 'lucide-react';
import { destinations, type Destination } from '@routiqo/shared';
import { NextPlan } from './next-plan';
import { PlanDialog } from './plan-dialog';
import { DestinationDialog } from './destination-dialog';
import { DestinationCard } from './destination-card';
import { usePlanning } from './planning-provider';
export function Home() {
  const [planning, setPlanning] = useState<string | null>(null);
  const [selected, setSelected] = useState<Destination | null>(null);
  const { state } = usePlanning();
  const featured = destinations[0]!;
  const commute = state.plans.find((plan) => plan.kind === 'commute');
  return (
    <div className="page home-page">
      <section className="page-heading">
        <div>
          <span className="eyebrow">THE ROAD IS CALLING</span>
          <h1>Where shall we go?</h1>
          <p>Your everyday route. Your next little escape.</p>
        </div>
        <button className="button primary start-top" onClick={() => setPlanning('')}>
          <Route size={18} /> Plan a journey
          <ArrowUpRight size={17} />
        </button>
      </section>
      <form action="/explore" className="search-bar">
        <Search size={21} />
        <input
          name="q"
          aria-label="Search destinations"
          placeholder="A beach, a hill town, somewhere new…"
          autoComplete="off"
        />
        <span className="search-hint">Find your next stop</span>
        <button aria-label="Search" type="submit">
          <ArrowRight size={19} />
        </button>
      </form>
      <NextPlan />
      <section className="home-feature" aria-label="Journey inspiration">
        <button className="feature-photo" onClick={() => setSelected(featured)}>
          <Image
            src={featured.image}
            alt={featured.imageAlt}
            fill
            priority
            sizes="(max-width:760px) 95vw, 60vw"
          />
          <div className="feature-shade" />
          <div className="feature-content">
            <span className="feature-label">
              THE WEEKEND EDIT <span>COASTAL ESCAPES</span>
            </span>
            <div>
              <span className="eyebrow light">CHENNAI → PONDICHERRY</span>
              <h2>
                A slower kind
                <br />
                of weekend.
              </h2>
              <p>Sea air. Small cafés. No rush.</p>
              <span className="feature-cta">
                Explore the coast{' '}
                <span>
                  <ArrowUpRight size={22} />
                </span>
              </span>
            </div>
          </div>
        </button>
        <div className="journey-aside">
          <div className="aside-icon">
            <Route size={27} />
          </div>
          <span className="eyebrow">MAKE IT YOUR JOURNEY</span>
          <h2>
            Good things <br />
            along the way.
          </h2>
          <p>A daily commute or a road less travelled. Keep your next journey in one place.</p>
          <button className="button primary full" onClick={() => setPlanning('')}>
            Let’s make a plan
            <ArrowRight size={18} />
          </button>
          <div className="aside-divider" />
          <div className="aside-note">
            <ShieldCheck size={20} />
            <span>
              Explore freely.
              <br />
              <strong>Your location stays yours.</strong>
            </span>
          </div>
        </div>
      </section>
      <section className="discover-section">
        <div className="section-heading">
          <div>
            <span className="eyebrow">A CHANGE OF SCENERY</span>
            <h2>Somewhere worth going.</h2>
          </div>
          <Link className="text-link" href="/explore">
            Explore all <ArrowRight size={17} />
          </Link>
        </div>
        <div className="destination-grid">
          {destinations.slice(1, 4).map((place) => (
            <DestinationCard key={place.id} place={place} onOpen={() => setSelected(place)} />
          ))}
        </div>
      </section>
      <section className="small-links">
        <Link href="/trips">
          <span className="small-link-icon">
            <BriefcaseBusiness size={23} />
          </span>
          <div>
            <h3>{commute ? 'Your everyday route' : 'A familiar road, a fresh start.'}</h3>
            <p>
              {commute
                ? commute.origin + ' → ' + commute.destination
                : 'Make room for your daily commute.'}
            </p>
          </div>
          <ArrowUpRight size={21} />
        </Link>
        <Link href="/profile#saved">
          <span className="small-link-icon">
            <Bookmark size={23} />
          </span>
          <div>
            <h3>Keep a little inspiration.</h3>
            <p>
              {state.saved.length
                ? state.saved.length +
                  (state.saved.length === 1
                    ? ' place saved for another day.'
                    : ' places saved for another day.')
                : 'Your saved places, ready when you are.'}
            </p>
          </div>
          <ArrowUpRight size={21} />
        </Link>
      </section>
      <div className="quiet-promise">
        <Compass size={20} />
        <span>More than getting there. A better way to be on your way.</span>
      </div>
      {selected && (
        <DestinationDialog
          place={selected}
          onClose={() => setSelected(null)}
          onPlan={() => {
            setPlanning(selected.name);
            setSelected(null);
          }}
        />
      )}
      {planning !== null && <PlanDialog destination={planning} onClose={() => setPlanning(null)} />}
    </div>
  );
}
