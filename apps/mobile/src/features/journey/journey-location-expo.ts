import * as Location from 'expo-location';
import { createJourneyLocationStore, type LocationDriver } from './journey-location';

const permission = (response: Location.LocationPermissionResponse) =>
  response.granted
    ? ('granted' as const)
    : response.status === Location.PermissionStatus.UNDETERMINED
      ? ('undetermined' as const)
      : response.canAskAgain
        ? ('denied' as const)
        : ('blocked' as const);

/** expo-location with while-in-use permission only; no background or task APIs. */
const expoLocationDriver: LocationDriver = {
  permission: async () => permission(await Location.getForegroundPermissionsAsync()),
  request: async () => permission(await Location.requestForegroundPermissionsAsync()),
  servicesEnabled: () => Location.hasServicesEnabledAsync(),
  async watch(onFix, onError) {
    const subscription = await Location.watchPositionAsync(
      {
        accuracy: Location.Accuracy.Balanced,
        timeInterval: 30_000,
        distanceInterval: 50,
        mayShowUserSettingsDialog: false,
      },
      (reading) =>
        onFix({
          coordinate: [reading.coords.longitude, reading.coords.latitude],
          accuracyMetres: reading.coords.accuracy ?? null,
          at: reading.timestamp,
        }),
      () => onError(),
    );
    return () => subscription.remove();
  },
};

/** One app-wide store; its readings live only in memory. */
export const journeyLocation = createJourneyLocationStore(expoLocationDriver);
