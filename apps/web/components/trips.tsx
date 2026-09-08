'use client';
import { useState } from 'react';
import {
  Route,
  Plus,
  ArrowRight,
  CalendarDays,
  Clock3,
  BriefcaseBusiness,
  Compass,
  Pencil,
  Trash2,
  ShieldCheck,
} from 'lucide-react';
import { recurrenceLabel, schedulePlans, departureLabel, type JourneyPlan } from '@routiqo/shared';
import { usePlanning } from './planning-provider';
import { PlanDialog } from './plan-dialog';
import { useLocalClock } from './use-local-clock';
import { Modal } from './modal';
export function Trips() {
  const now = useLocalClock();
  const { state, ready, removePlan } = usePlanning();
  const [edit, setEdit] = useState<JourneyPlan | 'new' | null>(null);
  const [remove, setRemove] = useState<JourneyPlan | null>(null);
  const [error, setError] = useState('');
  return (
    <div className="page">
      <section className="page-heading">
        <div>
          <span className="eyebrow">YOUR NEXT CHAPTER</span>
          <h1>Journeys to look forward to.</h1>
          <p>Small plans for the everyday. Big ideas for the weekend.</p>
        </div>
        <button className="button primary" onClick={() => setEdit('new')}>
          <Plus size={18} /> New plan
        </button>
      </section>
      <div className="local-strip">
        <ShieldCheck size={18} />
        <span>
          Plans use your device’s local time. No reminders or cloud sync. Earlier plans remain below
          upcoming departures.
        </span>
      </div>
      {!ready || !now ? (
        <div className="skeleton" aria-label="Loading saved plans" />
      ) : state.plans.length ? (
        <div className="plan-list">
          {schedulePlans(state.plans, now).map(({ plan, departure }) => (
            <article className="plan-item" key={plan.id}>
              <div className="plan-symbol">
                {plan.kind === 'commute' ? <BriefcaseBusiness size={24} /> : <Compass size={24} />}
              </div>
              <div className="plan-info">
                <span className="eyebrow">
                  {plan.kind === 'commute' ? 'DAILY COMMUTE' : 'TRIP / TRAVEL'}
                </span>
                <h2>
                  {plan.origin}
                  <ArrowRight size={20} />
                  {plan.destination}
                </h2>
                <p className="departure-status">{departureLabel(departure, now)}</p>
                <div className="plan-meta">
                  <span>
                    <CalendarDays size={15} />
                    {plan.kind === 'commute' ? 'Repeats from ' : 'Planned for '}
                    {new Intl.DateTimeFormat('en-IN', {
                      day: 'numeric',
                      month: 'short',
                      year: 'numeric',
                    }).format(new Date(plan.date + 'T00:00:00'))}
                  </span>
                  <span>
                    <Clock3 size={15} />
                    {plan.time}
                  </span>
                  {plan.kind === 'commute' && <span>{recurrenceLabel(plan)}</span>}
                </div>
                {plan.notes && <p className="plan-notes">{plan.notes}</p>}
              </div>
              <div className="plan-actions">
                <button
                  className="icon-button"
                  aria-label={'Edit plan to ' + plan.destination}
                  onClick={() => setEdit(plan)}
                >
                  <Pencil size={18} />
                </button>
                <button
                  className="icon-button"
                  aria-label={'Remove plan to ' + plan.destination}
                  onClick={() => setRemove(plan)}
                >
                  <Trash2 size={18} />
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="empty-state trips-empty">
          <div className="empty-route">
            <Route size={42} />
          </div>
          <span className="eyebrow">EVERY JOURNEY STARTS SOMEWHERE</span>
          <h2>Your next one starts here.</h2>
          <p>
            Plan a trip or save a recurring commute.
            <br />A little preparation, a lot to look forward to.
          </p>
          <button className="button primary" onClick={() => setEdit('new')}>
            Plan your first journey
            <ArrowRight size={18} />
          </button>
        </div>
      )}
      <section className="journal-teaser">
        <span className="eyebrow">FURTHER DOWN THE ROAD</span>
        <h2>The places become the memories.</h2>
        <p>
          Trip journals will bring completed journeys, stops, and favourite moments together. For
          now, let’s plan what comes next.
        </p>
      </section>
      {edit && (
        <PlanDialog {...(edit === 'new' ? {} : { plan: edit })} onClose={() => setEdit(null)} />
      )}
      {remove && (
        <Modal
          title="Remove this plan?"
          onClose={() => {
            setRemove(null);
            setError('');
          }}
        >
          <p className="modal-intro">
            {remove.origin} → {remove.destination} will be removed from this device.
          </p>
          {error && (
            <p role="alert" className="form-error">
              {error}
            </p>
          )}
          <div className="detail-actions">
            <button className="button secondary" onClick={() => setRemove(null)}>
              Keep plan
            </button>
            <button
              className="button danger"
              onClick={() => {
                try {
                  removePlan(remove.id);
                  setRemove(null);
                } catch (e) {
                  setError(e instanceof Error ? e.message : 'Could not remove plan.');
                }
              }}
            >
              Remove plan
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
