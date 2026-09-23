import { requireNativeModule } from 'expo';
import { createNativeTransport, type NativeHttpDriver } from './safe-transport';

export const nativeTransport = createNativeTransport(
  {
    request: (...arguments_: Parameters<NativeHttpDriver['request']>) =>
      requireNativeModule<NativeHttpDriver>('RoutiqoSafeHttp').request(...arguments_),
  },
  process.env.EXPO_PUBLIC_ROUTIQO_API_ORIGIN,
);
