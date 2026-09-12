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
  addLayer: ReturnType<typeof vi.fn>;
  fitBounds: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
  remove: ReturnType<typeof vi.fn>;
  emit(name: string, value?: unknown): void;
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
  const MapConstructor = vi.fn(function (options: Record<string, unknown>) {
    const instance: MapDouble = {
      options,
      handlers: new globalThis.Map(),
      addControl: vi.fn(),
      on: vi.fn(),
      addSource: vi.fn(),
      addLayer: vi.fn(),
      fitBounds: vi.fn(),
      resize: vi.fn(),
      remove: vi.fn(),
      emit(name, value) {
        this.handlers.get(name)?.forEach((handler) => handler(value));
      },
    };
    instance.addControl.mockImplementation(() => instance);
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
    accessToken: 'global-token-must-not-change',
  };
  return {
    api,
    MapConstructor,
    NavigationControl,
    Marker,
    LngLatBounds,
    instances,
    markers,
    controls,
    bounds,
    moduleLoads: 0,
    importGate: null as Promise<void> | null,
  };
});

vi.mock('mapbox-gl', async () => {
  sdk.moduleLoads += 1;
  if (sdk.importGate) await sdk.importGate;
  return { default: sdk.api };
});

const publicToken = `pk.${'a'.repeat(40)}`;
const geometry: RouteCoordinate[] = [
  [80.2, 13.1],
  [80.25, 13.08],
  [80.3, 13.04],
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
  sdk.instances.splice(0);
  sdk.markers.splice(0);
  sdk.controls.splice(0);
  sdk.bounds.splice(0);
  sdk.importGate = null;
  ResizeObserverDouble.instances.splice(0);
}

beforeEach(() => {
  resetSdkDoubles();
  vi.stubEnv('NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN', publicToken);
  vi.stubGlobal('ResizeObserver', ResizeObserverDouble);
  setOnline(true);
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('RouteMap', () => {
  it('does not import Mapbox on mount or for non-public and oversized tokens', async () => {
    const view = render(<RouteMap geometry={geometry} />);
    expect(sdk.moduleLoads).toBe(0);
    expect(sdk.MapConstructor).not.toHaveBeenCalled();

    vi.stubEnv('NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN', `sk.${'a'.repeat(40)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    expect((await screen.findByRole('alert')).textContent).toBe(
      'Map display is unavailable. You can still review the directions.',
    );
    expect((view.container.querySelector('.route-map-canvas') as HTMLDivElement).hidden).toBe(true);
    expect(sdk.moduleLoads).toBe(0);
    expect(screen.queryByRole('button', { name: 'Retry map' })).toBeNull();

    view.rerender(<RouteMap key="oversized" geometry={geometry} />);
    vi.stubEnv('NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN', `pk.${'a'.repeat(2046)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    expect((await screen.findByRole('alert')).textContent).toBe(
      'Map display is unavailable. You can still review the directions.',
    );
    expect((view.container.querySelector('.route-map-canvas') as HTMLDivElement).hidden).toBe(true);
    expect(sdk.moduleLoads).toBe(0);
  });

  it('ignores a dynamic import that resolves after unmount', async () => {
    const gate = deferred<void>();
    sdk.importGate = gate.promise;
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.moduleLoads).toBe(1));

    view.unmount();
    await act(async () => gate.resolve());
    expect(sdk.MapConstructor).not.toHaveBeenCalled();
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
      style: 'mapbox://styles/mapbox/streets-v12',
      accessToken: publicToken,
      attributionControl: true,
      collectResourceTiming: false,
      performanceMetricsCollection: false,
    });
    expect(sdk.api.accessToken).toBe('global-token-must-not-change');
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

  it('keeps a ready map while offline and does not recreate it on reconnect', async () => {
    setOnline(false);
    render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    expect((await screen.findByRole('alert')).textContent).toContain('offline');
    expect(sdk.MapConstructor).not.toHaveBeenCalled();

    setOnline(true);
    fireEvent(window, new Event('online'));
    fireEvent.click(screen.getByRole('button', { name: 'Retry map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const map = sdk.instances[0]!;
    act(() => map.emit('load'));

    setOnline(false);
    fireEvent(window, new Event('offline'));
    expect(screen.getByText('You’re offline. The current map may not update.')).toBeTruthy();
    expect(map.remove).not.toHaveBeenCalled();
    setOnline(true);
    fireEvent(window, new Event('online'));
    await waitFor(() =>
      expect(screen.queryByText('You’re offline. The current map may not update.')).toBeNull(),
    );
    expect(sdk.MapConstructor).toHaveBeenCalledOnce();
    expect(map.remove).not.toHaveBeenCalled();
  });

  it('cleans up markers and maps on geometry changes and ignores late events', async () => {
    const nextGeometry: RouteCoordinate[] = [
      [79.9, 12.9],
      [80.05, 13],
    ];
    const view = render(<RouteMap geometry={geometry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Show map' }));
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledOnce());
    const first = sdk.instances[0]!;
    act(() => first.emit('load'));
    expect(sdk.markers).toHaveLength(2);

    view.rerender(<RouteMap geometry={nextGeometry} />);
    expect(first.remove).toHaveBeenCalledOnce();
    expect(ResizeObserverDouble.instances[0]!.disconnect).toHaveBeenCalledOnce();
    expect(sdk.markers[0]!.remove).toHaveBeenCalledOnce();
    expect(sdk.markers[1]!.remove).toHaveBeenCalledOnce();
    await waitFor(() => expect(sdk.MapConstructor).toHaveBeenCalledTimes(2));
    const second = sdk.instances[1]!;
    const sourceCalls = first.addSource.mock.calls.length;
    act(() => first.emit('load'));
    expect(first.addSource).toHaveBeenCalledTimes(sourceCalls);

    view.unmount();
    expect(second.remove).toHaveBeenCalledOnce();
    expect(ResizeObserverDouble.instances[1]!.disconnect).toHaveBeenCalledOnce();
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
