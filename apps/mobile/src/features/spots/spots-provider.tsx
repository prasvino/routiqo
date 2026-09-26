import { createContext, useContext, useEffect, useMemo, useRef, type ReactNode } from 'react';
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
  const store = useMemo(
    () =>
      enabled
        ? createSpotCatalogStore({
            load: () => readCachedSpotCatalog(db),
            save: (etag, payload, fetchedAt) =>
              replaceCachedSpotCatalog(db, etag, payload, fetchedAt),
            clear: () => clearCachedSpotCatalog(db),
            fetch: (ifNoneMatch, signal) => fetchRef.current(ifNoneMatch, signal),
            now: Date.now,
          })
        : null,
    [db],
  );
  useEffect(() => {
    if (!store) return;
    void store.load();
    return () => store.dispose();
  }, [store]);
  useEffect(() => {
    if (store && session.accountId && session.online) void store.refresh();
  }, [store, session.accountId, session.online]);
  return <Context.Provider value={store}>{children}</Context.Provider>;
}

/** The catalog store, or null when the Spots flag is off. */
export const useSpotCatalogStore = () => useContext(Context);
