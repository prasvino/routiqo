import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { expect, it } from 'vitest';

const mobileRequire = createRequire(new URL('../apps/mobile/package.json', import.meta.url));
const secureRoot = dirname(mobileRequire.resolve('expo-secure-store/package.json'));
const sqliteRoot = dirname(mobileRequire.resolve('expo-sqlite/package.json'));

it('keeps native journal SQLite files outside the configured Android cloud and device backup allowlists', () => {
  const config = JSON.parse(
    readFileSync(new URL('../apps/mobile/app.json', import.meta.url), 'utf8'),
  ) as { expo: { plugins: unknown[] } };
  expect(config.expo.plugins).toContainEqual([
    'expo-secure-store',
    { configureAndroidBackup: true, faceIDPermission: false },
  ]);
  // Expo SQLite's default files/SQLite directory is in Android's "file" domain.
  // An explicit include list backs up only those domains/paths, not other files.
  const sqlite = readFileSync(
    join(sqliteRoot, 'android/src/main/java/expo/modules/sqlite/SQLiteModule.kt'),
    'utf8',
  );
  expect(sqlite).toContain('context.filesDir.canonicalPath + File.separator + "SQLite"');
  for (const [file, sections] of [
    ['secure_store_backup_rules.xml', ['full-backup-content']],
    ['secure_store_data_extraction_rules.xml', ['cloud-backup', 'device-transfer']],
  ] as const) {
    const xml = readFileSync(join(secureRoot, 'android/src/main/res/xml', file), 'utf8');
    for (const section of sections) {
      const body = new RegExp(`<${section}(?:\\s[^>]*)?>([\\s\\S]*?)</${section}>`).exec(xml)?.[1];
      expect(body, `${file}: ${section} must have an explicit inclusion policy`).toBeDefined();
      const includes = body!.match(/<include\b[^>]*\/>/g) ?? [];
      expect(includes).toHaveLength(1);
      expect(includes[0]).toMatch(/domain="sharedpref"/);
      expect(includes[0]).toMatch(/path="\."/);
      expect(body).toMatch(/<exclude\s+domain="sharedpref"\s+path="SecureStore"\s*\/>/);
    }
  }
});
