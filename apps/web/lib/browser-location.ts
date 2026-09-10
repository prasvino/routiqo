import { readRouteCoordinate, type RouteCoordinate } from '@routiqo/shared';
export class BrowserLocationError extends Error {}
export interface BrowserLocation {
  coordinate: RouteCoordinate;
  accuracyMetres: number;
}
/** One deliberate reading. Aborting ignores late callbacks; it cannot dismiss an OS prompt. */
export function readBrowserLocation(signal?: AbortSignal): Promise<BrowserLocation> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Location cancelled.', 'AbortError'));
      return;
    }
    if (!navigator.geolocation) {
      reject(
        new BrowserLocationError(
          'Location is unavailable in this browser. Search for your starting place.',
        ),
      );
      return;
    }
    let done = false;
    const settle = (result?: BrowserLocation, failure?: Error) => {
      if (done) return;
      done = true;
      clearTimeout(timeout);
      signal?.removeEventListener('abort', abort);
      if (result) resolve(result);
      else reject(failure);
    };
    const abort = () => settle(undefined, new DOMException('Location cancelled.', 'AbortError'));
    const timeout = setTimeout(
      () =>
        settle(
          undefined,
          new BrowserLocationError(
            'Location took too long. Try again or search for a starting place.',
          ),
        ),
      12000,
    );
    signal?.addEventListener('abort', abort, { once: true });
    try {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          if (done) return;
          try {
            const coordinate = readRouteCoordinate([
              position.coords.longitude,
              position.coords.latitude,
            ]);
            const accuracyMetres = position.coords.accuracy;
            if (
              !Number.isFinite(accuracyMetres) ||
              accuracyMetres < 0 ||
              !Number.isFinite(position.timestamp) ||
              Math.abs(Date.now() - position.timestamp) > 30000
            )
              throw new Error();
            if (accuracyMetres > 1000) {
              settle(
                undefined,
                new BrowserLocationError(
                  'Your location is too approximate. Search for a starting place.',
                ),
              );
              return;
            }
            settle({ coordinate, accuracyMetres });
          } catch {
            settle(
              undefined,
              new BrowserLocationError(
                'A reliable location was not returned. Search for a starting place.',
              ),
            );
          }
        },
        (failure) =>
          settle(
            undefined,
            new BrowserLocationError(
              failure.code === 1
                ? 'Location permission was denied. You can search for your starting place instead.'
                : failure.code === 3
                  ? 'Location took too long. Try again or search for a starting place.'
                  : 'Your location could not be found. Search for a starting place.',
            ),
          ),
        { enableHighAccuracy: false, maximumAge: 0, timeout: 10000 },
      );
    } catch {
      settle(
        undefined,
        new BrowserLocationError('Location is unavailable. Search for a starting place.'),
      );
    }
  });
}
