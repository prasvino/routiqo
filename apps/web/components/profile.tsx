'use client';
import Link from 'next/link';
import { useState } from 'react';
import { ShieldCheck, Bookmark, ArrowRight, Trash2 } from 'lucide-react';
import { destinations, type Destination } from '@routiqo/shared';
import { usePlanning } from './planning-provider';
import { DestinationCard } from './destination-card';
import { DestinationDialog } from './destination-dialog';
import { PlanDialog } from './plan-dialog';
import { Modal } from './modal';
import { PlanningBackupControls } from './planning-backup';
import { AccountControls } from './account-controls';
export function Profile() {
  const { state, ready, clear, error } = usePlanning();
  const [confirm, setConfirm] = useState(false);
  const [selected, setSelected] = useState<Destination | null>(null);
  const [planning, setPlanning] = useState<string | null>(null);
  const saved = destinations.filter((place) => state.saved.includes(place.id));
  return (
    <div className="page">
      <section className="page-heading">
        <div>
          <span className="eyebrow">A SPACE OF YOUR OWN</span>
          <h1>Your places. Your pace.</h1>
          <p>A little inspiration for wherever comes next.</p>
        </div>
      </section>
      <AccountControls />
      <section id="saved" className="saved-section">
        <div className="section-heading">
          <div>
            <span className="eyebrow">FOR ANOTHER DAY</span>
            <h2>Saved places</h2>
          </div>
          <span className="result-count">{saved.length} saved</span>
        </div>
        {!ready ? (
          <div className="skeleton" />
        ) : saved.length ? (
          <div className="destination-grid">
            {saved.map((place) => (
              <DestinationCard key={place.id} place={place} onOpen={() => setSelected(place)} />
            ))}
          </div>
        ) : (
          <div className="empty-state compact">
            <Bookmark size={28} />
            <h3>Something will catch your eye.</h3>
            <p>Save a place while exploring and find it here.</p>
            <Link className="text-link" href="/explore">
              Find a little inspiration
              <ArrowRight size={17} />
            </Link>
          </div>
        )}
      </section>
      <section className="settings-section">
        <h2>Privacy & local data</h2>
        <Link className="settings-row" href="/privacy">
          <ShieldCheck size={21} />
          <div>
            <strong>Your privacy comes first</strong>
            <p>What this preview stores, and what it doesn’t.</p>
          </div>
          <ArrowRight size={18} />
        </Link>
        <PlanningBackupControls />
        <button className="settings-row" onClick={() => setConfirm(true)}>
          <Trash2 size={21} />
          <div>
            <strong>Clear this device’s planning data</strong>
            <p>Remove local plans and saved places.</p>
          </div>
          <ArrowRight size={18} />
        </button>
      </section>
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
      {confirm && (
        <Modal title="Clear local planning data?" onClose={() => setConfirm(false)}>
          <p className="modal-intro">
            This removes all journey plans and saved destinations on this browser. This cannot be
            undone.
          </p>
          {error && (
            <p role="alert" className="form-error">
              {error}
            </p>
          )}
          <div className="detail-actions">
            <button className="button secondary" onClick={() => setConfirm(false)}>
              Keep my data
            </button>
            <button
              className="button danger"
              onClick={() => {
                if (clear()) setConfirm(false);
              }}
            >
              Clear local data
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
