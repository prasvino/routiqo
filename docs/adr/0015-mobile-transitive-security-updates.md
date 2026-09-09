# ADR0015: Mobile dependency security updates

Status: accepted; compatibility tests and Android export passed,2026-09-09.

The production dependency audit reported two moderate advisories: GHSA-vcc3-ghjq-m6fr in decode-uri-component0.2.2 (malformed URL decoding can consume excessive CPU), and GHSA-w5hq-g745-h8pq in uuid7.0.3 (buffer bounds in specific UUID APIs).

Pin only query-string7.1.3's decoder to0.5.0 and xcode's UUID to11.1.1. The decoder is now ESM, so keep a one-line pnpm patch selecting its default export in the CommonJS query-string consumer. UUID11 retains a CommonJS export and the v4 API used by xcode. Avoid broader major upgrades across Expo.

Verify query parsing, malformed input, Xcode ID generation, full TypeScript checks and Android export. The production audit now reports no known vulnerabilities; this is an advisory snapshot, not proof of absence of all security defects. Preserve the patch and lockfile in CI and remove the workaround when upstream packages support the fixed dependencies.

References: https://github.com/SamVerschueren/decode-uri-component/security/advisories/GHSA-vcc3-ghjq-m6fr and https://github.com/uuidjs/uuid/security/advisories/GHSA-w5hq-g745-h8pq.
