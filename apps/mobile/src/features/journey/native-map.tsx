import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import { nativeMapStyle } from './native-map-config';

const configuredStyle = nativeMapStyle(
  process.env.EXPO_PUBLIC_ROUTIQO_API_ORIGIN,
  process.env.EXPO_PUBLIC_ROUTIQO_MAP_STYLE_PATH,
);
type MapModule = typeof import('@maplibre/maplibre-react-native');

/** Explicit regional basemap preview. No GPS, route geometry or social overlay. */
export function NativeMapPreview() {
  const [visible, setVisible] = useState(false);
  const [module, setModule] = useState<MapModule | null>(null);
  const [status, setStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  useEffect(() => {
    if (!visible || !configuredStyle) return;
    let alive = true;
    setStatus('loading');
    void import('@maplibre/maplibre-react-native')
      .then((loaded) => {
        if (alive) setModule(loaded);
      })
      .catch(() => {
        if (alive) setStatus('error');
      });
    return () => {
      alive = false;
    };
  }, [visible]);
  if (!configuredStyle)
    return (
      <Text style={styles.notice}>
        Regional map is not configured for this build. Journeys remain available.
      </Text>
    );
  if (!visible)
    return (
      <Pressable accessibilityRole="button" style={styles.button} onPress={() => setVisible(true)}>
        <Text style={styles.buttonText}>Show regional map</Text>
      </Pressable>
    );
  const Map = module?.Map;
  const Camera = module?.Camera;
  return (
    <View style={styles.container}>
      <Pressable
        accessibilityRole="button"
        style={styles.close}
        onPress={() => {
          setVisible(false);
          setModule(null);
          setStatus('idle');
        }}
      >
        <Text style={styles.buttonText}>Close map</Text>
      </Pressable>
      {status === 'error' ? (
        <Text accessibilityRole="alert" style={styles.notice}>
          Map could not load. Close and try again when connected.
        </Text>
      ) : null}
      {status === 'loading' ? <Text style={styles.notice}>Loading regional map…</Text> : null}
      {Map && Camera && status !== 'error' ? (
        <Map
          style={styles.map}
          mapStyle={configuredStyle}
          onDidFinishLoadingMap={() => setStatus('ready')}
          onDidFailLoadingMap={() => setStatus('error')}
        >
          <Camera initialViewState={{ center: [80.2707, 13.0827], zoom: 8 }} />
        </Map>
      ) : null}
      <Text style={styles.notice}>
        Regional view near Chennai. This map does not track you or display your journey route.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { marginTop: tokens.spacing.md, marginBottom: tokens.spacing.lg },
  map: { height: 260, width: '100%', borderRadius: tokens.radius.sm },
  button: {
    minHeight: tokens.touchTarget,
    padding: 14,
    backgroundColor: tokens.colors.ink,
    borderRadius: tokens.radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 12,
  },
  close: { minHeight: tokens.touchTarget, padding: 12, alignItems: 'flex-start' },
  buttonText: { color: tokens.colors.accent, fontSize: 15, fontWeight: '600' },
  notice: { color: tokens.colors.muted, fontSize: 14, lineHeight: 21, marginTop: 8 },
});
