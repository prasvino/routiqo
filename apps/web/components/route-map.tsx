'use client';

import { useEffect, useRef, useState } from 'react';
import type { RouteCoordinate } from '@routiqo/shared';

const style = 'mapbox://styles/mapbox/streets-v12';
const sourceId = 'routiqo-route';
const layerId = 'routiqo-route-line';

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

export function RouteMap({ geometry }: { geometry: RouteCoordinate[] }) {
  const container = useRef<HTMLDivElement | null>(null);
  const revision = useRef(0);
  const [attempt, setAttempt] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [retryable, setRetryable] = useState(false);
  const [offline, setOffline] = useState(false);

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

  useEffect(() => {
    if (attempt === null) return;
    const token = publicToken();
    if (token === null) {
      setLoading(false);
      setReady(false);
      setRetryable(false);
      setError('Map display is unavailable. You can still review the directions.');
      return;
    }
    const accessToken = token;
    if (!validGeometry(geometry)) {
      setLoading(false);
      setReady(false);
      setRetryable(false);
      setError('Route map is unavailable for this route.');
      return;
    }
    const renderGeometry = unwrapGeometry(geometry);
    if (!navigator.onLine) {
      setOffline(true);
      setLoading(false);
      setReady(false);
      setRetryable(true);
      setError('You’re offline. Connect to show the route map.');
      return;
    }

    const currentRevision = ++revision.current;
    let disposed = false;
    let map: import('mapbox-gl').Map | null = null;
    let markers: import('mapbox-gl').Marker[] = [];
    let observer: ResizeObserver | null = null;
    let removed = false;
    setLoading(true);
    setReady(false);
    setError('');
    setRetryable(false);

    function current(): boolean {
      return !disposed && currentRevision === revision.current;
    }

    function removeMap() {
      if (removed) return;
      removed = true;
      observer?.disconnect();
      observer = null;
      markers.forEach((marker) => {
        try {
          marker.remove();
        } catch {
          // Cleanup is best effort; provider details must not escape this boundary.
        }
      });
      markers = [];
      try {
        map?.remove();
      } catch {
        // Cleanup is best effort; provider details must not escape this boundary.
      }
      map = null;
    }

    function fail() {
      if (!current()) return;
      setLoading(false);
      setReady(false);
      setRetryable(true);
      setError('Route map is unavailable. Try again later.');
      removeMap();
    }

    async function initialize() {
      try {
        const mapbox = (await import('mapbox-gl')).default;
        if (!current() || container.current === null) return;
        if (!navigator.onLine) {
          setOffline(true);
          setLoading(false);
          setReady(false);
          setRetryable(true);
          setError('You’re offline. Connect to show the route map.');
          return;
        }
        map = new mapbox.Map({
          container: container.current,
          style,
          accessToken,
          attributionControl: true,
          collectResourceTiming: false,
          performanceMetricsCollection: false,
        });
        map.addControl(new mapbox.NavigationControl(), 'top-right');
        if (typeof ResizeObserver !== 'undefined') {
          observer = new ResizeObserver(() => {
            if (!current()) return;
            try {
              map?.resize();
            } catch {
              fail();
            }
          });
          observer.observe(container.current);
        }
        map.on('error', fail);
        map.on('load', () => {
          if (!current() || map === null) return;
          try {
            map.addSource(sourceId, {
              type: 'geojson',
              data: {
                type: 'Feature',
                properties: {},
                geometry: { type: 'LineString', coordinates: renderGeometry },
              },
            });
            map.addLayer({
              id: layerId,
              type: 'line',
              source: sourceId,
              layout: { 'line-cap': 'round', 'line-join': 'round' },
              paint: { 'line-color': '#2f6b5f', 'line-opacity': 0.9, 'line-width': 5 },
            });

            const start = new mapbox.Marker({ color: '#2f6b5f' })
              .setLngLat(renderGeometry[0]!)
              .addTo(map);
            markers.push(start);
            start.getElement().setAttribute('aria-label', 'Route start');
            const end = new mapbox.Marker({ color: '#a34f35' })
              .setLngLat(renderGeometry[renderGeometry.length - 1]!)
              .addTo(map);
            markers.push(end);
            end.getElement().setAttribute('aria-label', 'Route end');

            const bounds = new mapbox.LngLatBounds();
            renderGeometry.forEach((coordinate) => bounds.extend(coordinate));
            map.fitBounds(bounds, { padding: 48, duration: 0, maxZoom: 15 });
            setLoading(false);
            setReady(true);
          } catch {
            fail();
          }
        });
      } catch {
        fail();
      }
    }

    void initialize();
    return () => {
      disposed = true;
      revision.current += 1;
      removeMap();
    };
  }, [attempt, geometry]);

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
