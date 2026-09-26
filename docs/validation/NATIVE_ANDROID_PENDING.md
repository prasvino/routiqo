# Native Android implementation and pending release evidence

Updated: 2026-09-24; gate reframed 2026-09-25. Implementation is for a
development build and staging evaluation. Production activation, GPS navigation
and downloaded/offline maps are outside this approval. V3 community traffic output
is out of scope: it was archived with the 2026-09-25 direction reset (see
[`PRODUCT.md`](../PRODUCT.md)).

**Phase 1 gate:** Android is the primary pilot client. This ledger closes when the
app installs and runs a full journey (sign in, plan, start, travel, complete, see
history and journal) on **3 or more physical Android phones** against hosted
staging services. Emulator and synthetic evidence does not close it.

## Implemented code path

- Bearer-only native journey API with server-owned actor, exact account-context
  header, no cookies/origin/query strings, durable rate limits and idempotent
  lifecycle writes. Browser and default preview paths remain separate.
- Google Credential Manager sign-in receives a fresh native server challenge with
  hex nonce, exchanges the ID token, validates the response and serializes the
  Routiqo credential through SecureStore. Cold start verifies with the server;
  renewal and logout use the same vault.
- Android OkHttp bridge accepts a fixed HTTPS origin, native route allowlist,
  no redirects/cookies, bounded request and response bytes and timeouts.
- Explicit Trips journey actions persist in an account-scoped SQLite outbox before
  send, retry in bounded foreground batches on reconnect, and reconcile only a
  matching blocked server result. Local plans remain independent. Owner-scoped
  recent history is cached and checked after restart.
- MapLibre native renderer is reachable only from an explicit Show regional map
  action with a configured same-origin style path. It does not track a person or
  draw a journey route. The complete map style/resource tree needs regional review.

## Exact remaining inputs and checks

- [ ] Supply a Google Cloud Web OAuth client ID as server verifier audience and
      Android OAuth registration for `com.routiqo.app` with debug/release signing
      SHA-1 fingerprints; add staging test accounts. Keep secrets out of the bundle.
- [ ] Supply a reachable staging HTTPS API with `persistence,google-auth,native-auth`,
      database, rate secret, valid certificate and reviewed proxy behavior. Verify
      live sign-in, renewal, logout, account switch and offline restart on a test account.
- [ ] Supply reviewed self-hosted `/maps/` style, sprites, glyphs and regional tiles
      on the configured host. Inspect all nested resource URLs, attribution, logs,
      egress and failures. Do not use a public demo as production fallback.
- [ ] Complete configured-service checks on an emulator and on 3+ physical Android phones (low-end and mid-range): cold start,
      SecureStore/SQLite persistence, screen sizes/large text, slow/offline/reconnect,
      map load/failure, Google cancellation, background/resume, blocked replay and
      account isolation. Capture visual/device evidence and performance.
- [ ] Produce a signed build with the owner's signing key (an internal test build
      for Phase 1; the store release for the pilot), then verify its signing
      fingerprint and real OAuth registration. A debug APK does not satisfy this gate.
- [ ] Run the full-journey gate above on 3+ physical phones and record device
      model, Android version, memory and latency.
- [ ] Complete independent security review of the native bridge, token lifecycle,
      OAuth configuration and proxy/TLS behavior before production use.

## Journey map (flag `EXPO_PUBLIC_ROUTIQO_JOURNEY_MAP_ENABLED`)

Implemented and locally tested on 2026-09-25, with no emulator or device run.
Still to check on a rebuilt development client (the new `expo-location` native
module needs a rebuild) and then on 3+ physical phones:

- [ ] Start a trip with a calculated route; Journey mode opens; the route line,
      both endpoints and the camera fit are correct on the configured style.
- [ ] "Show my position": the permission dialog appears only then; allow, deny,
      "don't ask again" (settings link) and location services off all show
      their states; no prompt at app start, sign-in or journey start.
- [ ] Position updates stop when leaving Journey mode, backgrounding the app,
      completing the journey and switching accounts. Confirm with the Android
      location indicator that nothing runs in the background.
