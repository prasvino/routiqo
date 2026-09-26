# Implementation resume: handoff for the next session

Updated 2026-09-26 (Spots backend and Android Spots). Read this first, then `CLAUDE.md` / `AGENTS.md`,
[`PRODUCT.md`](../PRODUCT.md) and the spec for whatever you pick up. The
previous private LIVE handoff is archived at
[`../archive/validation/IMPLEMENTATION_RESUME.md`](../archive/validation/IMPLEMENTATION_RESUME.md).

## Where things stand

- **Direction.** The [direction brief](../direction-brief.md) is adopted as
  [`PRODUCT.md`](../PRODUCT.md): Journey, Spots and Ask Ahead, active input only.
  See its *Decisions (2026-09-25)* section for the settled choices.
- **Timeline.** Diwali 2026 **dry run** (outbound rush 5–7 November) on a
  reduced scope with friends and early testers, so the critical path must be
  ready by **about 1 November**. Second dry run on a December long weekend.
  **Pongal 2027 public launch**, tentatively 8–14 January (to be finalised).
- **Corridors.**
  - Trunk: GST Road, Chennai (incl. Kilambakkam) to Trichy.
  - Branches: Trichy to Thanjavur; Trichy to Madurai to Tirunelveli; Madurai to
    Tuticorin.
  - Pongal adds Chennai to Coimbatore via Salem.
