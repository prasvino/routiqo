import { beforeEach, describe, expect, it, vi } from 'vitest';

const secure = vi.hoisted(() => ({
  isAvailableAsync: vi.fn(),
  getItemAsync: vi.fn(),
  setItemAsync: vi.fn(),
  deleteItemAsync: vi.fn(),
  WHEN_UNLOCKED_THIS_DEVICE_ONLY: 6,
}));
// Resolve the mobile workspace's native dependency, not a root/virtual package ID.
vi.mock('../apps/mobile/node_modules/expo-secure-store', () => secure);

const key = 'routiqo.session.v1';
const options = {
  keychainService: 'com.routiqo.session.v1',
  keychainAccessible: 6,
  requireAuthentication: false,
};

beforeEach(() => {
  vi.resetModules();
  vi.resetAllMocks();
  secure.isAvailableAsync.mockResolvedValue(true);
  secure.getItemAsync.mockResolvedValue(null);
});

describe('native SecureStore session adapter', () => {
  it('uses the same fixed key and device-only options for read, write and removal', async () => {
    const { nativeSessionVault } = await import('../apps/mobile/src/auth/secure-session');
    expect(await nativeSessionVault.load()).toBeNull();
    const session = {
      accountId: '00000000-0000-4000-8000-000000000001',
      credential: 's'.repeat(43),
      expiresAt: Date.now() + 600_000,
    };
    await nativeSessionVault.commit(nativeSessionVault.beginWrite(), session);
    await nativeSessionVault.clear();
    expect(secure.getItemAsync).toHaveBeenCalledWith(key, options);
    expect(secure.setItemAsync).toHaveBeenCalledWith(
      key,
      JSON.stringify({ version: 1, ...session }),
      options,
    );
    expect(secure.deleteItemAsync).toHaveBeenCalledWith(key, options);
  });

  it('fails closed when secure storage is unavailable and recovers only after clear', async () => {
    const { nativeSessionVault } = await import('../apps/mobile/src/auth/secure-session');
    secure.isAvailableAsync.mockResolvedValue(false);
    await expect(nativeSessionVault.load()).rejects.toThrow();
    expect(secure.getItemAsync).not.toHaveBeenCalled();
    secure.isAvailableAsync.mockResolvedValue(true);
    await expect(nativeSessionVault.load()).rejects.toThrow();
    await nativeSessionVault.clear();
    expect(await nativeSessionVault.load()).toBeNull();
  });
});
