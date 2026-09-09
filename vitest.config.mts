import { defineConfig } from 'vitest/config';
export default defineConfig({
  oxc: { jsx: { runtime: 'automatic' } },
  test: {
    include: ['packages/**/*.test.ts', 'tests/**/*.test.ts', 'apps/web/**/*.test.tsx'],
    environment: 'node',
  },
});
