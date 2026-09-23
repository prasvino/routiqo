# Native account history validation ledger

Updated: 2026-09-24. Owner-only journey history is the next native feature after
the Android account/journey integration. It remains behind the existing
`native-auth` server profile and configured native HTTPS origin.

## Human and environment requirements

- [ ] Provide the real Google OAuth registrations and reachable staging HTTPS API
      listed in `NATIVE_ANDROID_PENDING.md`.
- [ ] On a staging account with more than 20 journeys, verify latest/earlier/end
      pages, renewal, sign-out and same/different-account sign-in. Repeat with network
      loss and a delayed response during an account change.
- [ ] Repeat on a representative physical Android device, with large text,
      accessibility services and a slow network. Emulator/fixture evidence cannot
      establish physical-device performance or real OAuth behavior.

This read-only feature needs no new external provider, migration or operator
permission. It does not expand the durable recovery cache, upload local plans,
enable journals or activate public LIVE.

## Verification evidence

- Updated x86_64 Android debug build passed (467 tasks, 18 executed) and installed
  on the API 36 emulator. The APK hash and labeled synthetic view evidence are in
  `docs/quality/evidence/native-history-2026-09-24/README.md`.
- Native view checks covered explicit load, loaded rows, earlier/empty page,
  offline retention with disabled paging, failure/retry, session-required state,
  and 360 dp width with 130% text. The temporary harness was removed.
- Full TypeScript regression suite: **617 tests / 75 files passed** with two
  workers. One initial run hit an existing web focus-timing failure under local
  resource contention; its 17-test file and then the complete suite passed after
  stopping development processes. No timeout or assertion was weakened.
- Full `:core-api:check`: **545 Java tests / 88 suites passed**, with zero
  failures, errors or skips, including PostgreSQL integration tests.
- Workspace formatting, all six typecheck targets, lint, generated OpenAPI drift
  check and secret scan passed. Native focused protocol/session tests passed.
  Real OAuth validation is not claimed by these synthetic tests.
- Final Android export passed and produced the fixture-free 3.2 MB Hermes bundle.
  The final debug APK build passed after POST-only bridge hardening (467 tasks,
  21 executed). SHA-256:
  `26a91a64fc43dfedf7d4ab725111b5be760fe9a7f4ded998797c88d89500e311`.
  It installed and opened the restored production Trips route, preserving the
  local plan and showing the expected unconfigured-server disclosure. Evidence:
  `docs/quality/evidence/native-history-2026-09-24/final-app-trips.png`.
