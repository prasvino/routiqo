import { Tabs } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SQLiteProvider } from 'expo-sqlite';
import { Text } from 'react-native';
import { tokens } from '@routiqo/design-tokens';
import { MobilePlanningProvider, initializeStorage } from '../src/storage/planning';
const icons: Record<string, string> = { index: '⌂', explore: '◇', trips: '↗', profile: '○' };
export default function RootLayout() {
  return (
    <SQLiteProvider databaseName="routiqo.db" onInit={initializeStorage}>
      <MobilePlanningProvider>
        <StatusBar style="dark" />
        <Tabs
          screenOptions={({ route }) => ({
            headerTitle: 'routiqo.',
            headerTitleStyle: { color: tokens.colors.ink, fontWeight: '700', fontSize: 25 },
            headerStyle: { backgroundColor: tokens.colors.canvas },
            tabBarActiveTintColor: tokens.colors.ink,
            tabBarStyle: { backgroundColor: tokens.colors.canvas },
            tabBarIcon: ({ color }) => (
              <Text style={{ color, fontSize: 24 }}>{icons[route.name] ?? '○'}</Text>
            ),
          })}
        >
          <Tabs.Screen name="index" options={{ title: 'Home' }} />
          <Tabs.Screen name="explore" options={{ title: 'Explore' }} />
          <Tabs.Screen name="trips" options={{ title: 'Trips' }} />
          <Tabs.Screen name="profile" options={{ title: 'Profile' }} />
        </Tabs>
      </MobilePlanningProvider>
    </SQLiteProvider>
  );
}
