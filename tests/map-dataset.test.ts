import { createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm, mkdir, symlink, rmdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { execFileSync } from 'node:child_process';
import { afterEach, expect, it } from 'vitest';
import { checkMapDataset, validateDatasetManifest } from '../scripts/check-map-dataset.mjs';

const directories: string[] = [];
const content = 'Synthetic artifact, not provider data.';
const manifest = () => ({
  version: 1,
  region: 'synthetic-region',
  bounds: [78, 11, 81, 14],
  artifacts: ['valhalla', 'photon', 'tiles'].map((role) => ({
    role,
    path: `${role}.bin`,
    bytes: Buffer.byteLength(content),
    sha256: createHash('sha256').update(content).digest('hex'),
    source: 'https://example.test/source',
    attribution: 'Synthetic test data',
  })),
});

async function dataset() {
  const root = await mkdtemp(join(tmpdir(), 'routiqo-dataset-test-'));
  directories.push(root);
  const value = manifest();
  await writeFile(join(root, 'manifest.json'), JSON.stringify(value));
  for (const artifact of value.artifacts) await writeFile(join(root, artifact.path), content);
  return root;
}

afterEach(async () => {
  for (const root of directories.splice(0)) {
    const absolute = resolve(root);
    if (
      !absolute.startsWith(resolve(tmpdir()) + sep) ||
      !absolute.includes('routiqo-dataset-test-')
    ) {
      throw new Error('Unexpected test directory');
    }
    await rm(absolute, { recursive: true, force: true });
  }
});

it('verifies staged bytes without claiming live provider readiness', async () => {
  const root = await dataset();
  await expect(checkMapDataset(root)).resolves.toBeUndefined();
  const output = execFileSync(process.execPath, ['scripts/check-map-dataset.mjs', root], {
    encoding: 'utf8',
  });
  expect(output).toContain('integrity verified');
  expect(output).toContain('remain unverified');
  expect(output).not.toContain(root);
});

it('rejects missing files, incorrect sizes and same-size corruption', async () => {
  const root = await dataset();
  await writeFile(join(root, 'tiles.bin'), 'x'.repeat(Buffer.byteLength(content)));
  await expect(checkMapDataset(root)).rejects.toThrow('Dataset integrity check failed');
  await writeFile(join(root, 'tiles.bin'), 'short');
  await expect(checkMapDataset(root)).rejects.toThrow('Dataset integrity check failed');
  await rm(join(root, 'tiles.bin'));
  await expect(checkMapDataset(root)).rejects.toThrow('Dataset integrity check failed');
});

it('rejects traversal, absolute paths, alternate streams and duplicate artifacts', () => {
  for (const path of [
    '../secret',
    '/secret',
    'C:/secret',
    'tiles.bin:secret',
    'a\\secret',
    'a//b',
    'a/./b',
  ]) {
    const value = manifest();
    value.artifacts[0]!.path = path;
    expect(() => validateDatasetManifest(value)).toThrow('Dataset integrity check failed');
  }
  const duplicate = manifest();
  duplicate.artifacts[1]!.path = duplicate.artifacts[0]!.path.toUpperCase();
  expect(() => validateDatasetManifest(duplicate)).toThrow();
  duplicate.artifacts[1]!.path = 'unique.bin';
  duplicate.artifacts[1]!.role = 'valhalla';
  expect(() => validateDatasetManifest(duplicate)).toThrow();
});

it('rejects invalid coverage and unsafe or incomplete metadata', () => {
  for (const path of ['NUL', 'CON.txt', 'AUX/data.bin', 'folder/com1.bin', 'LPT9', 'prn.dat']) {
    const value = manifest();
    value.artifacts[0]!.path = path;
    expect(() => validateDatasetManifest(value)).toThrow();
  }
  for (const bounds of [
    [81, 11, 78, 14],
    [-180, -90, 180, 90],
    [78, 14, 81, 11],
    [78, 11, NaN, 14],
  ]) {
    expect(() => validateDatasetManifest({ ...manifest(), bounds })).toThrow();
  }
  for (const source of [
    'http://example.test/',
    'https://user:private@example.test/',
    'https://example.test/?private=1',
  ]) {
    const value = manifest();
    value.artifacts[0]!.source = source;
    expect(() => validateDatasetManifest(value)).toThrow();
  }
  expect(() => validateDatasetManifest({ ...manifest(), version: 2 })).toThrow();
  expect(() => validateDatasetManifest({ ...manifest(), artifacts: [] })).toThrow();
  expect(() => validateDatasetManifest({ ...manifest(), extra: true })).toThrow();
});

it('bounds manifest reads and rejects malformed UTF-8 and non-files', async () => {
  const root = await dataset();
  await rm(join(root, 'manifest.json'));
  await mkdir(join(root, 'manifest.json'));
  await expect(checkMapDataset(root)).rejects.toThrow();
  // This is the empty directory created just above, inside the test-owned root.
  await rmdir(join(root, 'manifest.json'));
  await writeFile(join(root, 'manifest.json'), ' '.repeat(65537));
  await expect(checkMapDataset(root)).rejects.toThrow();
  await writeFile(join(root, 'manifest.json'), Buffer.from([0xff]));
  await expect(checkMapDataset(root)).rejects.toThrow();
  await writeFile(join(root, 'manifest.json'), JSON.stringify(manifest()));
  await rm(join(root, 'tiles.bin'));
  await mkdir(join(root, 'tiles.bin'));
  await expect(checkMapDataset(root)).rejects.toThrow();
});

it('redacts underlying filesystem and metadata errors', async () => {
  const root = await dataset();
  const value = manifest();
  value.artifacts[0]!.path = 'private-missing.bin';
  await writeFile(join(root, 'manifest.json'), JSON.stringify(value));
  try {
    await checkMapDataset(root);
    throw new Error('Expected rejection');
  } catch (error) {
    expect(error).toBeInstanceOf(Error);
    expect((error as Error).message).toBe(
      'Dataset integrity check failed. Review the local inventory and artifacts.',
    );
    expect((error as Error).cause).toBeUndefined();
  }
});

it('rejects a directory junction that escapes the selected dataset', async () => {
  const root = await dataset();
  const outside = await dataset();
  const link = join(root, 'linked');
  await symlink(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
  try {
    const value = manifest();
    value.artifacts[0]!.path = 'linked/valhalla.bin';
    await writeFile(join(root, 'manifest.json'), JSON.stringify(value));
    await expect(checkMapDataset(root)).rejects.toThrow('Dataset integrity check failed');
  } finally {
    // Remove only this created link before cleanup; never recurse into its target.
    await rm(link);
  }
});
