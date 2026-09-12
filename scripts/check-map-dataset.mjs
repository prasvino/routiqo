import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { open, realpath, stat } from 'node:fs/promises';
import { isAbsolute, join, relative, sep, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const manifestLimit = 64 * 1024;
const artifactLimit = 256 * 1024 ** 3;
const failure = () =>
  new Error('Dataset integrity check failed. Review the local inventory and artifacts.');

function exactKeys(value, keys) {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key))
  );
}

function text(value, maximum) {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= maximum &&
    value.trim() === value &&
    !Array.from(value).some((character) => {
      const code = character.codePointAt(0);
      return code < 32 || (code >= 127 && code <= 159);
    })
  );
}

function inside(root, target) {
  const child = relative(root, target);
  return child !== '' && !isAbsolute(child) && child !== '..' && !child.startsWith(`..${sep}`);
}

export function validateDatasetManifest(value) {
  if (
    !exactKeys(value, ['version', 'region', 'bounds', 'artifacts']) ||
    value.version !== 1 ||
    typeof value.region !== 'string' ||
    !/^[a-z0-9][a-z0-9-]{0,63}$/.test(value.region) ||
    !Array.isArray(value.bounds) ||
    value.bounds.length !== 4 ||
    !value.bounds.every(Number.isFinite)
  )
    throw failure();
  const [west, south, east, north] = value.bounds;
  if (
    west < -180 ||
    east > 180 ||
    south < -90 ||
    north > 90 ||
    west >= east ||
    south >= north ||
    east - west >= 180 ||
    !Array.isArray(value.artifacts) ||
    value.artifacts.length !== 3
  )
    throw failure();
  const roles = new Set();
  const paths = new Set();
  for (const artifact of value.artifacts) {
    if (
      !exactKeys(artifact, ['role', 'path', 'bytes', 'sha256', 'source', 'attribution']) ||
      !['valhalla', 'photon', 'tiles'].includes(artifact.role) ||
      roles.has(artifact.role) ||
      !text(artifact.path, 240) ||
      !artifact.path
        .split('/')
        .every(
          (part) =>
            /^[a-zA-Z0-9_-][a-zA-Z0-9_.-]*$/.test(part) &&
            !part.endsWith('.') &&
            !/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(part),
        ) ||
      paths.has(artifact.path.toLowerCase()) ||
      !Number.isSafeInteger(artifact.bytes) ||
      artifact.bytes < 1 ||
      artifact.bytes > artifactLimit ||
      typeof artifact.sha256 !== 'string' ||
      !/^[a-f0-9]{64}$/.test(artifact.sha256) ||
      !text(artifact.source, 2048) ||
      !text(artifact.attribution, 2048)
    )
      throw failure();
    try {
      const source = new URL(artifact.source);
      if (
        source.protocol !== 'https:' ||
        !source.hostname ||
        source.username ||
        source.password ||
        source.search ||
        source.hash
      )
        throw failure();
    } catch {
      throw failure();
    }
    roles.add(artifact.role);
    paths.add(artifact.path.toLowerCase());
  }
  return value;
}

export async function checkMapDataset(directory) {
  try {
    const root = await realpath(resolve(directory));
    const manifestPath = await realpath(join(root, 'manifest.json'));
    if (!inside(root, manifestPath)) throw failure();
    const initial = await stat(manifestPath);
    if (!initial.isFile() || initial.size < 1 || initial.size > manifestLimit) throw failure();
    const file = await open(manifestPath, 'r');
    let manifest;
    try {
      const info = await file.stat();
      if (!info.isFile() || info.size < 1 || info.size > manifestLimit) throw failure();
      const buffer = Buffer.alloc(manifestLimit + 1);
      let count = 0;
      while (count < buffer.length) {
        const result = await file.read(buffer, count, buffer.length - count, null);
        if (!result.bytesRead) break;
        count += result.bytesRead;
      }
      if (count > manifestLimit) throw failure();
      manifest = validateDatasetManifest(
        JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(buffer.subarray(0, count))),
      );
    } finally {
      await file.close();
    }
    const files = [];
    const paths = new Set();
    for (const artifact of manifest.artifacts) {
      const path = await realpath(join(root, artifact.path));
      if (!inside(root, path) || paths.has(path.toLowerCase())) throw failure();
      const info = await stat(path);
      if (!info.isFile() || info.size !== artifact.bytes) throw failure();
      paths.add(path.toLowerCase());
      files.push({ artifact, path, info });
    }
    for (const { artifact, path, info } of files) {
      const stream = createReadStream(path, { highWaterMark: 1024 * 1024 });
      const timer = setTimeout(() => stream.destroy(failure()), 5 * 60 * 1000);
      let bytes = 0;
      const hash = createHash('sha256');
      try {
        for await (const chunk of stream) {
          bytes += chunk.length;
          if (bytes > artifact.bytes) throw failure();
          hash.update(chunk);
        }
        const after = await stat(path);
        if (
          bytes !== artifact.bytes ||
          hash.digest('hex') !== artifact.sha256 ||
          after.size !== info.size ||
          after.mtimeMs !== info.mtimeMs
        )
          throw failure();
      } finally {
        clearTimeout(timer);
        stream.destroy();
      }
    }
  } catch {
    throw failure();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2).filter((arg, index) => !(index === 0 && arg === '--'));
  if (args.length !== 1) {
    console.error('Usage: pnpm maps:check -- <dataset-directory>');
    process.exitCode = 1;
  } else {
    try {
      await checkMapDataset(args[0]);
      console.log(
        'Dataset artifact integrity verified. Service readiness and coverage quality remain unverified.',
      );
    } catch {
      console.error(failure().message);
      process.exitCode = 1;
    }
  }
}
