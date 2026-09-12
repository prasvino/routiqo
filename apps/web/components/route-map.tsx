'use client';

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { RouteCoordinate } from '@routiqo/shared';

const style = 'mapbox://styles/mapbox/streets-v12';
const sourceId = 'routiqo-route';
const layerId = 'routiqo-route-line';
const unavailableMessage = 'Route map is unavailable. Try again later.';
const degradedMessage =
  'Some map details may be unavailable. The displayed route is still available.';

type Mapbox = typeof import('mapbox-gl').default;
type RouteData = Parameters<import('mapbox-gl').GeoJSONSource['setData']>[0];

interface MapSession {
  revision: number;
  mapbox: Mapbox;
  map: import('mapbox-gl').Map;
  source: import('mapbox-gl').GeoJSONSource | null;
  markers: import('mapbox-gl').Marker[];
  observer: ResizeObserver | null;
  loaded: boolean;
  removed: boolean;
}

function publicToken(): string | null {
  const token = process.env.NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN;
  return typeof token === 'string' && token.startsWith('pk.') && token.length <= 2048
    ? token
    : null;
}

function validGeometry(geometry: RouteCoordinate[]): boolean {
  return (
    geometry.length >= 2 &&
    geometry.length <= 10_000 &&
    geometry.every(
      (coordinate) =>
        Array.isArray(coordinate) &&
        coordinate.length === 2 &&
        Number.isFinite(coordinate[0]) &&
        coordinate[0] >= -180 &&
        coordinate[0] <= 180 &&
        Number.isFinite(coordinate[1]) &&
        coordinate[1] >= -90 &&
        coordinate[1] <= 90,
    )
  );
}

function unwrapGeometry(geometry: RouteCoordinate[]): RouteCoordinate[] {
  const first = geometry[0]!;
  const unwrapped: RouteCoordinate[] = [[first[0], first[1]]];
  let previousLongitude = first[0];
  for (const coordinate of geometry.slice(1)) {
    let longitude = coordinate[0];
    const difference = longitude - previousLongitude;
    if (difference > 180) longitude -= 360 * Math.ceil((difference - 180) / 360);
    if (difference < -180) longitude += 360 * Math.ceil((-180 - difference) / 360);
    unwrapped.push([longitude, coordinate[1]]);
    previousLongitude = longitude;
  }
  return unwrapped;
}

function routeData(geometry: RouteCoordinate[]): RouteData {
  return {
    type: 'Feature',
    properties: {},
    geometry: { type: 'LineString', coordinates: geometry },
  };
}

function removeMarkers(session: MapSession) {
  session.markers.forEach((marker) => {
    try {
      marker.remove();
    } catch {
      // Cleanup is best effort; provider details must not escape this boundary.
    }
  });
  session.markers = [];
}

function removeSession(session: MapSession) {
  if (session.removed) return;
  session.removed = true;
  session.observer?.disconnect();
  session.observer = null;
  removeMarkers(session);
  try {
    session.map.remove();
  } catch {
    // Cleanup is best effort; provider details must not escape this boundary.
  }
}

function renderRoute(session: MapSession, geometry: RouteCoordinate[]) {
  const data = routeData(geometry);
  if (session.source === null) {
    session.map.addSource(sourceId, { type: 'geojson', data });
    session.map.addLayer({
      id: layerId,
      type: 'line',
      source: sourceId,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#2f6b5f', 'line-opacity': 0.9, 'line-width': 5 },
    });
    const source = session.map.getSource(sourceId);
    if (source === undefined || !('setData' in source)) throw new Error('Route source unavailable');
    session.source = source as import('mapbox-gl').GeoJSONSource;
  } else {
    session.source.setData(data);
  }

  const endpoints = [geometry[0]!, geometry[geometry.length - 1]!] as const;
  if (session.markers.length === 2) {
    session.markers[0]!.setLngLat(endpoints[0]);
    session.markers[1]!.setLngLat(endpoints[1]);
  } else {
    removeMarkers(session);
    const start = new session.mapbox.Marker({ color: '#2f6b5f' })
      .setLngLat(endpoints[0])
      .addTo(session.map);
    start.getElement().setAttribute('aria-label', 'Route start');
    const end = new session.mapbox.Marker({ color: '#a34f35' })
      .setLngLat(endpoints[1])
      .addTo(session.map);
    end.getElement().setAttribute('aria-label', 'Route end');
    session.markers = [start, end];
  }

  const bounds = new session.mapbox.LngLatBounds();
  geometry.forEach((coordinate) => bounds.extend(coordinate));
  session.map.fitBounds(bounds, { padding: 48, duration: 0, maxZoom: 15 });
}

