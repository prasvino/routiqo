'use client';
import { useEffect, useRef, useState } from 'react';
import type { PlaceMatch, PlaceResults, RouteMode, RouteResult } from '@routiqo/shared';
import { readBrowserLocation, BrowserLocationError } from '../lib/browser-location';
import {
  calculateBrowserRoute,
  searchBrowserPlaces,
  BrowserRoutingError,
} from '../lib/browser-routing';

type Endpoint = 'origin' | 'destination';
export function RoutePlanner({ account }: { account: string }) {
  return <Planner key={account} account={account} />;
}
function Planner({ account }: { account: string }) {
  const [query, setQuery] = useState({ origin: '', destination: '' });
  const [selected, setSelected] = useState<Partial<Record<Endpoint, PlaceMatch | undefined>>>({});
  const [matches, setMatches] = useState<Partial<Record<Endpoint, PlaceResults | undefined>>>({});
  const [attribution, setAttribution] = useState({ origin: '', destination: '' });
  const [mode, setMode] = useState<RouteMode>('driving');
  const [result, setResult] = useState<RouteResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => pending.current?.abort(), []);
  function clearRequest() {
    pending.current?.abort();
    pending.current = null;
    setBusy(false);
    setResult(null);
    setError('');
    setMessage('');
  }
  async function run(
    work: (signal: AbortSignal) => Promise<void>,
    status = 'Looking up your route…',
  ) {
    if (pending.current) return;
    if (!navigator.onLine) {
      setError('You’re offline. Connect to search places or calculate a route.');
      return;
    }
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setError('');
    setMessage(status);
    try {
      await work(controller.signal);
    } catch (failure) {
      if (!controller.signal.aborted) {
        setMessage('');
        setError(
          failure instanceof BrowserLocationError
            ? failure.message
            : failure instanceof BrowserRoutingError
              ? failure.status === 401 || failure.status === 403
                ? 'Sign in again from Profile to continue.'
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
      origin: selected.origin.coordinate,
      destination: selected.destination.coordinate,
    };
    setResult(null);
    void run(async (signal) => {
      const found = await calculateBrowserRoute(account, input, signal);
      if (signal.aborted) return;
      setResult(found);
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
    void run(async (signal) => {
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
    }, 'Getting your location…');
  }
  return (
    <details className="route-planner">
      <summary>Plan a route</summary>
      <p>
        Search text and selected endpoints are sent to Mapbox. Results stay in this view and aren’t
        saved to your plans.
      </p>
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
              <button className="button secondary" disabled={busy} type="submit">
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
                  One reading for your starting point. Sent to Mapbox only when you calculate.
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
        disabled={busy || !selected.origin || !selected.destination}
        onClick={calculate}
      >
        Calculate route
      </button>
      <p role="status" aria-live="polite">
        {message}
      </p>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {result && result.routes.length > 0 && (
        <div className="route-estimates">
          <h3>Route estimates</h3>
          <ol>
            {result.routes.map((route, index) => (
              <li key={index}>
                <strong>
                  {(route.distanceMetres / 1000).toLocaleString('en-IN', {
                    maximumFractionDigits: 1,
                  })}{' '}
                  km
                </strong>
                <span>About {Math.max(1, Math.round(route.durationSeconds / 60))} min</span>
              </li>
            ))}
          </ol>
          <p>
            Mapbox estimate ·{' '}
            {new Date(result.calculatedAt).toLocaleTimeString('en-IN', {
              hour: '2-digit',
              minute: '2-digit',
            })}
            . Check road conditions before travelling.
          </p>
        </div>
      )}
    </details>
  );
}
