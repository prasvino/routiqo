import { requireNativeModule } from 'expo';
import { createNativeTransport, type NativeHttpDriver } from './safe-transport';

export const nativeTransport = createNativeTransport(
  {
    // The native module always takes seven arguments; ifNoneMatch is null except for the Spot catalog.
    request: (origin, path, method, credential, accountId, payload, ifNoneMatch) =>
      requireNativeModule<NativeHttpDriver>('RoutiqoSafeHttp').request(
        origin,
        path,
        method,
        credential,
        accountId,
        payload,
        ifNoneMatch ?? null,
      ),
  },
  process.env.EXPO_PUBLIC_ROUTIQO_API_ORIGIN,
);