export function RouteMap({ geometry }: { geometry: RouteCoordinate[] }) {
  const container = useRef<HTMLDivElement | null>(null);
  const revision = useRef(0);
  const phase = useRef<'idle' | 'importing' | 'loading' | 'ready' | 'failed'>('idle');
  const session = useRef<MapSession | null>(null);
  const latestGeometry = useRef<RouteCoordinate[] | null>(null);
  const invalidatedByGeometry = useRef(false);
  const [attempt, setAttempt] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [retryable, setRetryable] = useState(false);
  const [offline, setOffline] = useState(false);

  const preparedGeometry = useMemo(
    () => (validGeometry(geometry) ? unwrapGeometry(geometry) : null),
    [geometry],
  );

  useLayoutEffect(() => {
    latestGeometry.current = preparedGeometry;
  }, [preparedGeometry]);

  useEffect(() => {
    const updateConnection = () => setOffline(!navigator.onLine);
    updateConnection();
    window.addEventListener('online', updateConnection);
    window.addEventListener('offline', updateConnection);
    return () => {
      window.removeEventListener('online', updateConnection);
      window.removeEventListener('offline', updateConnection);
    };
  }, []);

  useLayoutEffect(() => {
    if (attempt === null) return;
    const renderGeometry = preparedGeometry;
    const currentSession = session.current;

    if (renderGeometry === null) {
      invalidatedByGeometry.current = true;
      setLoading(false);
      setReady(false);
      setRetryable(false);
      setError('Route map is unavailable for this route.');
      if (
        currentSession !== null ||
        phase.current === 'importing' ||
        phase.current === 'loading' ||
        phase.current === 'ready'
      ) {
        revision.current += 1;
        session.current = null;
        if (currentSession !== null) removeSession(currentSession);
      }
      phase.current = 'failed';
      return;
    }

    if (invalidatedByGeometry.current && currentSession === null) {
      setLoading(false);
      setReady(false);
      setRetryable(true);
      setError(unavailableMessage);
      return;
    }

    if (currentSession?.loaded && phase.current === 'ready') {
      try {
        renderRoute(currentSession, renderGeometry);
        setLoading(false);
        setReady(true);
        setRetryable(false);
        setError('');
      } catch {
        session.current = null;
        revision.current += 1;
        phase.current = 'failed';
        removeSession(currentSession);
        setLoading(false);
        setReady(false);
        setRetryable(true);
        setError(unavailableMessage);
      }
    }
  }, [attempt, preparedGeometry]);

  useEffect(() => {
    if (attempt === null) return;
    const token = publicToken();
    if (token === null) {
      phase.current = 'failed';
      setLoading(false);
      setReady(false);
      setRetryable(false);
      setError('Map display is unavailable. You can still review the directions.');
      return;
    }
    const accessToken = token;
    if (latestGeometry.current === null) {
      phase.current = 'failed';
      setLoading(false);
      setReady(false);
      setRetryable(false);
      setError('Route map is unavailable for this route.');
      return;
    }
    if (!navigator.onLine) {
      phase.current = 'failed';
      setOffline(true);
      setLoading(false);
      setReady(false);
      setRetryable(true);
      setError('You’re offline. Connect to show the route map.');
      return;
    }

    const currentRevision = ++revision.current;
    let disposed = false;
    let ownedSession: MapSession | null = null;
    invalidatedByGeometry.current = false;
    phase.current = 'importing';
    setLoading(true);
    setReady(false);
    setError('');
    setRetryable(false);

    const loadDeadline = setTimeout(() => {
      if (disposed || currentRevision !== revision.current || phase.current === 'ready') return;
      revision.current += 1;
      if (ownedSession !== null) {
        if (session.current === ownedSession) session.current = null;
        removeSession(ownedSession);
      }
      phase.current = 'failed';
      setLoading(false);
      setReady(false);
      setRetryable(true);
      setError(
        'Map loading took too long. Your directions are still available. Try again when connected.',
      );
    }, 20000);

    async function initialize() {
      try {
        const mapbox = (await import('mapbox-gl')).default;
        if (disposed || currentRevision !== revision.current || container.current === null) return;
        if (latestGeometry.current === null) return;
        if (!navigator.onLine) {
          clearTimeout(loadDeadline);
          phase.current = 'failed';
          setOffline(true);
          setLoading(false);
          setReady(false);
          setRetryable(true);
          setError('You’re offline. Connect to show the route map.');
          return;
        }

        const map = new mapbox.Map({
          container: container.current,
          style,
          accessToken,
          attributionControl: true,
          collectResourceTiming: false,
          performanceMetricsCollection: false,
        });
        const currentSession: MapSession = {
          revision: currentRevision,
          mapbox,
          map,
          source: null,
          markers: [],
          observer: null,
          loaded: false,
          removed: false,
        };
        ownedSession = currentSession;
        if (disposed || currentRevision !== revision.current) {
          removeSession(currentSession);
          return;
        }
        session.current = currentSession;
        phase.current = 'loading';

        function current(): boolean {
          return (
            !disposed &&
            currentRevision === revision.current &&
            session.current === currentSession &&
            !currentSession.removed
          );
        }

        function fail() {
          if (!current()) return;
          clearTimeout(loadDeadline);
          session.current = null;
          revision.current += 1;
          phase.current = 'failed';
          setLoading(false);
          setReady(false);
          setRetryable(true);
          setError(unavailableMessage);
          removeSession(currentSession);
        }

        map.addControl(new mapbox.NavigationControl(), 'top-right');
        if (typeof ResizeObserver !== 'undefined') {
          currentSession.observer = new ResizeObserver(() => {
            if (!current()) return;
            try {
              map.resize();
            } catch {
              fail();
            }
          });
          currentSession.observer.observe(container.current);
        }
        map.on('error', () => {
          if (!current()) return;
          if (!currentSession.loaded || navigator.onLine) {
            fail();
            return;
          }
          setOffline(true);
          setLoading(false);
          setReady(true);
          setRetryable(false);
          setError(degradedMessage);
        });
        map.on('load', () => {
          if (!current()) return;
          const renderGeometry = latestGeometry.current;
          if (renderGeometry === null) {
            fail();
            return;
          }
          try {
            renderRoute(currentSession, renderGeometry);
            currentSession.loaded = true;
            clearTimeout(loadDeadline);
            phase.current = 'ready';
            setLoading(false);
            setReady(true);
            setRetryable(false);
            setError('');
          } catch {
            fail();
          }
        });
      } catch {
        clearTimeout(loadDeadline);
        if (ownedSession !== null) {
          if (session.current === ownedSession) session.current = null;
          removeSession(ownedSession);
        }
        if (disposed || currentRevision !== revision.current) return;
        revision.current += 1;
        phase.current = 'failed';
        setLoading(false);
        setReady(false);
        setRetryable(true);
        setError(unavailableMessage);
      }
    }

    void initialize();
    return () => {
      disposed = true;
      clearTimeout(loadDeadline);
      revision.current += 1;
      const currentSession = session.current;
      if (currentSession?.revision === currentRevision) {
        session.current = null;
        removeSession(currentSession);
      }
      phase.current = 'idle';
    };
  }, [attempt]);

  return (
    <section className="route-map" aria-label="Route map preview">
      <p className="route-attribution">
        Loading the map contacts Mapbox. Map tiles and usage data may remain in this browser until
        you clear site data.
      </p>
      {offline && <p role="status">You’re offline. The current map may not update.</p>}
      {(attempt === null || (error !== '' && retryable)) && (
        <button
          className="button secondary"
          type="button"
          disabled={loading}
          onClick={() => setAttempt((current) => (current ?? 0) + 1)}
        >
          {attempt === null ? 'Show map' : 'Retry map'}
        </button>
      )}
      {attempt !== null && (
        <div
          className="route-map-canvas"
          ref={container}
          role="region"
          aria-label="Map showing the selected route"
          hidden={!loading && !ready}
        />
      )}
      <p role="status" aria-live="polite">
        {loading ? 'Loading route map…' : ready ? 'Route map ready.' : ''}
      </p>
      {error !== '' && <p role="alert">{error}</p>}
    </section>
  );
}
