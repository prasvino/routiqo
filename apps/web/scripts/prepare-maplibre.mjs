import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const packagePath = require.resolve('maplibre-gl/package.json');
const { version } = JSON.parse(readFileSync(packagePath, 'utf8'));
if (version !== '6.9.0') throw new Error('Review MapLibre worker packaging before upgrading.');
const packageDirectory = path.dirname(packagePath);
const destination = fileURLToPath(new URL('../public/maplibre/6.9.0/', import.meta.url));
mkdirSync(destination, { recursive: true });
// Turbopack does not emit the worker's shared sibling automatically.
for (const name of ['maplibre-gl-worker.mjs', 'maplibre-gl-shared.mjs']) {
  copyFileSync(path.join(packageDirectory, 'dist', name), path.join(destination, name));
}
copyFileSync(path.join(packageDirectory, 'LICENSE.txt'), path.join(destination, 'LICENSE.txt'));
