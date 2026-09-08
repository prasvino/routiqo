import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useSQLiteContext, type SQLiteDatabase } from 'expo-sqlite';
import { emptyPlanningState, readPlanningState, type PlanningState } from '@routiqo/shared';
import { initializeJourneyOutbox } from './journey-outbox';
export async function initializeStorage(db: SQLiteDatabase) {
  await db.execAsync(
    'PRAGMA journal_mode = WAL; CREATE TABLE IF NOT EXISTS planning_state (id INTEGER PRIMARY KEY CHECK (id = 1), payload TEXT NOT NULL);',
  );
  await initializeJourneyOutbox(db);
}
interface Context {
  state: PlanningState;
  ready: boolean;
  error: string;
  update: (change: (current: PlanningState) => PlanningState) => Promise<void>;
  clear: () => Promise<void>;
  snapshot: () => Promise<PlanningState>;
}
const Context = createContext<Context | null>(null);
export function MobilePlanningProvider({ children }: { children: ReactNode }) {
  const db = useSQLiteContext();
  const [state, setState] = useState<PlanningState>(emptyPlanningState);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    db.getFirstAsync<{ payload: string }>('SELECT payload FROM planning_state WHERE id = 1')
      .then((row) => {
        if (active) setState(readPlanningState(row?.payload ?? null));
      })
      .catch(() => {
        if (active)
          setError('Saved plans could not be read. Clear local data in Profile to start again.');
      })
      .finally(() => {
        if (active) setReady(true);
      });
    return () => {
      active = false;
    };
  }, [db]);
  async function update(change: (current: PlanningState) => PlanningState) {
    if (!ready) throw new Error('Saved plans are still loading.');
    try {
      let next = state;
      await db.withExclusiveTransactionAsync(async (tx) => {
        const row = await tx.getFirstAsync<{ payload: string }>(
          'SELECT payload FROM planning_state WHERE id = 1',
        );
        next = change(readPlanningState(row?.payload ?? null));
        await tx.runAsync(
          'INSERT INTO planning_state (id,payload) VALUES (1,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload',
          JSON.stringify(next),
        );
      });
      setState(next);
      setError('');
    } catch {
      setError('Your changes could not be saved. Try again or manage local data in Profile.');
      throw new Error('Could not save on this device.');
    }
  }
  async function clear() {
    try {
      await db.runAsync('DELETE FROM planning_state WHERE id = 1');
      setState(emptyPlanningState());
      setError('');
    } catch {
      setError('Local data could not be cleared. Try again.');
    }
  }
  async function snapshot() {
    if (!ready) throw new Error('Saved plans are still loading.');
    const row = await db.getFirstAsync<{ payload: string }>(
      'SELECT payload FROM planning_state WHERE id = 1',
    );
    return readPlanningState(row?.payload ?? null);
  }
  return (
    <Context.Provider value={{ state, ready, error, update, clear, snapshot }}>
      {children}
    </Context.Provider>
  );
}
export function useMobilePlanning() {
  const context = useContext(Context);
  if (!context) throw new Error('Mobile planning provider missing');
  return context;
}
