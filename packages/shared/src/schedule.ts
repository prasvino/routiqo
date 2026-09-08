import { dayLabels, localDate, type JourneyPlan } from './planning';

export interface ScheduledPlan {
  plan: JourneyPlan;
  departure: Date | null;
}

/** Local wall-clock schedule, not a timezone-aware booking or notification. */
export function nextDeparture(plan: JourneyPlan, now: Date): Date | null {
  const cutoff = new Date(now);
  cutoff.setSeconds(0, 0);
  if (!Number.isFinite(cutoff.getTime())) return null;
  const [hours, minutes] = plan.time.split(':').map(Number);
  const start = new Date(plan.date + 'T00:00:00');
  if (hours === undefined || minutes === undefined || !Number.isFinite(start.getTime()))
    return null;
  const candidate = new Date(
    plan.kind === 'commute' && localDate(now) > plan.date ? localDate(now) + 'T00:00:00' : start,
  );
  // Two weeks also cover a selected weekday skipped by a daylight-saving gap.
  for (let offset = 0; offset < (plan.kind === 'commute' ? 15 : 1); offset++) {
    const day = new Date(candidate);
    day.setDate(day.getDate() + offset);
    const expectedDate = localDate(day);
    day.setHours(hours, minutes, 0, 0);
    const validTime =
      localDate(day) === expectedDate && day.getHours() === hours && day.getMinutes() === minutes;
    if (validTime && day >= cutoff && (plan.kind === 'trip' || plan.days.includes(day.getDay())))
      return day;
  }
  return null;
}

export function schedulePlans(plans: readonly JourneyPlan[], now: Date): ScheduledPlan[] {
  return plans
    .map((plan) => ({ plan, departure: nextDeparture(plan, now) }))
    .sort((a, b) => {
      if (a.departure && b.departure)
        return a.departure.getTime() - b.departure.getTime() || a.plan.id.localeCompare(b.plan.id);
      if (a.departure) return -1;
      if (b.departure) return 1;
      return (
        (b.plan.date + b.plan.time).localeCompare(a.plan.date + a.plan.time) ||
        a.plan.id.localeCompare(b.plan.id)
      );
    });
}

export function departureLabel(departure: Date | null, now: Date): string {
  if (!departure) return 'Planned departure has passed';
  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);
  const date =
    localDate(departure) === localDate(now)
      ? 'Today'
      : localDate(departure) === localDate(tomorrow)
        ? 'Tomorrow'
        : new Intl.DateTimeFormat('en-IN', {
            weekday: 'short',
            day: 'numeric',
            month: 'short',
            year: 'numeric',
          }).format(departure);
  return (
    date +
    ' · ' +
    String(departure.getHours()).padStart(2, '0') +
    ':' +
    String(departure.getMinutes()).padStart(2, '0')
  );
}

export function recurrenceLabel(plan: JourneyPlan): string {
  return plan.kind === 'commute'
    ? [...plan.days]
        .sort((a, b) => a - b)
        .map((day) => dayLabels[day])
        .join(', ')
    : 'One-time trip';
}