- **Docs.** Aligned; superseded material is in [`docs/archive/`](../archive/README.md).
- **Specs written (proposed, merged in PRs #17 and #18):**
  - [ANDROID_JOURNEY_MAP_SPEC.md](../features/journey/ANDROID_JOURNEY_MAP_SPEC.md)
  - [SPOTS_SPEC.md](../features/spots/SPOTS_SPEC.md)
  - [POSTS_AND_SIGNALS_SPEC.md](../features/spots/POSTS_AND_SIGNALS_SPEC.md)
  - [PILOT_MODERATION_SPEC.md](../features/spots/PILOT_MODERATION_SPEC.md)
- **ADRs 0066–0069:**
  - 0066: bounded HTTP refresh for Spot chat.
  - 0067: on-device journey route and Spots-ahead matching.
  - 0068: report collapse pending review (Pongal only).
  - 0069: moderation access and alerting.
- **Built (PR #19, merged): Android Journey map** behind
  `EXPO_PUBLIC_ROUTIQO_JOURNEY_MAP_ENABLED`:
  - device-only journey route record;
  - foreground-only `expo-location` store;
  - full-screen Journey mode, "Back to journey" bar, Home card, and "Start trip
    with this route".

  `pnpm check` passed (865 tests) and the Android JS export built. **No device
  run yet:** the dev client must be rebuilt for `expo-location`, and the
  checklist is in [NATIVE_ANDROID_PENDING.md](NATIVE_ANDROID_PENDING.md).
- **Verified behaviour** is recorded only in
  [`BUILD_STATUS.md`](../quality/BUILD_STATUS.md).

## Paused code checkpoint: native private route preparation

Commit `f5c5d7e` ("checkpoint private route preparation pending final QA") holds
native private route preparation and context recovery under ADR 0061 and
`../features/live/NATIVE_ROUTE_PREPARATION_SPEC.md`: an exact GET/POST native
route-context backend leaf, strict Android transport, provider wiring, a
consent/route coordinator, controller and native controls. Independent server and
client flags (`ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_API_ENABLED`,
`EXPO_PUBLIC_ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_ENABLED`) default to false.

What was verified before the pause: full `:core-api:check` (568 tests / 94
suites), focused backend/configuration tests, contract drift, focused UI/controller
tests, mobile TypeScript and affected lint, and native route-context transport
tests. What was **not**: mounted Android QA of the feature, a final Android
rebuild, and the full TypeScript/workspace checks on final source. Temporary QA
fixtures were restored and not committed; ignored `.patch-work` files hold
fixture templates and logs.

Under PRODUCT.md, per-journey consent and private route preparation are
archived: Spots ahead should appear automatically on journey start. Do not
resume the old QA sequence. Whether to keep this checkpoint default-off, reuse
parts of it (for example transport or coordinator patterns) or remove it is a
**code decision for the user**; do not delete or extend it without that decision.

## Next steps (Diwali critical path, in order)

The full list is the *Diwali dry-run critical path* in [`todo.md`](../../todo.md).
Build each piece default-off, with tests, and keep BUILD_STATUS and the ledgers
honest.

1. **Spots backend — done 2026-09-26, default-off**
   ([ADR 0070](../adr/0070-spot-module-and-catalog-delivery.md), BUILD_STATUS
   *September 26 Spots backend*):
   - new `spot` module;
   - the `routiqo-spots/1` loader;
   - `GET /api/v1/native/spots/catalog` with ETag/304;
   - `POST /api/v1/native/spots/activity`, quiet until posts exist and requiring an
     active journey;
   - the exact-`true` `ROUTIQO_SPOTS_API_ENABLED` flag, the contract and the
     generated client;
   - both native transport allowlists. The client reads the catalog with
     `nativeTransport.spotCatalog({ credential, accountId, ifNoneMatch })`.

   Kotlin transport checks are pending on a device, and the peer rate gate behind
   a load balancer must be resolved before the flag goes on in staging
   (NATIVE_ANDROID_PENDING.md).
   Requirements for the step-2 client parser, from the independent review:
   - Store a catalog only when its `version` equals the ETag without the quotes.
   - Do not enforce the contract's `maxItems: 0` on `alertIds` and `alerts`: those
     become non-empty additively once official alerts exist.
2. **Spots on Android — code done 2026-09-26, default-off, not device-verified.**
   The flag is `EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED`.
   - What's built: the catalog cache, Spots-ahead matching, the panel with an
     in-place detail, activity refresh and map markers.
   - Device checks are in NATIVE_ANDROID_PENDING.md.
   - Spot detail has no posts, signals or actions yet; step 3 adds them.
   - The activity parser already tolerates non-empty `alerts`.
3. **Posts and signals** ([POSTS_AND_SIGNALS_SPEC.md](../features/spots/POSTS_AND_SIGNALS_SPEC.md)):
   - new tables (do not alter the archived V10 private-signal tables);
   - four queue bands, per-type lifetimes, "Still true?" and "No longer true";
   - aliases from the word list, delete my post, Report, Block;
   - Ghost Mode and the separate `spot_outbox_v1` offline queue on Android.
4. **Moderation** ([PILOT_MODERATION_SPEC.md](../features/spots/PILOT_MODERATION_SPEC.md),
   ADR 0069):
   - `spots_*` permissions and 1–12 h shift grants;
   - renewable admin sessions (8 h absolute);
   - a queue for Spot items with hide, restore, clear signals, restrict and
     audited lookup;
   - the count-only urgent-report webhook and the phone-friendly admin UI.
5. **Official alerts:** extend the NDMA district pattern (currently hard-coded
   in `NdmaCapAlerts.java`) to the catalog's districts, and add a native read
   inside the activity response.

## Waiting on the owner (outside the code)

- Google Web and Android OAuth clients (`com.routiqo.app`, debug and release
  SHA-1) and a staging HTTPS API with `persistence,google-auth,native-auth`.
  Check the `azp` note in [GOOGLE_OAUTH_SETUP.md](../development/GOOGLE_OAUTH_SETUP.md)
  on the first real Android sign-in.
- Hosted Valhalla, Photon and map tiles/styles under `/maps/` for the corridors.
- A named Spot curator (about 150–200 Spots, English and Tamil names) and a
  second reviewer.
- The alias word list (owner approval, Tamil-speaker review).
- The private chat channel for urgent-report alerts, the moderator rota and the
  two grant admins.
- 3+ physical Android phones for the Phase 1 gate.

## Working notes for agents

- **Branch:** `claude/direction-brief-docs-j88j6f`. The owner merges each PR and
  deletes the branch, so recreate it from the latest `main` at the start of each
  task (`git fetch origin main && git checkout -B <branch> origin/main`). Open a
  PR only when asked.
- **ADR numbers:** check `docs/adr/` on the latest `main` before numbering a new
  ADR; other work lands on `main` in parallel. The next free number is **0071**.
- **Checks:** `pnpm install --frozen-lockfile`, then `pnpm check` (contracts,
  formatting, typecheck, lint, Vitest) and `pnpm --filter @routiqo/mobile build`.
  Backend: `(cd backend && ./gradlew check)`; PostgreSQL tests need Docker, so
  confirm early whether the session has it.
- **TypeScript:** `noUncheckedIndexedAccess` applies in `apps/mobile` and
  `packages/shared`, including tests.
- **Native view tests:** export hook-free `*View` components and walk the element
  tree with mocked `react-native` and `expo-router`, as in
  `tests/native-journey-mode.test.ts`.
- **Native SQLite tests:** use the file-backed `node:sqlite` adapter, as in
  `tests/native-journey-route-storage.test.ts`.
- **No Android SDK or emulator in cloud sessions:** record device checks as
  pending, never as passed.
- **Cloud backend checks (found 2026-09-26):**
  - The container ships JDK 21. Install JDK 25 with
    `apt-get install -y openjdk-25-jdk-headless`; Adoptium downloads are blocked.
  - Docker's CLI is present but its daemon is not running. Start `dockerd` as a
    long-running background task (it dies with a short-lived shell). If a stale
    `/var/run/docker/containerd/containerd.pid` blocks startup, remove it first.
  - Maven Central can answer 429 on the first Gradle run; retrying with backoff
    and `--max-workers=1` resolved it.
  - The pinned GHCR gitleaks image cannot be pulled, so run gitleaks v8.30.1 from
    Docker Hub with `.gitleaks.toml`.
- **Docs:** `prettier` does not cover `docs/`, so check relative links by hand.
  The only known broken ones are the two external Wayfind references.

## Carried forward from the archived V3 trial ledger

From [`V3_STAGING_TRIAL_PENDING.md`](../archive/validation/V3_STAGING_TRIAL_PENDING.md),
re-scoped from traffic summaries to posts, voice notes and chat. Details in the
[pilot moderation runbook](../development/PILOT_MODERATION_RUNBOOK.md).

| Dependency | Engineering work | External input or review |
|---|---|---|
| Real identity | Verify separate consumer and moderator Google audiences, origins and sessions; two-account browser and device flows | Google project owner configures OAuth clients, approved origins and admin MFA policy; consenting test accounts and devices |
| Moderation | Build per [PILOT_MODERATION_SPEC.md](../features/spots/PILOT_MODERATION_SPEC.md) and ADR 0069 (Spots permissions, queue, hide, shift grants, renewable sessions, urgent-report alert); exercise with real accounts | Named moderators and grant administrator, out-of-band trust-root bootstrap, admin OAuth/MFA policy, small named rota with time-boxed grants for the event window, supervision and retention review |
