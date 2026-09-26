import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SQLiteProvider } from 'expo-sqlite';
import { MobilePlanningProvider, initializeStorage } from '../src/storage/planning';
import { NativeAccountProvider } from '../src/auth/native-account-provider';
import { SpotsProvider } from '../src/features/spots/spots-provider';
export default function RootLayout() {
  return (
    <SQLiteProvider databaseName="routiqo.db" onInit={initializeStorage}>
      <MobilePlanningProvider>
        <NativeAccountProvider>
          <SpotsProvider>
            <StatusBar style="dark" />
            <Stack screenOptions={{ headerShown: false }}>
              <Stack.Screen name="(tabs)" />
              {/* Full-screen Journey mode above the tabs, not a fifth tab. */}
              <Stack.Screen name="journey" />
            </Stack>
          </SpotsProvider>
        </NativeAccountProvider>
      </MobilePlanningProvider>
    </SQLiteProvider>
  );
}
