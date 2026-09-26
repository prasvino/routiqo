import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useSQLiteContext } from 'expo-sqlite';
import { useNativeAccount } from '../../auth/native-account-provider';
import {
  clearCachedSpotCatalog,
  readCachedSpotCatalog,
  replaceCachedSpotCatalog,
} from '../../storage/spot-catalog';
import { createSpotCatalogStore, type SpotCatalogStore } from './spot-catalog-store';
import { spotsEnabled } from './spots-model';

const enabled = spotsEnabled();
const Context = createContext<SpotCatalogStore | null>(null);

/**
 * Holds the device Spot catalog. With the Spots flag off nothing is created, read or requested.
 * Refreshes once per signed-in online session; Journey mode refreshes again when opened.
 */
export function SpotsProvider({ children }: { children: ReactNode }) {
  const db = useSQLiteContext();
  const session = useNativeAccount();
  const fetchRef = useRef(session.fetchSpotCatalog);
  fetchRef.current = session.fetchSpotCatalog;
  // Created in an effect so a remount (or StrictMode's double effects) gets a live store.
  const [store, setStore] = useState<SpotCatalogStore | null>(null);
  useEffect(() => {
    if (!enabled) return;
    const created = createSpotCatalogStore({
      load: () => readCachedSpotCatalog(db),
      save: (etag, payload, fetchedAt) => replaceCachedSpotCatalog(db, etag, payload, fetchedAt),
      clear: () => clearCachedSpotCatalog(db),
      fetch: (ifNoneMatch, signal) => fetchRef.current(ifNoneMatch, signal),
      now: Date.now,
    });
    setStore(created);
    void created.load();
    return () => {
      created.dispose();
      setStore(null);
    };
  }, [db]);
  useEffect(() => {
    if (store && session.accountId && session.online) void store.refresh();
  }, [store, session.accountId, session.online]);
  return <Context.Provider value={store}>{children}</Context.Provider>;
}

/** The catalog store, or null when the Spots flag is off. */
export const useSpotCatalogStore = () => useContext(Context);
