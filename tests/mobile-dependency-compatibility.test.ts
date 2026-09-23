import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { expect, it } from 'vitest';
const mobile = createRequire(new URL('../apps/mobile/package.json', import.meta.url));
it('keeps Expo query parsing compatible with the patched decoder', () => {
  const router = createRequire(mobile.resolve('expo-router/package.json'));
  const query = router('query-string') as {
    parse(value: string): Record<string, string>;
    stringify(value: Record<string, string>): string;
  };
  expect(query.parse('place=Pondicherry&label=caf%C3%A9')).toEqual({
    place: 'Pondicherry',
    label: 'café',
  });
  expect(query.parse(query.stringify({ route: 'Chennai → coast' }))).toEqual({
    route: 'Chennai → coast',
  });
  const malformed = '%FF'.repeat(10000);
  expect(query.parse(`value=${malformed}`).value).toBe(malformed);
}, 2000);
it('keeps Xcode project IDs compatible with the patched UUID dependency', () => {
  const expo = createRequire(mobile.resolve('expo/package.json'));
  const plugins = createRequire(expo.resolve('@expo/config-plugins'));
  const xcode = plugins('xcode') as {
    project(path: string): {
      generateUuid(): string;
      hash: { project: { objects: Record<string, unknown> } };
    };
  };
  const project = xcode.project('synthetic-unused.pbxproj');
  project.hash = { project: { objects: {} } };
  const first = project.generateUuid();
  expect(first).toMatch(/^[A-F0-9]{24}$/);
  expect(project.generateUuid()).not.toBe(first);
});
it('matches the Nitro runtime to the version used to generate Google sign-in native code', () => {
  const google = mobile('react-native-nitro-google-signin/package.json') as {
    devDependencies: { 'react-native-nitro-modules': string };
  };
  const nitro = mobile('react-native-nitro-modules/package.json') as { version: string };
  expect(nitro.version).toBe(google.devDependencies['react-native-nitro-modules']);
  expect(
    existsSync(
      join(
        dirname(mobile.resolve('react-native-nitro-modules/package.json')),
        'cpp/views/ReactProp.hpp',
      ),
    ),
  ).toBe(true);
});
