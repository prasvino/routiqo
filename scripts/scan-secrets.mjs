import { spawnSync } from 'node:child_process';
import { copyFileSync, lstatSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';

const root = process.cwd();
const listing = spawnSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], {
  encoding: 'utf8',
  maxBuffer: 10 * 1024 * 1024,
});
if (listing.status !== 0)
  throw new Error('Could not enumerate repository files for secret scanning.');
const staging = mkdtempSync(join(tmpdir(), 'routiqo-secret-scan-'));
try {
  for (const file of new Set(listing.stdout.split('\0').filter(Boolean))) {
    const source = resolve(root, file);
    const withinRoot = relative(root, source);
    if (isAbsolute(withinRoot) || withinRoot.startsWith('..'))
      throw new Error('Invalid repository path.');
    // Never follow a link into files outside the project.
    if (lstatSync(source).isSymbolicLink())
      throw new Error('Review symbolic links before scanning.');
    const target = join(staging, file);
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(source, target);
  }
  const result = spawnSync(
    'docker',
    [
      'run',
      '--rm',
      '--network',
      'none',
      '--mount',
      `type=bind,source=${staging},target=/scan,readonly`,
      'ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f',
      'dir',
      '/scan',
      '--config',
      '/scan/.gitleaks.toml',
      '--redact',
      '--verbose',
      '--no-banner',
    ],
    { stdio: 'inherit' },
  );
  process.exitCode = result.status ?? 1;
} finally {
  // Staging is created above under the OS temporary directory and never taken from user input.
  rmSync(staging, { recursive: true });
}
