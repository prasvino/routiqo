import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { destinations } from '../packages/shared/src/catalog';

describe('shared discovery assets', () => {
  it('ships every destination image in both applications with accessible descriptions', () => {
    expect(new Set(destinations.map((place) => place.id)).size).toBe(destinations.length);
    for (const place of destinations) {
      expect(place.image).toMatch(/^\/images\/[a-z-]+\.jpg$/);
      expect(place.imageAlt.trim().length).toBeGreaterThan(15);
      expect(['Coast', 'Hills', 'Heritage']).toContain(place.category);
      expect(existsSync(resolve('apps/web/public', place.image.slice(1)))).toBe(true);
      expect(existsSync(resolve('apps/mobile/assets', place.image.split('/').at(-1)!))).toBe(true);
    }
  });
});
