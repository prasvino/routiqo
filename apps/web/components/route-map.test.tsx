// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RouteCoordinate } from '@routiqo/shared';
import { RouteMap } from './route-map';

type MapHandler = (...values: unknown[]) => void;

interface MapDouble {
  options: Record<string, unknown>;
  handlers: Map<string, MapHandler[]>;
  addControl: ReturnType<typeof vi.fn>;
  on: ReturnType<typeof vi.fn>;
  addSource: ReturnType<typeof vi.fn>;
  getSource: ReturnType<typeof vi.fn>;
  addLayer: ReturnType<typeof vi.fn>;
  fitBounds: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
  remove: ReturnType<typeof vi.fn>;
  emit(name: string, value?: unknown): void;
}

interface GeoJsonSourceDouble {
  setData: ReturnType<typeof vi.fn>;
}

interface MarkerDouble {
  options: Record<string, unknown>;
  coordinate: RouteCoordinate | null;
  setLngLat: ReturnType<typeof vi.fn>;
  addTo: ReturnType<typeof vi.fn>;
  getElement: ReturnType<typeof vi.fn>;
  remove: ReturnType<typeof vi.fn>;
}

const sdk = vi.hoisted(() => {
  const instances: MapDouble[] = [];
  const markers: MarkerDouble[] = [];
  const controls: object[] = [];
  const bounds: Array<{ extend: ReturnType<typeof vi.fn> }> = [];
  const sources: GeoJsonSourceDouble[] = [];
  const sourceUpdate = { promise: null as Promise<void> | null };
  const MapConstructor = vi.fn(function (options: Record<string, unknown>) {
    let routeSource: GeoJsonSourceDouble | undefined;
    const instance: MapDouble = {
      options,
      handlers: new globalThis.Map(),
      addControl: vi.fn(),
      on: vi.fn(),
      addSource: vi.fn(),
      getSource: vi.fn(),
      addLayer: vi.fn(),
      fitBounds: vi.fn(),
      resize: vi.fn(),
      remove: vi.fn(),
      emit(name, value) {
        this.handlers.get(name)?.forEach((handler) => handler(value));
      },
    };
    instance.addControl.mockImplementation(() => instance);
    instance.addSource.mockImplementation(() => {
      routeSource = { setData: vi.fn(() => sourceUpdate.promise ?? Promise.resolve()) };
      sources.push(routeSource);
      return instance;
    });
    instance.getSource.mockImplementation(() => routeSource);
    instance.on.mockImplementation((name: string, handler: MapHandler) => {
      instance.handlers.set(name, [...(instance.handlers.get(name) ?? []), handler]);
      return instance;
    });
    instances.push(instance);
    return instance;
  });
  const NavigationControl = vi.fn(function () {
    const control = {};
    controls.push(control);
    return control;
  });
  const Marker = vi.fn(function (options: Record<string, unknown>) {
    const element = { setAttribute: vi.fn() };
    const marker: MarkerDouble = {
      options,
      coordinate: null,
      setLngLat: vi.fn(),
      addTo: vi.fn(),
      getElement: vi.fn(() => element),
      remove: vi.fn(),
    };
    marker.setLngLat.mockImplementation((coordinate: RouteCoordinate) => {
      marker.coordinate = coordinate;
      return marker;
    });
    marker.addTo.mockImplementation(() => marker);
    markers.push(marker);
    return marker;
  });
  const LngLatBounds = vi.fn(function () {
    const value = { extend: vi.fn() };
    value.extend.mockImplementation(() => value);
    bounds.push(value);
    return value;
  });
  const api = {
    Map: MapConstructor,
    NavigationControl,
    Marker,
    LngLatBounds,
    setWorkerUrl: vi.fn(),
  };
  return {
    api,
    MapConstructor,
    NavigationControl,
    Marker,
    LngLatBounds,
    setWorkerUrl: api.setWorkerUrl,
    instances,
    markers,
    controls,
    bounds,
    sources,
    sourceUpdate,
    moduleLoads: 0,
    importGate: null as Promise<void> | null,
  };
});

