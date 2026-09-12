'use client';

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { RouteCoordinate } from '@routiqo/shared';

const sourceId = 'routiqo-route';
const layerId = 'routiqo-route-line';
const blockedResourcePath = '/maps/__blocked_map_resource__';
const workerPath = '/maplibre/6.9.0/maplibre-gl-worker.mjs';
const maxStylePathLength = 2048;
const maxResourceUrlLength = 8192;
const unavailableMessage = 'Route map is unavailable. Try again later.';
const degradedMessage =
  'Some map details may be unavailable. The displayed route is still available.';

type MapRenderer = typeof import('maplibre-gl');
type RouteData = Parameters<import('maplibre-gl').GeoJSONSource['setData']>[0];

interface MapSession {
  revision: number;
  renderer: MapRenderer;
  map: import('maplibre-gl').Map;
  source: import('maplibre-gl').GeoJSONSource | null;
  markers: import('maplibre-gl').Marker[];
  observer: ResizeObserver | null;
  loaded: boolean;
  removed: boolean;
}

function hasControlCharacters(value: string): boolean {
  return Array.from(value).some((character) => {
    const code = character.charCodeAt(0);
    return code <= 31 || code === 127;
  });
}

function hasUnsafePath(path: string): boolean {
  let decoded = path;
  for (let depth = 0; depth < 5; depth += 1) {
    if (
      decoded.includes('\\') ||
      hasControlCharacters(decoded) ||
      /%(?:2f|5c)/iu.test(decoded) ||
      decoded.split('/').some((segment) => segment === '.' || segment === '..')
    ) {
      return true;
    }
    let next: string;
    try {
      next = decodeURIComponent(decoded);
    } catch {
      return true;
    }
    if (next === decoded) return false;
    decoded = next;
  }
  return true;
}

function mapStylePath(): string | null {
  const path = process.env.NEXT_PUBLIC_MAP_STYLE_PATH;
  return typeof path === 'string' &&
    path.length > '/maps/.json'.length &&
    path.length <= maxStylePathLength &&
    path.startsWith('/maps/') &&
    path.endsWith('.json') &&
    !path.includes('//') &&
    !path.includes('?') &&
    !path.includes('#') &&
    !hasUnsafePath(path)
    ? path
    : null;
}

function transformMapRequest(url: string): import('maplibre-gl').RequestParameters {
  const origin = window.location.origin;
  if (
    url.length <= maxResourceUrlLength &&
    url.startsWith('data:image/') &&
    !hasControlCharacters(url) &&
    !url.includes('#')
  ) {
    return { url };
  }
  if (url.length <= maxResourceUrlLength && !url.includes('?') && !url.includes('#')) {
    try {
      const resource = new URL(url, origin);
      if (
        (resource.protocol === 'http:' || resource.protocol === 'https:') &&
        resource.origin === origin &&
        resource.username === '' &&
        resource.password === '' &&
        resource.pathname.startsWith('/maps/') &&
        resource.search === '' &&
        resource.hash === '' &&
        !hasUnsafePath(url) &&
        !hasUnsafePath(resource.pathname)
      ) {
        return { url: resource.href, credentials: 'same-origin' };
      }
      if (
        resource.protocol === 'blob:' &&
        resource.origin === origin &&
        resource.search === '' &&
        resource.hash === ''
      ) {
        return { url: resource.href };
      }
    } catch {
      // Invalid and unapproved resources use the same generic blocked path.
    }
  }
  return { url: new URL(blockedResourcePath, origin).href, credentials: 'same-origin' };
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

async function renderRoute(session: MapSession, geometry: RouteCoordinate[]) {
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
    session.source = source as import('maplibre-gl').GeoJSONSource;
  }
  const sourceUpdate = session.source.setData(data);

  const endpoints = [geometry[0]!, geometry[geometry.length - 1]!] as const;
  if (session.markers.length === 2) {
    session.markers[0]!.setLngLat(endpoints[0]);
    session.markers[1]!.setLngLat(endpoints[1]);
  } else {
    removeMarkers(session);
    const start = new session.renderer.Marker({ color: '#2f6b5f' })
      .setLngLat(endpoints[0])
      .addTo(session.map);
    start.getElement().setAttribute('aria-label', 'Route start');
    const end = new session.renderer.Marker({ color: '#a34f35' })
      .setLngLat(endpoints[1])
      .addTo(session.map);
    end.getElement().setAttribute('aria-label', 'Route end');
    session.markers = [start, end];
  }

  const bounds = new session.renderer.LngLatBounds();
  geometry.forEach((coordinate) => bounds.extend(coordinate));
  session.map.fitBounds(bounds, { padding: 48, duration: 0, maxZoom: 15 });
  await sourceUpdate;
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
      void renderRoute(currentSession, renderGeometry).catch(() => {
        if (session.current !== currentSession || currentSession.removed) return;
        session.current = null;
        revision.current += 1;
        phase.current = 'failed';
        removeSession(currentSession);
        setLoading(false);
        setReady(false);
        setRetryable(true);
        setError(unavailableMessage);
      });
      setLoading(false);
      setReady(true);
      setRetryable(false);
      setError('');
    }
  }, [attempt, preparedGeometry]);

  useEffect(() => {
    if (attempt === null) return;
    const stylePath = mapStylePath();
    if (stylePath === null) {
      phase.current = 'failed';
      setLoading(false);
      setReady(false);
      setRetryable(false);
      setError('Map display is unavailable. You can still review the directions.');
      return;
    }
    const configuredStyle = stylePath;
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
        const renderer = await import('maplibre-gl');
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

        renderer.setWorkerUrl(workerPath);
        const map = new renderer.Map({
          container: container.current,
          style: configuredStyle,
          attributionControl: {},
          transformRequest: transformMapRequest,
        });
        const currentSession: MapSession = {
          revision: currentRevision,
          renderer,
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

        map.addControl(new renderer.NavigationControl(), 'top-right');
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
        map.on('load', async () => {
          if (!current()) return;
          try {
            let renderGeometry = latestGeometry.current;
            while (current() && renderGeometry !== null) {
              await renderRoute(currentSession, renderGeometry);
              if (latestGeometry.current === renderGeometry) break;
              renderGeometry = latestGeometry.current;
            }
            if (!current() || renderGeometry === null) return;
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
        Loading the map contacts the configured map service and shares the viewed area. Map
        resources may remain in browser caches until you clear site data.
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
