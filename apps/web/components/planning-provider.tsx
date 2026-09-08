'use client';
import { updateStoredPlans } from '../lib/planning-storage';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  emptyPlanningState,
  readPlanningState,
  upsertPlan,
  toggleSaved,
  createPlanningBackup,
  mergePlanningBackup,
  type RestoreSummary,
  type PlanningState,
  type JourneyPlan,
} from '@routiqo/shared';
const KEY = 'routiqo.planning.v1';
interface PlanningContextValue {
  state: PlanningState;
  ready: boolean;
  error: string;
  message: string;
  savePlan: (plan: JourneyPlan) => void;
  removePlan: (id: string) => void;
  saveDestination: (id: string) => void;
  clear: () => boolean;
  exportBackup: () => string;
  restoreBackup: (data: PlanningState) => RestoreSummary;
}
const PlanningContext = createContext<PlanningContextValue | null>(null);
export function PlanningProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<PlanningState>(emptyPlanningState);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  useEffect(() => {
    const read = () => {
      try {
        setState(readPlanningState(localStorage.getItem(KEY)));
        setError('');
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Local storage is unavailable.');
      } finally {
        setReady(true);
      }
    };
    read();
    const sync = (event: StorageEvent) => {
      if (event.key === KEY || event.key === null) read();
    };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, []);
  function update(transform: (current: PlanningState) => PlanningState, notice: string) {
    if (!ready) throw new Error('Your saved plans are still loading.');
    try {
      const next = updateStoredPlans(localStorage, KEY, transform);
      setState(next);
      setError('');
      setMessage(notice);
    } catch (e) {
      const detail =
        e instanceof Error ? e.message : 'Your changes could not be saved on this device.';
      setError(detail);
      throw new Error(detail);
    }
  }
  return (
    <PlanningContext.Provider
      value={{
        state,
        ready,
        error,
        message,
        savePlan: (plan) =>
          update((current) => upsertPlan(current, plan), 'Journey plan saved on this device.'),
        removePlan: (id) =>
          update(
            (current) => ({ ...current, plans: current.plans.filter((plan) => plan.id !== id) }),
            'Journey plan removed.',
          ),
        saveDestination: (id) =>
          update((current) => toggleSaved(current, id), 'Saved places updated.'),
        exportBackup: () => {
          if (!ready) throw new Error('Saved plans are still loading.');
          return createPlanningBackup(readPlanningState(localStorage.getItem(KEY)));
        },
        restoreBackup: (data) => {
          let summary: RestoreSummary = { addedPlans: 0, keptPlans: 0, addedPlaces: 0 };
          update((current) => {
            const merged = mergePlanningBackup(current, data);
            summary = merged.summary;
            return merged.state;
          }, 'Backup restored on this device.');
          return summary;
        },
        clear: () => {
          try {
            localStorage.removeItem(KEY);
            setState(emptyPlanningState());
            setError('');
            setMessage('Local plans and saved places cleared.');
            return true;
          } catch {
            setError('Your browser could not clear local data.');
            return false;
          }
        },
      }}
    >
      {children}
    </PlanningContext.Provider>
  );
}
export function usePlanning() {
  const value = useContext(PlanningContext);
  if (!value) throw new Error('PlanningProvider is required');
  return value;
}