vi.mock('maplibre-gl', async () => {
  sdk.moduleLoads += 1;
  if (sdk.importGate) await sdk.importGate;
  return sdk.api;
});

const stylePath = '/maps/styles/routiqo.json';
const geometry: RouteCoordinate[] = [
  [80.2, 13.1],
  [80.25, 13.08],
  [80.3, 13.04],
];
const alternateGeometry: RouteCoordinate[] = [
  [79.9, 12.9],
  [80.05, 13],
];

class ResizeObserverDouble {
  static instances: ResizeObserverDouble[] = [];
  readonly observe = vi.fn();
  readonly disconnect = vi.fn();
  constructor(private readonly callback: ResizeObserverCallback) {
    ResizeObserverDouble.instances.push(this);
  }
  notify() {
    this.callback([], this as unknown as ResizeObserver);
  }
}

function setOnline(value: boolean) {
  Object.defineProperty(window.navigator, 'onLine', { configurable: true, value });
}

function resetSdkDoubles() {
  sdk.MapConstructor.mockClear();
  sdk.NavigationControl.mockClear();
  sdk.Marker.mockClear();
  sdk.LngLatBounds.mockClear();
  sdk.setWorkerUrl.mockClear();
  sdk.instances.splice(0);
  sdk.markers.splice(0);
  sdk.controls.splice(0);
  sdk.bounds.splice(0);
  sdk.sources.splice(0);
  sdk.sourceUpdate.promise = null;
  sdk.importGate = null;
  ResizeObserverDouble.instances.splice(0);
}

