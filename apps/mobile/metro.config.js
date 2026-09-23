/* eslint-disable @typescript-eslint/no-require-imports -- Expo loads metro.config.js as CommonJS. */
const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
const repository = path
  .resolve(__dirname, '../..')
  .replaceAll('\\', '/')
  .replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  .replaceAll('/', '[\\\\/]');
const generated = new RegExp(
  `^${repository}[\\\\/](?:\\.local-gradle-android|\\.patch-work|\\.gradle|\\.pnpm-store|test-results|playwright-report|coverage|apps[\\\\/]mobile[\\\\/]android|backend[\\\\/][^\\\\/]+[\\\\/]build)(?:[\\\\/]|$)`,
);
const existing = config.resolver.blockList;
config.resolver.blockList = [
  ...(Array.isArray(existing) ? existing : existing ? [existing] : []),
  generated,
];

module.exports = config;
