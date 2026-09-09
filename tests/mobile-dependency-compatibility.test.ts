import { createRequire } from 'node:module';
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
