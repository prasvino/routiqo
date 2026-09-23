import { expect, it } from 'vitest';
import { nativeMapStyle } from '../apps/mobile/src/features/journey/native-map-config';

it('loads only an explicitly configured regional map style on the trusted HTTPS host', () => {
  expect(nativeMapStyle('https://maps.routiqo.example', '/maps/tamil-nadu/style.json')).toBe(
    'https://maps.routiqo.example/maps/tamil-nadu/style.json',
  );
  for (const path of [
    undefined,
    'https://public.example/style.json',
    '/other/style.json',
    '/maps//style.json',
    '/maps/../style.json',
    '/maps/style.json?token=x',
  ])
    expect(nativeMapStyle('https://maps.routiqo.example', path)).toBeNull();
  expect(nativeMapStyle('http://maps.routiqo.example', '/maps/style.json')).toBeNull();
});
