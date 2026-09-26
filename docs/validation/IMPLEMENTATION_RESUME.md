# Implementation resume: handoff for the next session

Updated 2026-09-26, late (steps 1–3 merged as PRs #21–#25; step A done on branch `claude/resume-routiqo-mutuyg`, awaiting its PR). Work continues on the owner's laptop, where Android builds and device checks are possible. Read this first, then `CLAUDE.md` / `AGENTS.md`,
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
3. **Posts and signals** ([POSTS_AND_SIGNALS_SPEC.md](../features/spots/POSTS_AND_SIGNALS_SPEC.md)).
   The owner split this into three PRs.
   - **3a — server writes, done 2026-09-26, default-off**
     ([ADR 0071](../adr/0071-spot-contributions-storage-and-lifetimes.md)):
     - signals, posts, votes, delete, aliases and lifetimes;
     - rate limits and highlights;
     - maintenance (`ROUTIQO_SPOTS_MAINTENANCE_ENABLED`);
     - activity content.
     Writes need `ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED`. The alias word list
     `alias-words-v1.txt` is a **draft** that needs owner approval and a Tamil
     review first.
   - **3b — Report and Block (server), done 2026-09-26, default-off**
     ([ADR 0072](../adr/0072-spot-report-and-block.md)):
     - `POST /spots/items/{ref}/reports` (posts and signal-summary incidents;
       ADR 0064 receipt-first, 10 per 24 h, 7/30-day retention, severity-first
       index);
     - `POST /spots/items/{ref}/block-author` (posts only; hides that alias in
       its room for the blocker and records the account-level edge; 100-edge
       cap, 10 per minute; no unblock in the pilot);
     - the spec's "hide everywhere" was narrowed to the room, because it would
       link aliases across rooms (ADR 0072 §6). **Owner decision:** keep this, or
       commission a privacy review for wider hiding;
     - reported posts and signals are kept a fixed 30 days as evidence and never
       become highlights.
     Step 4 must add hide/restore and an "upheld" state that lifts the highlight
     exclusion for restored posts.
   - **3c — Android, done 2026-09-26, default-off, not device-verified**
     ([ADR 0073](../adr/0073-android-spot-contributions-and-ghost-mode.md);
     flag `EXPO_PUBLIC_ROUTIQO_SPOT_CONTRIBUTIONS_ENABLED`):
     - Spot detail content;
     - one-tap signals, the post composer, votes, Report, Block and Delete my post;
     - the `spot_outbox_v1` offline queue;
     - device-wide Ghost Mode, in Profile and the Spots panel;
     - the TypeScript and Kotlin transport allowlists.
     Device checks are in NATIVE_ANDROID_PENDING.md.

   Original scope list:
   - new tables (do not alter the archived V10 private-signal tables);
   - four queue bands, per-type lifetimes, "Still true?" and "No longer true";
   - aliases from the word list, delete my post, Report, Block;
   - Ghost Mode and the separate `spot_outbox_v1` offline queue on Android.
**Next, in this order:**

- **A. Per-address rate gate behind the load balancer — done in code 2026-09-26,
  default-off** ([ADR 0074](../adr/0074-client-address-behind-load-balancer.md),
  BUILD_STATUS *September 26 Rate-limit client address*):
  - `ClientAddressResolver` keys the native, browser and admin peer gates;
  - `ROUTIQO_TRUSTED_PROXY_CIDRS` (unset by default) trusts `X-Forwarded-For`
    only from the balancer's literal CIDRs, reading one line from the right;
  - a custom resolver was chosen over `RemoteIpValve`, which would also rewrite
    scheme and host and trust all private ranges by default;
  - signed-in native calls allow 600 per minute per address (owner decision, for
    carrier NAT), and per-account 120 is unchanged.
  - Still open, in staging (NATIVE_ANDROID_PENDING.md, "Spots native transport"):
    - set the ALB subnet CIDRs;
    - confirm the ALB's `X-Forwarded-For` line handling;
    - measure the activity read at peak.
- **B. Android build and device verification, now possible on the laptop.**
  - Nothing Spot-related has run on Android yet. Rebuild the development client
    with `scripts/android-build.ps1` (JDK 17, API 36).
  - Check that the Kotlin safe-HTTP module compiles, with its new Spot write paths
    and seven-argument call.
  - Then work through the Spot sections of NATIVE_ANDROID_PENDING.md on the
    emulator against a local API:
    - Spots on Android;
    - Spot contributions;
    - Ghost Mode.
  - The full physical-phone checks (3+ phones) need the staging API and OAuth
    clients.
  - Record real results, with screenshots under
    `docs/quality/evidence/native-android-<date>/`, and fix what breaks.
  - A known follow-up: make the Kotlin call cancellable, so Ghost Mode can stop
    a send already in flight (ADR 0073).

4. **Moderation** ([PILOT_MODERATION_SPEC.md](../features/spots/PILOT_MODERATION_SPEC.md),
   ADR 0069):
   - `spots_*` permissions and 1–12 h shift grants;
   - renewable admin sessions (8 h absolute);
   - a queue for Spot items with hide, restore, clear signals, restrict and
     audited lookup;
   - the count-only urgent-report webhook and the phone-friendly admin UI.
   - **Plan mode.** Split it into **4a, server** (permissions, grants, sessions,
     the queue over `spot_report_group` and hide/restore) and **4b, admin UI and
     webhook**.
   - Step 4 must also:
     - add an "upheld" state: highlights currently skip *any* reported post
       (ADR 0072);
     - make hidden items vanish from activity reads;
     - show the author "Hidden by a moderator".
   - The queue reads `spot_report_group`: one row per incident, severity-first
     index, reporter-free.
   - Reporter rows (`spot_report`, 7 days) hold the reporter only for rate
     limits and de-duplication. They are never shown to moderators.
5. **Official alerts:** extend the NDMA district pattern (currently hard-coded
   in `NdmaCapAlerts.java`) to the catalog's districts, and add a native read
   inside the activity response.
   - `alertIds` / `alerts` in the activity contract are `maxItems: 0` today, and
     the Android parser already tolerates non-empty values.

## Decisions the owner still has to make

- **Block scope:** Block currently hides the alias only in that room (the Spot
  on that day), because hiding everywhere would link aliases across rooms.
  Keep it, or send wider hiding to a privacy review (ADR 0072 §6).
- **Ghost Mode:**
  - Delete my post is allowed while it is on. Confirm or forbid (ADR 0073).
  - It is device-wide, not per account. Confirm.
- **Phone-number rule:** "7 or more digits" also rejects dates such as
  2026-11-05. Keep it, or require 10 or more digits.
- **Alias word list:** `alias-words-v1.txt` is a draft that needs owner
  approval and a Tamil review.
- **District list:** confirm the catalog districts, which step 5 needs.

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

- **Branch:** use the branch the session names (recently
  `claude/resume-routiqo-mutuyg`). The owner merges each PR and deletes the
  branch, so recreate it from the latest `main` at the start of each task
  (`git fetch origin main && git checkout -B <branch> origin/main`). Open a PR
  only when asked ("yes, open the PR").
- **Numbering:** check the latest `main` before numbering anything; other work
  lands there in parallel.
  - The next free ADR number is **0075**.
  - The next Flyway migration is **V32**. Never edit an applied migration.
- **Baselines on `main` (`2885b57`, after PR #26), confirmed 2026-09-26:**
  - `pnpm check`: **956 tests / 114 files**;
  - `./gradlew check bootJar`: **707 tests / 119 suites**, counted across all
    backend modules;
  - `pnpm build`: passes.
  - With step A: backend **768 tests / 121 suites**; TypeScript unchanged.
  - `packages/shared/src/journey-route.test.ts` ("tightens to the point budget")
    takes about 3.8 s against the 5 s default timeout. It fails when `pnpm check`
    runs alongside Gradle, so run the two one after the other, or raise that
    test's timeout.
- **Workflow the owner uses:**
  1. Plan mode for substantial steps; the owner approves before implementation.
  2. Build in phased commits, pushing each one.
  3. Run full checks: `pnpm check`, `pnpm build`, the backend check when Java
     changes, and the secret scan.
  4. Get an independent fresh-subagent review and fix what it finds.
  5. Update BUILD_STATUS, `todo.md`, the spec status and this file.
  6. Report, then open the PR on request.
  - Commit trailers are `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
    plus the session URL. Never put model names in PR text.
- **On the owner's Windows laptop** (see README and CLAUDE.md):
  - run pnpm as `npx.cmd --yes pnpm@10.34.4 <script>`;
  - backend scripts are `pnpm backend:check` / `pnpm backend:dev`
    (`scripts/backend.ps1`);
  - the Android build is `scripts/android-build.ps1`.
  - Device checks may be recorded as passed only when actually run on an emulator
    or phone, with the device, API level and date.
- **Cloud backend checks (found 2026-09-26):**
  - The container ships JDK 21. Install JDK 25 with
    `apt-get install -y openjdk-25-jdk-headless`; Adoptium downloads are blocked.
  - Docker's CLI is present but its daemon is not running. Start `dockerd` as a
    long-running background task (`exec dockerd` in a background command); a
    `nohup … &` launch dies with its shell. If a stale
    `/var/run/docker/containerd/containerd.pid` blocks startup, remove it first.
  - Maven Central can answer 429 on the first Gradle run; retrying with backoff
    and `--max-workers=1` resolved it.
  - The pinned GHCR gitleaks image cannot be pulled, so run gitleaks v8.30.1 from
    Docker Hub with `.gitleaks.toml`.
    - Copy the tracked files (`git ls-files`) into a temporary folder.
    - Run `docker run --rm --network none --mount type=bind,source=<tmp>,target=/scan,readonly zricethezav/gitleaks:v8.30.1 dir /scan --config /scan/.gitleaks.toml --redact`.
  - Count backend tests across all modules
    (`backend/*/build/test-results/test/*.xml`), not `core-api` alone.
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
