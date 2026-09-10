import { afterEach, expect, it, vi } from 'vitest';
import { readBrowserLocation } from '../apps/web/lib/browser-location';
const position = (accuracy = 20, timestamp = Date.now()) =>
  ({
    coords: { longitude: 80, latitude: 13, accuracy },
    timestamp,
  }) as GeolocationPosition;
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
it('makes only a bounded one-time position request without watching or networking', async () => {
  const read = vi.fn((success: PositionCallback) => success(position()));
  const watch = vi.fn();
  vi.stubGlobal('navigator', { geolocation: { getCurrentPosition: read, watchPosition: watch } });
  expect(await readBrowserLocation()).toEqual({ coordinate: [80, 13], accuracyMetres: 20 });
  expect(read).toHaveBeenCalledWith(expect.any(Function), expect.any(Function), {
    enableHighAccuracy: false,
    maximumAge: 0,
    timeout: 10000,
  });
  expect(watch).not.toHaveBeenCalled();
});
it('rejects denied, stale and excessively approximate readings', async () => {
  vi.stubGlobal('navigator', {
    geolocation: {
      getCurrentPosition: (_: PositionCallback, fail: PositionErrorCallback) =>
        fail({ code: 1 } as GeolocationPositionError),
    },
  });
  await expect(readBrowserLocation()).rejects.toThrow('denied');
  for (const point of [position(1001), position(20, Date.now() - 60000), position(NaN)]) {
    vi.stubGlobal('navigator', {
      geolocation: { getCurrentPosition: (success: PositionCallback) => success(point) },
    });
    await expect(readBrowserLocation()).rejects.toThrow();
  }
});
it('ignores a late position after cancellation and bounds a silent browser', async () => {
  let callback!: PositionCallback;
  vi.stubGlobal('navigator', {
    geolocation: {
      getCurrentPosition: (success: PositionCallback) => {
        callback = success;
      },
    },
  });
  const controller = new AbortController();
  const pending = readBrowserLocation(controller.signal);
  controller.abort();
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  callback(position());
  vi.useFakeTimers();
  const timed = expect(readBrowserLocation()).rejects.toThrow('too long');
  await vi.advanceTimersByTimeAsync(12000);
  await timed;
});