- [ ] Follow me turns off on a manual pan; reduced motion jumps instead of
      animating.
- [ ] Offline: route and notice with tiles unavailable; unconfigured style shows
      the text route summary.
- [ ] "Back to journey" bar on all tabs and the Home card; Android back closes
      Journey mode without completing; Complete asks for confirmation.
- [ ] The stored route is gone after completion, sign-out, account change and
      Clear local data (inspect with a debug build).
- [ ] 360 dp width at 200% font scale and TalkBack order; one-hour battery and
      memory reading on each phone.
- [ ] The merged manifest has no `ACCESS_BACKGROUND_LOCATION` or location
      foreground-service permission.

## Spots native transport (server flag `ROUTIQO_SPOTS_API_ENABLED`)

The TypeScript transport rules are unit-tested. The Kotlin rules in
`RoutiqoSafeHttpModule.kt` were changed on 2026-09-26 but only checked
statically (a Vitest parity test), because this cloud session has no Android
SDK. No app code calls these paths yet: that arrives with the Android Spots
work and its client flag `EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED`. To check on a
rebuilt development client against a staging API with the server flag on:

- [ ] The Kotlin module compiles with the seventh `ifNoneMatch` argument, and every
      existing native call still works (the TypeScript adapter always passes
      seven arguments).
- [ ] `GET /api/v1/native/spots/catalog` returns 200 with a strict ETag. A repeat
      with that `If-None-Match` returns 304 with an empty body. Any other 3xx is
      still rejected as a redirect.
- [ ] `POST /api/v1/native/spots/activity` returns 200 with an active journey and
      409 after completion. Oversized responses (above 256 KiB / 128 KiB) are rejected.
- [ ] **Before the flag goes on in staging: peer rate gate behind the load balancer.**
      - The problem: `NativeAuthGuard` limits every native path to 120 requests per
        minute per peer address (`native-other`), and `forward-headers-strategy` is
        `none`. Behind an AWS load balancer the peer is the balancer node. With
        mobile carrier NAT (CGNAT), many phones share one IP. About 40 travellers
        polling activity every 20 s would get 429 on every native path, including
        session renew. This is the proxy aggregation that ADR 0009 says must be
        evaluated before deployment.
      - The fix: trust client IPs only from the balancer's address range (for
        example RemoteIpValve), or exempt account-gated paths from the peer gate by
        decision.
      - Then measure the activity read's database cost at festival peak. Each read
        does three rate-bucket upserts, one session lookup and one journey
        `EXISTS` query.

## Spots on Android (flag `EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED`)

The code is implemented and unit-tested as of 2026-09-26, with no emulator or
device run. It needs the Journey map flag and a staging API with
`ROUTIQO_SPOTS_API_ENABLED=true` and a reviewed catalog. Check on a rebuilt
development client, then on 3+ physical phones:

- [ ] The Spot list downloads after sign-in and on opening Journey mode.
      Reopening sends `If-None-Match` and gets a 304. A new catalog version
      replaces the old one. "Clear local data" removes it, and the panel then
      says the list will download.
- [ ] Starting a trip with a calculated route shows the Spots ahead. The spot
      list and map markers match, ordered by distance. Spots near home and the
      destination are left out, except bus stands.
- [ ] Driving past a Spot shows "Here", and the Spot drops off 200 m after it.
      Without a position, the list reads "(from start)".
- [ ] Refresh timing, confirmed with a request log on the staging API:
      - requests go out about every 60 s, and every 20 s with a Spot detail open;
      - they stop in the background, after leaving Journey mode, offline, and
        after completion or an account switch;
      - no requests go out before the journey start reaches the server.
- [ ] Error states:
      - a 429 or 503 from staging shows "Updates paused briefly" or
        "unavailable", with backoff;
      - offline shows "Last updated N min ago".
- [ ] 360 dp width at 200% font scale: the rows, chips and Show more / Show fewer
      buttons stay readable, and TalkBack reads each row as one label.
- [ ] One-hour battery and data reading with the panel open while travelling.

## Evidence

