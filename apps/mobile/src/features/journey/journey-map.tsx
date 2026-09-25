import { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, StyleSheet } from 'react-native';
import type { JourneyRoute, RouteCoordinate } from '@routiqo/shared';
import { tokens } from '@routiqo/design-tokens';
import { nativeMapStyle } from './native-map-config';

import type { CameraRef } from '@maplibre/maplibre-react-native';

type MapModule = typeof import('@maplibre/maplibre-react-native');

export const journeyMapStyle = nativeMapStyle(
  process.env.EXPO_PUBLIC_ROUTIQO_API_ORIGIN,
  process.env.EXPO_PUBLIC_ROUTIQO_MAP_STYLE_PATH,
);
export type JourneyMapStatus = 'unconfigured' | 'loading' | 'ready' | 'tiles_failed' | 'failed';

export function routeBounds(
  geometry: readonly RouteCoordinate[],
): [number, number, number, number] {
  let west = 180;
  let south = 90;
  let east = -180;
  let north = -90;
  for (const [longitude, latitude] of geometry) {
    west = Math.min(west, longitude);
    east = Math.max(east, longitude);
    south = Math.min(south, latitude);
    north = Math.max(north, latitude);
  }
  return [west, south, east, north];
}

/**
 * Journey map: the stored route, owner-only endpoints and the traveller's own dot.
 * The route source is created once; position updates change only the position source.
 */
export function JourneyMap({
  route,
  position,
  follow,
  onUserPan,
  onStatus,
}: {
  route: JourneyRoute | null;
  position: RouteCoordinate | null;
  follow: boolean;
  onUserPan(): void;
  onStatus(status: JourneyMapStatus): void;
}) {
  const [module, setModule] = useState<MapModule | null>(null);
  const [reduceMotion, setReduceMotion] = useState(false);
  const camera = useRef<CameraRef | null>(null);
  const statusRef = useRef(onStatus);
  statusRef.current = onStatus;
  useEffect(() => {
    if (!journeyMapStyle) {
      statusRef.current('unconfigured');
      return;
    }
    let alive = true;
    statusRef.current('loading');
    void import('@maplibre/maplibre-react-native')
      .then((loaded) => {
        if (alive) setModule(loaded);
      })
      .catch(() => {
        if (alive) statusRef.current('failed');
      });
    void AccessibilityInfo.isReduceMotionEnabled().then((value) => {
      if (alive) setReduceMotion(value);
    });
    const motion = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduceMotion);
    return () => {
      alive = false;
      motion.remove();
    };
  }, []);
  const routeData = useMemo(
    () =>
      route
        ? {
            type: 'FeatureCollection' as const,
            features: [
              {
                type: 'Feature' as const,
                properties: {},
                geometry: { type: 'LineString' as const, coordinates: route.geometry },
              },
            ],
          }
        : null,
    [route],
  );
  const endpointData = useMemo(
    () =>
      route
        ? {
            type: 'FeatureCollection' as const,
            features: [route.origin, route.destination].map((coordinates, index) => ({
              type: 'Feature' as const,
              properties: { end: index === 0 ? 'start' : 'destination' },
              geometry: { type: 'Point' as const, coordinates },
            })),
          }
        : null,
    [route],
  );
  const positionData = useMemo(
    () =>
      position
        ? {
            type: 'Feature' as const,
            properties: {},
            geometry: { type: 'Point' as const, coordinates: position },
          }
        : null,
    [position],
  );
  const initialBounds = useMemo(() => (route ? routeBounds(route.geometry) : null), [route]);
  useEffect(() => {
    if (!follow || !position || !camera.current) return;
    if (reduceMotion) camera.current.jumpTo({ center: position });
    else camera.current.easeTo({ center: position, duration: 300 });
  }, [follow, position, reduceMotion]);
  if (!journeyMapStyle || !module) return null;
  const { Map, Camera, GeoJSONSource, Layer } = module;
  return (
    <Map
      style={styles.map}
      mapStyle={journeyMapStyle}
      accessibilityLabel="Journey map with your route"
      compass={false}
      touchRotate={false}
      touchPitch={false}
      onDidFinishLoadingMap={() => statusRef.current('ready')}
      onDidFailLoadingMap={() => statusRef.current('tiles_failed')}
      onRegionWillChange={(event) => {
        if (event.nativeEvent.userInteraction) onUserPan();
      }}
    >
      <Camera
        ref={camera}
        initialViewState={
          initialBounds
            ? {
                bounds: initialBounds,
                padding: { top: 40, right: 40, bottom: 40, left: 40 },
              }
            : { center: position ?? [80.2707, 13.0827], zoom: 9 }
        }
      />
      {routeData ? (
        <GeoJSONSource id="journey-route" data={routeData}>
          <Layer
            id="journey-route-line"
            type="line"
            layout={{ 'line-join': 'round', 'line-cap': 'round' }}
            paint={{ 'line-color': tokens.colors.accent, 'line-width': 5 }}
          />
        </GeoJSONSource>
      ) : null}
      {endpointData ? (
        <GeoJSONSource id="journey-endpoints" data={endpointData}>
          <Layer
            id="journey-endpoints-dot"
            type="circle"
            paint={{
              'circle-radius': 6,
              'circle-color': tokens.colors.surface,
              'circle-stroke-color': tokens.colors.ink,
              'circle-stroke-width': 2,
            }}
          />
        </GeoJSONSource>
      ) : null}
      {positionData ? (
        <GeoJSONSource id="journey-position" data={positionData}>
          <Layer
            id="journey-position-dot"
            type="circle"
            paint={{
              'circle-radius': 8,
              'circle-color': tokens.colors.ink,
              'circle-stroke-color': tokens.colors.surface,
              'circle-stroke-width': 3,
            }}
          />
        </GeoJSONSource>
      ) : null}
    </Map>
  );
}

const styles = StyleSheet.create({
  map: { height: 360, width: '100%', borderRadius: tokens.radius.sm },
});
