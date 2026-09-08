'use client';
import { useState, type FormEvent } from 'react';
import { ArrowRight, BriefcaseBusiness, Compass, ShieldCheck } from 'lucide-react';
import {
  dayLabels,
  localDate,
  destinations,
  type JourneyKind,
  type JourneyPlan,
} from '@routiqo/shared';
import { usePlanning } from './planning-provider';
import { Modal } from './modal';
export function PlanDialog({
  onClose,
  destination = '',
  plan,
}: {
  onClose: () => void;
  destination?: string;
  plan?: JourneyPlan;
}) {
  const { savePlan, ready } = usePlanning();
  const [kind, setKind] = useState<JourneyKind>(plan?.kind ?? 'trip');
  const [days, setDays] = useState<number[]>(plan?.days ?? [1, 2, 3, 4, 5]);
  const [error, setError] = useState('');
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    try {
      savePlan({
        id: plan?.id ?? crypto.randomUUID(),
        kind,
        origin: String(data.get('origin')).trim(),
        destination: String(data.get('destination')).trim(),
        date: String(data.get('date')),
        time: String(data.get('time')),
        days: kind === 'commute' ? days : [],
        notes: String(data.get('notes')).trim(),
        createdAt: plan?.createdAt ?? new Date().toISOString(),
      });
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Please check your journey.');
    }
  }
  return (
    <Modal title={plan ? 'Edit your journey' : 'Where are you heading?'} onClose={onClose}>
      <p className="modal-intro">A familiar road or somewhere new. Start with a plan.</p>
      <div className="segmented" aria-label="Journey type">
        <button type="button" aria-pressed={kind === 'trip'} onClick={() => setKind('trip')}>
          <Compass size={19} /> Trip / travel
        </button>
        <button type="button" aria-pressed={kind === 'commute'} onClick={() => setKind('commute')}>
          <BriefcaseBusiness size={19} /> Daily commute
        </button>
      </div>
      <form onSubmit={submit} className="plan-form">
        <label>
          Starting from
          <input
            name="origin"
            defaultValue={plan?.origin ?? 'Chennai'}
            placeholder="City or area"
            maxLength={100}
            required
            autoComplete="off"
          />
        </label>
        <label>
          Going to
          <input
            name="destination"
            defaultValue={plan?.destination ?? destination}
            placeholder="Choose a destination or area"
            list="destinations"
            maxLength={100}
            required
            autoComplete="off"
          />
          <datalist id="destinations">
            {destinations.map((place) => (
              <option key={place.id} value={place.name} />
            ))}
          </datalist>
        </label>
        <div className="form-row">
          <label>
            {kind === 'commute' ? 'First departure' : 'Departure date'}
            <input type="date" name="date" defaultValue={plan?.date ?? localDate()} required />
          </label>
          <label>
            Time
            <input type="time" name="time" defaultValue={plan?.time ?? '08:00'} required />
          </label>
        </div>
        {kind === 'commute' && (
          <fieldset className="days-field">
            <legend>Repeat on</legend>
            <div className="days">
              {dayLabels.map((day, index) => (
                <button
                  type="button"
                  key={day}
                  aria-pressed={days.includes(index)}
                  onClick={() =>
                    setDays((current) =>
                      current.includes(index)
                        ? current.filter((item) => item !== index)
                        : [...current, index].sort(),
                    )
                  }
                >
                  {day}
                </button>
              ))}
            </div>
          </fieldset>
        )}
        <label>
          Anything to remember <span className="optional">Optional</span>
          <textarea
            name="notes"
            defaultValue={plan?.notes ?? ''}
            maxLength={500}
            rows={2}
            placeholder="A breakfast stop, a place to visit…"
          />
        </label>
        <div className="local-note">
          <ShieldCheck size={18} />
          <p>
            Saved only on this device. This creates a plan, not a live journey or departure
            reminder.
          </p>
        </div>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button className="button primary full" disabled={!ready} type="submit">
          {plan ? 'Save changes' : 'Save journey plan'}
          <ArrowRight size={18} />
        </button>
      </form>
    </Modal>
  );
}
