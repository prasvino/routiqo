import data from './catalog.json';
import type { components } from '@routiqo/api-client';
export const categories = ['All', 'Coast', 'Hills', 'Heritage'] as const;
export type Category = (typeof categories)[number];
export type Destination = components['schemas']['Destination'];
export const destinations: readonly Destination[] = data.map((place) => {
  const category = place.category;
  if (category !== 'Coast' && category !== 'Hills' && category !== 'Heritage')
    throw new Error('Unsupported catalog category');
  return { ...place, category };
});
export function searchDestinations(query: string, category: Category = 'All'): Destination[] {
  const term = query.trim().toLocaleLowerCase('en-IN');
  return destinations.filter(
    (place) =>
      (category === 'All' || place.category === category) &&
      [place.name, place.region, place.category, ...place.highlights]
        .join(' ')
        .toLocaleLowerCase('en-IN')
        .includes(term),
  );
}
