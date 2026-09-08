import createClient from 'openapi-fetch';
import type { paths } from './schema';
export function createRoutiqoClient(baseUrl: string) {
  return createClient<paths>({ baseUrl });
}
export type { paths, components } from './schema';