Recovered Android build, 2026-09-24:

- `scripts/android-build.ps1` with JDK 17, API 36 and `-Architectures x86_64`
  passed: 467 tasks, 82 executed. Short workspace-local CMake paths avoid Windows
  pnpm path failures. Nitro 0.37.1 now matches Google sign-in 2.3.0's generated
  bindings; a dependency regression test checks the pairing and required header.
- Debug APK: `apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk`;
  SHA-256 `5c9ce13f52ead42626da76f566118b6a71ef8a69ada1942ddae1cda2efa608b4`.
  Installation succeeded on the API 36 Google APIs x86_64 emulator. This is not
  a signed release candidate or representative physical-device evidence.
- Mobile TypeScript passed after dependency alignment. The three dependency
  compatibility tests passed. The earlier complete 609-test run is recorded below.
- Emulator smoke passed: Home/Trips/Profile navigation, fail-closed missing-service
  messages, explicit local plan creation and SQLite retention after force-stop and
  restart. Profile remained readable at 130% text and 360 dp width with Wi-Fi/data
  disabled and animations disabled. No authenticated identity or map tiles were
  supplied. Screenshots and exact limits are in
  `docs/quality/evidence/native-android-2026-09-24/README.md`.

Resumed implementation review, 2026-09-23:

- Tightened the JavaScript and Kotlin transport allowlists to canonical journey
  UUIDs and endpoint-specific credential/account headers.
- Session rejection now forces server renewal even when the local expiry is still
  in the future. Verified renewal releases only authentication-blocked commands;
  conflict/rejected work remains intact. Foreground transitions and unmount stop
  further sends in an in-flight bounded batch.
- Focused transport/account/file-backed SQLite regression run: 22 tests passed;
  mobile TypeScript, focused lint and formatting passed. Core API check passed
  against unchanged compiled/test outputs.
- Full workspace contracts, formatting, six typecheck targets and lint passed.
  All 609 TypeScript tests across 74 files passed with two workers. The first
  unconstrained run timed out in an existing dependency compatibility test while
  competing with Android compilation; no timeout or assertion was weakened.
  The final secret scan and `git diff --check` passed.
- Recovered the interrupted Gradle cache onto D:, isolated damaged transform
  metadata, and restored dependencies with the frozen pnpm 10.34.4 lockfile.
  Generated autolinking metadata was rebuilt after dependency paths changed.
- Installed an API 36 Google APIs x86_64 emulator in workspace-local ignored
  storage. WHPX acceleration is available and Android reported boot completion.
  This is emulator readiness, not yet an application smoke-test result.

2026-09-23 local implementation evidence:

- `pnpm check`: 607 tests / 74 files; contracts, formatting, type checks and lint
  passed. `:core-api:check`: 544 tests / 88 suites, zero failures/errors/skips.
  Focused native account, transport, map and SQLite tests: 21 passed.
- `scripts/android-doctor.ps1 -AndroidJavaHome <portable Temurin 17> -RequireReady`:
  six prerequisite checks Ready (API 36, tools and JDK). `expo prebuild
--platform android --no-install` passed. The generated Android project is ignored.
- `git diff --check` and `pnpm secrets:check` passed. Lockfile was regenerated and
  frozen-checked with the repository's pinned pnpm 10.34.4.
- Android Gradle initially exposed missing local-module version metadata and then
  a missing SDK environment variable; both were corrected. The final build result,
  APK path/hash and emulator/device result belong here after the active build ends.

Automated tests use synthetic identity and do not prove real Google, TLS/proxy,
regional services or representative device behavior. No emulator or USB device was
connected when this implementation began.

An account verified earlier in the same running process can still save local
journey actions while offline after its bearer expires. Those actions have no
server authorization until renewal or a new verified session succeeds; an offline
cold start does not expose a cached authenticated account.

As in the browser journey workspace, a conflict or rejection keeps the saved
command until an owner-scoped server read proves that same action was applied.
The user can refresh server status; an unmatched command remains blocked and later
commands remain queued. Explicit discard or rebase is outside this native slice.
