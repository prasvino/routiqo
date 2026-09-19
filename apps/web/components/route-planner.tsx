'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { PlaceMatch, PlaceResults, RouteMode, RouteResult } from '@routiqo/shared';
import { readBrowserLocation, BrowserLocationError } from '../lib/browser-location';
import { RouteResults } from './route-results';
import {
  calculateBrowserRoute,
  searchBrowserPlaces,
  BrowserRoutingError,
  routingCoverageMessage,
} from '../lib/browser-routing';
import {
  LiveRouteBindingPanel,
  type LiveConsentAuthority,
  type RouteBindingSelection,
  type RouteBindingSelectionSnapshot,
} from './live-route-binding-panel';

type Endpoint = 'origin' | 'destination';
export interface RoutePlannerLiveBinding {
  journeyId: string;
  online: boolean;
  available: boolean;
  authority: LiveConsentAuthority;
  getAuthority: () => LiveConsentAuthority | null;
}
export function RoutePlanner({
  account,
  liveBinding,
}: {
  account: string;
  liveBinding?: RoutePlannerLiveBinding | undefined;
}) {
  return <Planner key={account} account={account} liveBinding={liveBinding} />;
}
function Planner({
  account,
  liveBinding,
}: {
  account: string;
  liveBinding?: RoutePlannerLiveBinding | undefined;
}) {
  const [query, setQuery] = useState({ origin: '', destination: '' });
  const [selected, setSelected] = useState<Partial<Record<Endpoint, PlaceMatch | undefined>>>({});
  const [matches, setMatches] = useState<Partial<Record<Endpoint, PlaceResults | undefined>>>({});
  const [attribution, setAttribution] = useState({ origin: '', destination: '' });
  const [mode, setMode] = useState<RouteMode>('driving');
  const [result, setResult] = useState<RouteResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [offline, setOffline] = useState(false);
  const [resultRevision, setResultRevision] = useState(0);
  const pending = useRef<AbortController | null>(null);
  const pendingLocalOnly = useRef(false);
  const bindingSelectionRef = useRef<RouteBindingSelectionSnapshot>({
    selection: null,
    epoch: 0,
  });
  const [bindingSelection, setBindingSelection] = useState<RouteBindingSelectionSnapshot>(
    bindingSelectionRef.current,
  );
  const updateBindingSelection = useCallback((selection: RouteBindingSelection | null) => {
    const next = { selection, epoch: bindingSelectionRef.current.epoch + 1 };
    bindingSelectionRef.current = next;
    setBindingSelection(next);
  }, []);
  const getBindingSelection = useCallback(() => bindingSelectionRef.current, []);
  useEffect(() => {
    const changed = () => {
      const disconnected = !navigator.onLine;
      setOffline(disconnected);
      if (disconnected && pending.current && !pendingLocalOnly.current) {
        pending.current.abort();
        pending.current = null;
        setBusy(false);
        setMessage('');
        setError('Connection lost. Connect and try again when you’re ready.');
      }
    };
    changed();
    window.addEventListener('online', changed);
    window.addEventListener('offline', changed);
    return () => {
      window.removeEventListener('online', changed);
      window.removeEventListener('offline', changed);
    };
  }, []);
  useEffect(() => () => pending.current?.abort(), []);
  function clearRequest() {
    pending.current?.abort();
    pending.current = null;
    setBusy(false);
    setResult(null);
    updateBindingSelection(null);
    setError('');
    setMessage('');
  }
  async function run(
    work: (signal: AbortSignal) => Promise<void>,
    status = 'Looking up your route…',
    localOnly = false,
  ) {
    if (pending.current) return;
    if (!localOnly && !navigator.onLine) {
      setError('You’re offline. Connect to search places or calculate a route.');
      return;
    }
    const controller = new AbortController();
    pending.current = controller;
    pendingLocalOnly.current = localOnly;
    setBusy(true);
    setError('');
    setMessage(status);
    try {
      await work(controller.signal);
    } catch (failure) {
      if (!controller.signal.aborted) {
        if (
          failure instanceof BrowserRoutingError &&
          (failure.status === 401 || failure.status === 403)
        ) {
          setResult(null);
          updateBindingSelection(null);
          setSelected({});
          setMatches({});
          setQuery({ origin: '', destination: '' });
          setAttribution({ origin: '', destination: '' });
        }
        setMessage('');
        setError(
          failure instanceof BrowserLocationError
            ? failure.message
            : failure instanceof BrowserRoutingError
              ? failure.status === 401 || failure.status === 403
                ? 'Sign in again from Profile to continue.'
                : failure.status === 422
                  ? routingCoverageMessage
                  : failure.status === 429
                    ? 'Too many requests. Wait a minute and try again.'
                    : 'Route planning is unavailable. Try again later.'
              : 'Check your places and try again.',
        );
      }
    } finally {
      if (pending.current === controller) {
        pending.current = null;
        setBusy(false);
      }
    }
  }
  function search(endpoint: Endpoint) {
    setMatches((current) => ({ ...current, [endpoint]: undefined }));
    void run(async (signal) => {
      const found = await searchBrowserPlaces(account, query[endpoint], signal);
      if (signal.aborted) return;
      setMatches((current) => ({ ...current, [endpoint]: found }));
      setAttribution((current) => ({ ...current, [endpoint]: found.attribution }));
      setMessage(
        found.places.length
          ? 'Choose a matching place below.'
          : 'No matching places. Try a nearby street or city.',
      );
    });
  }
  function calculate() {
    if (!selected.origin || !selected.destination) return;
    const input = {
      mode,
      origin: [selected.origin.coordinate[0], selected.origin.coordinate[1]] as const,
      destination: [
        selected.destination.coordinate[0],
        selected.destination.coordinate[1],
      ] as const,
    };
    updateBindingSelection(null);
    void run(async (signal) => {
      const found = await calculateBrowserRoute(account, input, signal);
      if (signal.aborted) return;
      setResult(found);
      updateBindingSelection(
        found.routes.length
          ? {
              mode: input.mode,
              origin: [input.origin[0], input.origin[1]],
              destination: [input.destination[0], input.destination[1]],
              alternativeIndex: 0,
            }
          : null,
      );
      setResultRevision((value) => value + 1);
      setMessage(
        found.routes.length
          ? 'Route estimates ready.'
          : 'No route found for these places and travel mode.',
      );
    });
  }
  function locate() {
    clearRequest();
    setSelected((current) => ({ ...current, origin: undefined }));
    setMatches((current) => ({ ...current, origin: undefined }));
    setAttribution((current) => ({ ...current, origin: '' }));
    void run(
      async (signal) => {
        const location = await readBrowserLocation(signal);
        if (signal.aborted) return;
        setSelected((current) => ({
          ...current,
          origin: {
            id: 'device-location',
            label: 'My current location',
            coordinate: location.coordinate,
          },
        }));
        setQuery((current) => ({ ...current, origin: 'My current location' }));
        setMessage(
          `Starting location selected, accurate to about ${Math.ceil(location.accuracyMetres)} metres.`,
        );
      },
      'Getting your location…',
      true,
    );
  }
  return (
    <details className="route-planner">
      <summary>Plan a route</summary>
      {offline && (
        <p role="status">
          You’re offline. Loaded directions remain available in this view. Connect for place search,
          new map tiles or recalculation.
        </p>
      )}
      <p>
        Search text is processed by Routiqo’s Photon search service. Selected endpoints are
        processed by Routiqo’s Valhalla routing service. Results stay in this view and aren’t saved
        to your plans.
      </p>
      <button
        type="button"
        className="button secondary"
        onClick={() => {
          clearRequest();
          setQuery({ origin: '', destination: '' });
          setSelected({});
          setMatches({});
          setAttribution({ origin: '', destination: '' });
          setMode('driving');
          setMessage('Route planning cleared from this view. Browser map caches may remain.');
        }}
      >
        Clear route planning
      </button>
      <div className="route-endpoints">
        {(['origin', 'destination'] as const).map((endpoint) => (
          <div key={endpoint}>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                search(endpoint);
              }}
            >
              <label className="journey-kind">
                {endpoint === 'origin' ? 'From' : 'To'}
                <input
                  value={query[endpoint]}
                  maxLength={256}
                  minLength={3}
                  required
                  autoComplete="off"
                  placeholder="City, street or address"
                  onChange={(event) => {
                    clearRequest();
                    setQuery((current) => ({ ...current, [endpoint]: event.target.value }));
                    setSelected((current) => ({ ...current, [endpoint]: undefined }));
                    setMatches((current) => ({ ...current, [endpoint]: undefined }));
                    setAttribution((current) => ({ ...current, [endpoint]: '' }));
                  }}
                />
              </label>
              <button className="button secondary" disabled={busy || offline} type="submit">
                {endpoint === 'origin' ? 'Find starting place' : 'Find destination'}
              </button>
            </form>
            {endpoint === 'origin' && (
              <>
                <button
                  className="button secondary route-location-button"
                  type="button"
                  disabled={busy}
                  onClick={locate}
                >
                  Use my current location
                </button>
                <p className="route-attribution">
                  One reading for your starting point. Sent to Routiqo’s Valhalla routing service
                  when you calculate or prepare a private route.
                </p>
              </>
            )}
            {selected[endpoint] && (
              <p className="route-selected">Selected: {selected[endpoint]!.label}</p>
            )}
            {matches[endpoint] && (
              <>
                <ul
                  className="route-place-list"
                  aria-label={
                    endpoint === 'origin' ? 'Starting place matches' : 'Destination matches'
                  }
                >
                  {matches[endpoint]!.places.map((place) => (
                    <li key={place.id}>
                      <button
                        type="button"
                        onClick={() => {
                          clearRequest();
                          setSelected((current) => ({ ...current, [endpoint]: place }));
                          setMatches((current) => ({ ...current, [endpoint]: undefined }));
                        }}
                      >
                        {place.label}
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}
            {attribution[endpoint] && <p className="route-attribution">{attribution[endpoint]}</p>}
          </div>
        ))}
      </div>
      <button
        type="button"
        className="button secondary"
        disabled={busy || !selected.origin || !selected.destination}
        onClick={() => {
          clearRequest();
          setQuery({ origin: query.destination, destination: query.origin });
          setSelected({ origin: selected.destination, destination: selected.origin });
          setAttribution({ origin: attribution.destination, destination: attribution.origin });
          setMatches({});
          setMessage(
            'Starting point and destination swapped. Calculate when connected for new directions.',
          );
        }}
      >
        Swap starting point and destination
      </button>
      <label className="journey-kind">
        Travel mode
        <select
          value={mode}
          onChange={(event) => {
            clearRequest();
            setMode(event.target.value as RouteMode);
          }}
        >
          <option value="driving">Driving</option>
          <option value="walking">Walking</option>
          <option value="cycling">Cycling</option>
        </select>
      </label>
      <button
        type="button"
        className="button primary"
        disabled={busy || offline || !selected.origin || !selected.destination}
        onClick={calculate}
      >
        Calculate route
      </button>
      <p role="status" aria-live="polite">
        {message}
      </p>
      {busy && (
        <button
          type="button"
          className="button secondary"
          onClick={() => {
            const local = pendingLocalOnly.current;
            pending.current?.abort();
            pending.current = null;
            setBusy(false);
            setError('');
            setMessage(
              local
                ? 'Location request cancelled. Any browser permission prompt may still need to be closed.'
                : result?.routes.length
                  ? 'Request cancelled. The last successful route is still shown.'
                  : 'Request cancelled. Your selected places are unchanged.',
            );
          }}
        >
          Cancel request
        </button>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {result && result.routes.length > 0 && (
        <>
          {error && (
            <p className="fine-print">
              Showing the last successful route. It has not been recalculated.
            </p>
          )}
          <RouteResults
            key={resultRevision}
            result={result}
            onChoiceChange={(alternativeIndex) => {
              const selectedRoute = bindingSelectionRef.current.selection;
              if (!selectedRoute) return;
              updateBindingSelection({ ...selectedRoute, alternativeIndex });
            }}
          />
        </>
      )}
      {liveBinding && (
        <LiveRouteBindingPanel
          accountId={account}
          journeyId={liveBinding.journeyId}
          online={liveBinding.online && !offline}
          available={liveBinding.available}
          authority={liveBinding.authority}
          getAuthority={liveBinding.getAuthority}
          selectionSnapshot={bindingSelection}
          getSelectionSnapshot={getBindingSelection}
        />
      )}
    </details>
  );
}
