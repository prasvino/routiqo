import { expect, it } from 'vitest';
import config from '../apps/web/next.config';

it('allows only same-origin location while leaving camera and microphone denied', async () => {
  const rules = await config.headers!();
  const global = rules.find((rule) => rule.source === '/(.*)');
  expect(global?.headers.find((header) => header.key === 'Permissions-Policy')?.value).toBe(
    'geolocation=(self), microphone=(), camera=()',
  );
});
