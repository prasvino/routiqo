import * as SecureStore from 'expo-secure-store';
import { createSessionVault } from './session-vault';

const key = 'routiqo.session.v1';
const options: SecureStore.SecureStoreOptions = {
  keychainService: 'com.routiqo.session.v1',
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
  requireAuthentication: false,
};

async function available() {
  if (!(await SecureStore.isAvailableAsync())) throw new Error('Secure storage is unavailable.');
}

// The only owner of this native key. No credentials are cached in module state.
export const nativeSessionVault = createSessionVault({
  async read() {
    await available();
    return SecureStore.getItemAsync(key, options);
  },
  async write(value) {
    await available();
    await SecureStore.setItemAsync(key, value, options);
  },
  async remove() {
    await available();
    await SecureStore.deleteItemAsync(key, options);
  },
});