beforeEach(() => {
  resetSdkDoubles();
  vi.stubEnv('NEXT_PUBLIC_MAP_STYLE_PATH', stylePath);
  vi.stubGlobal('ResizeObserver', ResizeObserverDouble);
  setOnline(true);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('RouteMap', () => {
  it('does not import MapLibre on mount or for missing and unsafe style paths', async () => {
    const invalidPaths: Array<string | undefined> = [
      undefined,
      '',
      'https://maps.example/maps/style.json',
      '//maps.example/style.json',
      '/other/style.json',
      '/maps/style.yaml',
      '/maps//style.json',
      '/maps/style.json?version=1',
      '/maps/style.json#section',
      '/maps/..\\private/style.json',
      '/maps/%2e%2e/private/style.json',
      '/maps/%252e%252e/private/style.json',
      '/maps/%252525252e%252525252e/private/style.json',
      '/maps/%25252525252e%25252525252e/private/style.json',
      '/maps/styles%2fprivate/style.json',
      '/maps/styles%255cprivate/style.json',
      '/maps/styles%2525252fprivate/style.json',
      `/maps/style\u0000.json`,
      `/maps/${'a'.repeat(2048)}.json`,
    ];

    for (const path of invalidPaths) {
      cleanup();
      vi.stubEnv('NEXT_PUBLIC_MAP_STYLE_PATH', path);
      const view = render(<RouteMap geometry={geometry} />);
      expect(screen.getByText(/configured map service/)).toBeTruthy();
      expect(screen.getByText(/shares the viewed area/)).toBeTruthy();
      expect(screen.getByText(/browser caches/)).toBeTruthy();
      expect(sdk.MapConstructor).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
      expect((await screen.findByRole('alert')).textContent).toBe(
        'Map display is unavailable. You can still review the directions.',
      );
      expect((view.container.querySelector('.route-map-canvas') as HTMLDivElement).hidden).toBe(
        true,
      );
      expect(screen.queryByRole('button', { name: 'Retry map' })).toBeNull();
    }
    expect(sdk.moduleLoads).toBe(0);
  });

  it('uses the latest geometry while the SDK import and map load are pending', async () => {
    const gate = deferred<void>();
    sdk.importGate = gate.promise;
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.moduleLoads).toBe(1));

    view.rerender(<RouteMap geometry={alternateGeometry} />);
    await act(async () => gate.resolve());
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const latestBeforeLoad: RouteCoordinate[] = [
      [81, 14],
      [81.2, 14.1],
    ];
    view.rerender(<RouteMap geometry={latestBeforeLoad} />);
    act(() => sdk.instances[0]!.emit('load'));

    expect(sdk.instances[0]!.addSource.mock.calls[0]?.[1]).toMatchObject({
      data: { geometry: { coordinates: latestBeforeLoad } },
    });
    expect(sdk.MapConstructor).toHaveBeenCalledOnce();
  });

  it('rechecks connectivity after the lazy import before creating a map', async () => {
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    setOnline(false);
    fireEvent(window, new Event('offline'));

    expect((await screen.findByRole('alert')).textContent).toContain('offline');
    expect(sdk.MapConstructor).not.toHaveBeenCalled();
    expect((view.container.querySelector('.route-map-canvas') as HTMLDivElement).hidden).toBe(true);
  });

  it('builds the fixed attributed map, route line, controls, markers, and bounded view', async () => {
    render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    expect(map.options).toMatchObject({
      style: stylePath,
      attributionControl: {},
      transformRequest: expect.any(Function),
    });
    expect(map.options).not.toHaveProperty('accessToken');
    expect(map.options).not.toHaveProperty('collectResourceTiming');
    expect(map.options).not.toHaveProperty('performanceMetricsCollection');
    expect(sdk.setWorkerUrl).toHaveBeenCalledWith('/maplibre/6.9.0/maplibre-gl-worker.mjs');
    expect(map.addControl).toHaveBeenCalledWith(sdk.controls[0], 'top-right');
    expect(ResizeObserverDouble.instances[0]!.observe).toHaveBeenCalledOnce();
    act(() => ResizeObserverDouble.instances[0]!.notify());
    expect(map.resize).toHaveBeenCalledOnce();

    act(() => map.emit('load'));
    expect(await screen.findByText('Route map ready.')).toBeTruthy();
    expect(map.addSource).toHaveBeenCalledWith(
      'routiqo-route',
      expect.objectContaining({
        type: 'geojson',
        data: expect.objectContaining({
          geometry: { type: 'LineString', coordinates: geometry },
        }),
      }),
    );
    expect(map.addLayer).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'routiqo-route-line', source: 'routiqo-route', type: 'line' }),
    );
    expect(sdk.markers.map((marker) => marker.coordinate)).toEqual([
      geometry[0],
      geometry[geometry.length - 1],
    ]);
    expect(sdk.bounds[0]!.extend.mock.calls.map((call) => call[0])).toEqual(geometry);
    expect(map.fitBounds).toHaveBeenCalledWith(sdk.bounds[0], {
      padding: 48,
      duration: 0,
      maxZoom: 15,
    });
  });

  it('restricts network map resources to safe same-origin maps paths', async () => {
    render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const transformRequest = sdk.instances[0]!.options.transformRequest as (url: string) => {
      url: string;
      credentials?: string;
    };
    const origin = window.location.origin;
    const blocked = `${origin}/maps/__blocked_map_resource__`;

    expect(transformRequest('/maps/tiles/{z}/{x}/{y}.pbf')).toEqual({
      url: `${origin}/maps/tiles/%7Bz%7D/%7Bx%7D/%7By%7D.pbf`,
      credentials: 'same-origin',
    });
    expect(transformRequest(`${origin}/maps/fonts/Noto%20Sans/0-255.pbf`)).toEqual({
      url: `${origin}/maps/fonts/Noto%20Sans/0-255.pbf`,
      credentials: 'same-origin',
    });
    expect(transformRequest('data:image/png;base64,AA==')).toEqual({
      url: 'data:image/png;base64,AA==',
    });
    expect(transformRequest(`blob:${origin}/map-image`)).toEqual({
      url: `blob:${origin}/map-image`,
    });

    for (const unsafe of [
      'https://maps.example/tiles/1.pbf',
      'data:text/html,not-a-map-image',
      '/api/maps/tiles/1.pbf',
      `${origin}/maps/tile.pbf?token=secret`,
      `${origin}/maps/tile.pbf#fragment`,
      `${origin}/maps/../private/tile.pbf`,
      `${origin}/maps/%2e%2e/private/tile.pbf`,
      `${origin}/maps/%252e%252e/private/tile.pbf`,
      `${origin}/maps/%252525252e%252525252e/private/tile.pbf`,
      `${origin}/maps/%25252525252e%25252525252e/private/tile.pbf`,
      `${origin}/maps/tiles%2fprivate.pbf`,
      `${origin}/maps/tiles%252fprivate.pbf`,
      `${origin}/maps/tiles%2525252fprivate.pbf`,
      `${origin}/maps/tiles%5cprivate.pbf`,
      `${origin}/maps/tiles\\private.pbf`,
      `${origin.replace('://', '://user@')}/maps/tile.pbf`,
      `javascript:alert(1)`,
    ]) {
      expect(transformRequest(unsafe)).toEqual({
        url: blocked,
        credentials: 'same-origin',
      });
    }
  });

  it('unwraps antimeridian geometry consistently for the line, markers, and bounds', async () => {
    const crossing: RouteCoordinate[] = [
      [179, 10],
      [-179, 10.5],
      [-178, 11],
    ];
    const expected: RouteCoordinate[] = [
      [179, 10],
      [181, 10.5],
      [182, 11],
    ];
    render(<RouteMap geometry={crossing} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));

    expect(map.addSource.mock.calls[0]?.[1]).toMatchObject({
      data: { geometry: { coordinates: expected } },
    });
    expect(sdk.markers.map((marker) => marker.coordinate)).toEqual([expected[0], expected[2]]);
    expect(sdk.bounds[0]!.extend.mock.calls.map((call) => call[0])).toEqual(expected);
  });

  it('shows a generic provider failure without exposing event details or URLs', async () => {
    render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;

    act(() =>
      map.emit('error', {
        error: new Error('private provider failure at https://provider.invalid/style'),
      }),
    );
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('Route map is unavailable. Try again later.');
    expect(alert.textContent).not.toContain('provider.invalid');
    expect(map.remove).toHaveBeenCalledOnce();
    expect((screen.getByLabelText('Map showing the selected route') as HTMLDivElement).hidden).toBe(
      true,
    );
    expect(screen.getByRole('button', { name: 'Retry map' })).toBeTruthy();
  });

  it('keeps online provider errors fatal after the map is ready', async () => {
    render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(await screen.findByText('Route map ready.')).toBeTruthy();

    act(() => map.emit('error', new Error('private online provider error')));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Route map is unavailable. Try again later.',
    );
    expect(map.remove).toHaveBeenCalledOnce();
    expect(screen.getByRole('button', { name: 'Retry map' })).toBeTruthy();
  });

  it('updates a ready map offline, retains it after tile errors, and does not retry on reconnect', async () => {
    setOnline(false);
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    expect((await screen.findByRole('alert')).textContent).toContain('offline');
    expect(sdk.MapConstructor).not.toHaveBeenCalled();

    setOnline(true);
    fireEvent(window, new Event('online'));
    fireEvent.click(screen.getByRole('button', { name: 'Retry map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(await screen.findByText('Route map ready.')).toBeTruthy();

    setOnline(false);
    fireEvent(window, new Event('offline'));
    expect(screen.getByText('You’re offline. The current map may not update.')).toBeTruthy();
    view.rerender(<RouteMap geometry={alternateGeometry} />);
    expect(sdk.sources[0]!.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        geometry: { type: 'LineString', coordinates: alternateGeometry },
      }),
    );
    expect(sdk.markers.map((marker) => marker.coordinate)).toEqual([
      alternateGeometry[0],
      alternateGeometry[alternateGeometry.length - 1],
    ]);
    expect(map.fitBounds).toHaveBeenCalledTimes(2);
    act(() =>
      map.emit('error', {
        error: new Error('private offline tile URL https://provider.invalid/tile'),
      }),
    );
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe(
      'Some map details may be unavailable. The displayed route is still available.',
    );
    expect(alert.textContent).not.toContain('provider.invalid');
    expect(screen.getByText('Route map ready.')).toBeTruthy();
    expect(map.remove).not.toHaveBeenCalled();
    setOnline(true);
    fireEvent(window, new Event('online'));
    await waitFor(() =>
      expect(screen.queryByText('You’re offline. The current map may not update.')).toBeNull(),
    );
    expect(sdk.MapConstructor).toHaveBeenCalledOnce();
    expect(map.remove).not.toHaveBeenCalled();
  });

  it('updates the source, endpoint markers, and bounds without recreating a ready map', async () => {
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(await screen.findByText('Route map ready.')).toBeTruthy();
    expect(sdk.markers).toHaveLength(2);

    view.rerender(<RouteMap geometry={alternateGeometry} />);

    expect(sdk.MapConstructor).toHaveBeenCalledOnce();
    expect(map.addSource).toHaveBeenCalledOnce();
    expect(map.addLayer).toHaveBeenCalledOnce();
    expect(sdk.sources[0]!.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        geometry: { type: 'LineString', coordinates: alternateGeometry },
      }),
    );
    expect(sdk.Marker).toHaveBeenCalledTimes(2);
    expect(sdk.markers.map((marker) => marker.coordinate)).toEqual([
      alternateGeometry[0],
      alternateGeometry[alternateGeometry.length - 1],
    ]);
    expect(map.fitBounds).toHaveBeenLastCalledWith(sdk.bounds[1], {
      padding: 48,
      duration: 0,
      maxZoom: 15,
    });
    expect(map.remove).not.toHaveBeenCalled();
  });

  it('removes a loading map for invalid geometry and ignores its late events', async () => {
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;

    view.rerender(<RouteMap geometry={[]} />);
    expect((await screen.findByRole('alert')).textContent).toBe(
      'Route map is unavailable for this route.',
    );
    expect(map.remove).toHaveBeenCalledOnce();
    expect(ResizeObserverDouble.instances[0]!.disconnect).toHaveBeenCalledOnce();
    act(() => {
      map.emit('load');
      map.emit('error', new Error('late provider error'));
    });
    expect(map.addSource).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toBe('Route map is unavailable for this route.');

    view.rerender(<RouteMap geometry={alternateGeometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Retry map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledTimes(2));
    act(() => sdk.instances[1]!.emit('load'));
    expect(await screen.findByText('Route map ready.')).toBeTruthy();
    expect(sdk.instances[1]!.addSource.mock.calls[0]?.[1]).toMatchObject({
      data: { geometry: { coordinates: alternateGeometry } },
    });
  });

  it('removes a ready map for invalid geometry so late errors cannot restore the old route', async () => {
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(await screen.findByText('Route map ready.')).toBeTruthy();

    view.rerender(
      <RouteMap
        geometry={[
          [181, 13],
          [80, 13],
        ]}
      />,
    );
    expect(map.remove).toHaveBeenCalledOnce();
    expect(sdk.markers[0]!.remove).toHaveBeenCalledOnce();
    expect(sdk.markers[1]!.remove).toHaveBeenCalledOnce();
    await waitFor(() =>
      expect((view.container.querySelector('.route-map-canvas') as HTMLDivElement).hidden).toBe(
        true,
      ),
    );
    act(() => map.emit('error', new Error('late')));
    expect(screen.queryByText('Route map ready.')).toBeNull();
    expect(screen.getByRole('alert').textContent).toBe('Route map is unavailable for this route.');
  });

  it('times out an unfinished map, ignores late load and permits a successful explicit retry', async () => {
    vi.useFakeTimers();
    render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(sdk.MapConstructor).toHaveBeenCalledOnce();
    const first = sdk.instances[0]!;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000);
    });
    expect(screen.getByRole('alert').textContent).toContain('Map loading took too long');
    expect(first.remove).toHaveBeenCalledOnce();
    expect(ResizeObserverDouble.instances[0]!.disconnect).toHaveBeenCalledOnce();
    act(() => first.emit('load'));
    expect(first.addSource).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Retry map' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(sdk.MapConstructor).toHaveBeenCalledTimes(2);
    const second = sdk.instances[1]!;
    act(() => second.emit('load'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(40000);
    });
    expect(screen.getByText('Route map ready.')).toBeTruthy();
    expect(second.remove).not.toHaveBeenCalled();
  });

  it('waits for route source processing and applies the latest pending geometry before ready', async () => {
    vi.useFakeTimers();
    const sourceGate = deferred<void>();
    sdk.sourceUpdate.promise = sourceGate.promise;
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(sdk.MapConstructor).toHaveBeenCalledOnce();
    const map = sdk.instances[0]!;

    act(() => map.emit('load'));
    expect(screen.getByText('Loading route map…')).toBeTruthy();
    expect(screen.queryByText('Route map ready.')).toBeNull();
    view.rerender(<RouteMap geometry={alternateGeometry} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(19999);
    });
    expect(screen.getByText('Loading route map…')).toBeTruthy();
    expect(map.remove).not.toHaveBeenCalled();

    await act(async () => {
      sourceGate.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.getByText('Route map ready.')).toBeTruthy();
    expect(sdk.sources[0]!.setData).toHaveBeenLastCalledWith(
      expect.objectContaining({
        geometry: { type: 'LineString', coordinates: alternateGeometry },
      }),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(40000);
    });
    expect(map.remove).not.toHaveBeenCalled();
  });

  it('times out while route source processing is still pending and ignores its late completion', async () => {
    vi.useFakeTimers();
    const sourceGate = deferred<void>();
    sdk.sourceUpdate.promise = sourceGate.promise;
    render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(sdk.sources[0]!.setData).toHaveBeenCalledOnce();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000);
    });
    expect(screen.getByRole('alert').textContent).toContain('Map loading took too long');
    expect(map.remove).toHaveBeenCalledOnce();

    await act(async () => {
      sourceGate.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.queryByText('Route map ready.')).toBeNull();
    expect(map.remove).toHaveBeenCalledOnce();
  });

  it('removes a source-processing map when geometry becomes invalid and ignores completion', async () => {
    const sourceGate = deferred<void>();
    sdk.sourceUpdate.promise = sourceGate.promise;
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(screen.getByText('Loading route map…')).toBeTruthy();

    view.rerender(<RouteMap geometry={[]} />);
    expect((await screen.findByRole('alert')).textContent).toBe(
      'Route map is unavailable for this route.',
    );
    expect(map.remove).toHaveBeenCalledOnce();
    expect(sdk.markers[0]!.remove).toHaveBeenCalledOnce();
    expect(sdk.markers[1]!.remove).toHaveBeenCalledOnce();

    await act(async () => {
      sourceGate.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(screen.queryByText('Route map ready.')).toBeNull();
    expect(screen.getByRole('alert').textContent).toBe('Route map is unavailable for this route.');
  });

  it('cleans up when unmounted during route source processing', async () => {
    const sourceGate = deferred<void>();
    sdk.sourceUpdate.promise = sourceGate.promise;
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(screen.getByText('Loading route map…')).toBeTruthy();

    view.unmount();
    expect(map.remove).toHaveBeenCalledOnce();
    expect(ResizeObserverDouble.instances[0]!.disconnect).toHaveBeenCalledOnce();
    expect(sdk.markers[0]!.remove).toHaveBeenCalledOnce();
    expect(sdk.markers[1]!.remove).toHaveBeenCalledOnce();

    await act(async () => {
      sourceGate.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(map.remove).toHaveBeenCalledOnce();
  });

  it('cleans up the ready map, observer, and markers on unmount', async () => {
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));
    expect(await screen.findByText('Route map ready.')).toBeTruthy();

    view.unmount();
    expect(map.remove).toHaveBeenCalledOnce();
    expect(ResizeObserverDouble.instances[0]!.disconnect).toHaveBeenCalledOnce();
    expect(sdk.markers[0]!.remove).toHaveBeenCalledOnce();
    expect(sdk.markers[1]!.remove).toHaveBeenCalledOnce();
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (failure: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}
