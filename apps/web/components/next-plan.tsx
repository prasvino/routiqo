'use client';
import { useState } from 'react';
import { ArrowRight, CalendarDays, Pencil } from 'lucide-react';
import { schedulePlans, departureLabel, recurrenceLabel, type JourneyPlan } from '@routiqo/shared';
import { usePlanning } from './planning-provider';
import { useLocalClock } from './use-local-clock';
import { PlanDialog } from './plan-dialog';

export function NextPlan() {
  const { state, ready } = usePlanning();
  const now = useLocalClock();
  const [edit, setEdit] = useState<JourneyPlan | null>(null);
  const next = now ? schedulePlans(state.plans, now).find((item) => item.departure) : undefined;
  if (!ready || !now || (!next && !edit)) return null;
  return (
    <>
      {next && (
        <section className="next-plan" aria-label="Next planned departure">
          <CalendarDays size={24} aria-hidden="true" />
          <div className="next-plan-info">
            <span className="eyebrow">
              {next.plan.kind === 'commute' ? 'YOUR NEXT COMMUTE' : 'YOUR NEXT TRIP'}
            </span>
            <h2>
              {next.plan.origin}
              <ArrowRight size={18} />
              {next.plan.destination}
            </h2>
            <p>
              {departureLabel(next.departure, now)} · {recurrenceLabel(next.plan)}
            </p>
            <p className="fine-print">Device local time · No departure reminder</p>
          </div>
          <button
            className="button secondary"
            onClick={() => setEdit(next.plan)}
            aria-label={'Edit next plan to ' + next.plan.destination}
          >
            <Pencil size={16} />
            Edit plan
          </button>
        </section>
      )}
      {edit && <PlanDialog plan={edit} onClose={() => setEdit(null)} />}
    </>
  );
}
