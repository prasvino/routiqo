# Routiqo TODO

Release checklist for the Pongal pilot. Product scope, phase goals and gates come
from [`docs/PRODUCT.md`](docs/PRODUCT.md); the engineering sequence per phase is in
[`docs/development/BUILD_PLAN.md`](docs/development/BUILD_PLAN.md). Only
[`docs/quality/BUILD_STATUS.md`](docs/quality/BUILD_STATUS.md) records verified
behaviour: tick an item here only when its evidence is recorded there or in the
matching `docs/validation/*_PENDING.md` ledger.

**Pilot corridor (decided):** Chennai to Trichy and Madurai along GST Road, via
Chengalpattu, Tindivanam, Villupuram, Ulundurpet and Perambalur, plus the
Kilambakkam bus terminus. Android on physical devices is the primary client; web
serves route guides and planning.

New capabilities ship default-off behind flags until their phase gate passes.
Cloud agent sessions have no Android SDK or device: record device checks as
pending, never as passed.

## Phase 1 — Foundations

Gate: the app installs and runs a full journey (sign in, plan, start, travel,
complete, see history) on **3 or more physical Android phones** against the hosted
staging services.

Already done (engineering evidence in BUILD_STATUS and the native ledgers):

- [x] Bounded, redirect-safe Android authenticated transport and owner-scoped native journey API.
- [x] Google challenge binding and sign-in UI, secure-vault coordination, session renewal and coordinated logout/deletion.
- [x] Authenticated journey resources, durable native reconnect dispatch and exact blocked-command recovery.
- [x] MapLibre regional basemap preview in the Android development client (no GPS or route geometry yet).
- [x] Android doctor prerequisites (API 36, build-tools, command-line tools, JDK 17) and API 36 emulator.
- [x] x86_64 debug build and unconfigured-service emulator smoke test ([native Android ledger](docs/validation/NATIVE_ANDROID_PENDING.md)).
- [x] Native account history: fixed 20-row owner history, strict transport, latest/earlier/retry and session-fenced pages.
- [x] Native journal browsing and durable editing (synthetic emulator evidence only).
- [x] Native place search, route alternatives and manual directions behind default-off flags.

Google sign-in configuration:

- [ ] Configure the Google project, consent screen, Web OAuth client (server audience) and approved web origins with account-owner access ([setup](docs/development/GOOGLE_OAUTH_SETUP.md)).
- [ ] Register the Android OAuth client for `com.routiqo.app` with debug and release signing SHA-1 fingerprints; add staging test accounts.
- [ ] Supply required secrets through local/deployment secret configuration; keep them out of client bundles.
- [ ] Verify real sign-in, renewal, logout, expired sessions and recent-auth account deletion on Android and through the staging browser/proxy boundary.

Hosted maps and routing (corridor-scoped):

- [ ] Review OSM/dataset coverage, provenance, licensing, update cadence, storage and import for the GST Road corridor and Kilambakkam.
- [ ] Provision controlled Valhalla, Photon and tile hosting, including MapLibre styles, sprites and glyphs.
- [ ] Configure fixed service origins, corridor geographic bounds, internal-service protection and redacted provider/proxy logs.
- [ ] Validate real routing, alternatives, search, attribution, coverage failures, outages, egress behaviour and regional updates.
- [ ] Supply a reachable staging HTTPS API (`persistence,google-auth,native-auth`, database, rate secret, valid certificate, reviewed proxy).

Android Journey map:

- [ ] Author the Android Journey map spec (route on map, "me", Spots-ahead panel slot, offline and permission states).
- [ ] Implement selected-route display and explicit, foreground-only location permission during an active journey; no background tracking and no real location requested in agent QA.

Journey reliability (Android first):

- [ ] Verify SQLite/SecureStore restart, account isolation, backup behaviour, location denial, accessibility, performance and unreliable networks on real devices.
- [ ] Complete broader cross-device restoration and unresolved-conflict recovery without overwriting pending local work.
- [ ] Test delayed responses, duplicate commands, network flaps, interrupted writes and unavailable storage across accounts/devices.
- [ ] Confirm supported recovery flows preserve acknowledged work, account isolation and exact replay semantics.
- [ ] Complete native history and journal checks with real sign-in on physical devices ([history ledger](docs/validation/NATIVE_HISTORY_PENDING.md), [journal ledger](docs/validation/NATIVE_JOURNAL_PENDING.md)).
- [ ] Complete native routing checks against the hosted services ([routing ledger](docs/validation/NATIVE_ROUTING_PENDING.md)).

Android builds and devices:

- [ ] Produce a signed internal test build with the owner's release key; verify its fingerprint against the Android OAuth registration.
- [ ] Run the full-journey gate on 3+ physical Android phones spanning low-end and mid-range devices, small screens and large text; record device, Android version, memory and latency.

## Phase 2 — Spots and posts

Gate: **10 testers complete a real highway trip on the corridor and post.**

Specs to author before building (each names data, authorization, privacy, offline
and failure boundaries):

- [ ] Spots: seeding, catalog versioning, "live" state and fade, Spots-ahead ordering.
- [ ] Posts and one-tap signals: types, per-type lifetime, "Still true?", highlights, delete-my-post.
- [ ] Voice notes: signed direct S3 upload, size/duration limits, retention, deletion, report/hide.
- [ ] Spot passage: on-device detection, opt-in, coarse time, 24-hour deletion, outcome-code logging.
- [ ] Aliases and rooms: per-room alias generation and collisions, block mapping without exposing identity, Spot chat and festival route room lifetime.

Spots:

- [ ] Seed 50–100 corridor Spots (tolls, major eateries, fuel, restrooms, bus stands, Kilambakkam) by reusing the curated anchor catalog format, loader and route-anchor matching; record curator and provenance.
- [ ] Show Spots ahead on the Android Journey map, ordered by distance along the route, appearing automatically on journey start.
- [ ] Spot reads: authenticated, authorized per object, bounded foreground refresh with cancellation, backoff and one request in flight.
- [ ] Loading, empty, error, stale and offline states with truthful source and freshness; honest empty Spots, never faked activity.

Contribution:

- [ ] Public one-tap signals on Spots, evolved from private Quick Signals (reuse signal storage, idempotent commands, abuse budgets, expiry maintenance; withdraw becomes "delete my post").
- [ ] Short text posts on Spots.
- [ ] Voice notes via signed direct S3 uploads, recorded only by explicit action.
- [ ] Per-type expiry on server time, "Still true?" confirmation, and top tips becoming highlights after expiry.
- [ ] Report counts allowed ("3 reports in 20 min"); traveller counts not shown.
- [ ] Offline rule: posts, signals and answers queue in the journey outbox with capture time and idempotency key; the server rejects items past their lifetime; cached Spot content is labelled stale and dropped at expiry.
- [ ] Rate-limit posts, voice uploads, signals, "Still true?" and reports; stricter limits for new accounts.

Privacy and identity:

- [ ] Per-room aliases; no follower graph, no private DMs; aliases never embed account identifiers.
- [ ] Ghost Mode stops **all** sending (posts, signals, Spot passage, queued outbox items) and takes priority over reconnect and replay.
- [ ] Clear Spot state on Ghost Mode, account changes, sign-out and journey completion; reauthorize on reconnect.
- [ ] Account deletion removes the user's posts, voice notes, signals, answers and route guides; users can delete their own posts.
- [ ] Spot chat for each Spot and a festival route room for the event window; write the transport ADR (bounded HTTP refresh vs WebSockets) first.

Moderation (re-scoped to posts, voice notes and chat; see the
[pilot moderation runbook](docs/development/PILOT_MODERATION_RUNBOOK.md)):

- [x] Default-off moderator queue, audited review/suppression and controlled grant console exist (ADRs 0056/0057); reuse them rather than rebuild.
- [ ] Report and Block on every post, voice note and chat message; report reasons include business promotion, false alarm, abuse (Tamil/Tanglish) and personal data.
- [ ] Resolve report evidence identity, reference authorization, exact retries after revocation and transaction lock order; implement durable report intake for posts, voice and chat.
- [ ] Define what moderators can see after content expires, with explicit bounded retention.
- [ ] Moderator "hide content" action (follow-up to ADR 0041's restriction boundary).
- [ ] Reuse the admin app login with real admin OAuth/MFA; lighter pilot access: a small named rota with time-boxed grants for the event window.
- [ ] Blocks apply to REST and realtime delivery, room subscription, Ask Ahead recipient selection and replay.
- [ ] Propagate hide/block/delete across reads, caches and delivery channels; deny when authority is unavailable.
- [ ] Verify multi-user, concurrent, replay, abuse and revocation scenarios with two or more real accounts.

Official alerts (optional):

- [x] Default-off Chennai district official-alert pilot (NDMA CAP reader, owner-only API, web list).
- [ ] If kept for the pilot, show alerts on Spots and extend coverage to corridor districts (Villupuram, Perambalur, Trichy, Madurai); run staging validation per `docs/features/live/PROVIDER_LIVE_PILOT_SPEC.md` before activation.

## Phase 3 — Ask Ahead and route guides

Gate: **questions get answered on a test trip.**

Specs to author:

- [ ] Ask Ahead: recipient selection (bounded, non-deterministic, no repeat targeting, respects blocks), asker never learns recipients, honest no-answer state, answer summaries.
- [ ] Route guides: publishing from a completed journey plus journal notes and Spot tips, with address stripping.

Build:

- [ ] Post-passing prompt: quiet, dismissible card shown only when stopped/slow or to a declared passenger; expires if unanswered; never a blocking modal or sound while moving.
- [ ] Spot-passage opt-in (one clear opt-in), detection on device during an active journey; only the answer or an Ask Ahead eligibility record is sent.
- [ ] Ask Ahead questions pinned to a Spot ahead, offered to opted-in recent passers and posters; one-tap answers summarised ("5 replies: mostly 10–20 min").
- [ ] Route guides published from completed journeys and journals; strip exact home, office and start/end addresses; web shows guides for planning.
- [ ] Verify web planning flows used for guides: keyboard/focus, small screens, large text, reduced motion and storage-failure recovery.
- [ ] Verify journal conflicts, older history, summary completeness and backup/restore with real sign-in (route-guide source material).

## Phase 4 — Dry run

Gate: **no blocking bugs, and moderation works**, on a normal long weekend before
Pongal with friends and early users.

- [ ] Set exact success targets (share of journeys seeing a fresh Spot item, Ask Ahead answer rate within 15 minutes, prompt tap rate, return-journey reopen) before the dry run; measure with privacy-preserving aggregates.
- [ ] Configure production PostgreSQL/PostGIS, caches, secrets, networking, domain/TLS, runtime profiles and feature gates with operator access.
- [ ] Validate migrations, backup restoration and rollback in staging, including the V13 acceptance-writer quiet period.
- [ ] Validate expiry maintenance throughput and backlog; set failure and capacity alerts for the intended replica count.
- [ ] Establish redacted monitoring, health checks and incident ownership.
- [ ] Staff and exercise the on-call moderator rota; verify hide, block, grant expiry and escalation end to end.
- [ ] Operator alerts, supervision, a lightweight appeal path and approved audit/backup retention; activate and verify bounded moderation cleanup in staging.
- [ ] Finalize privacy disclosures (Spot passage, aliases, voice notes), deletion policies and backup retention.
- [ ] Complete authenticated release QA, load/capacity checks and deployment smoke tests.

## Phase 5 — Pongal pilot

Gate: measured against the success signals in PRODUCT.md.

- [ ] Produce the signed Android release and distribution channel; verify release fingerprint against OAuth.
- [ ] Verify remote CI on the actual release commit.
- [ ] Capacity plan for the Pongal rush on the corridor; festival route room window set.
- [ ] On-call moderator rota for the full event window.
- [ ] Launch publicly on the corridor, enabling only flags whose phase gates passed.
- [ ] Measure against the success targets and record results.

## Later (not pilot)

- [ ] Pulse: AI route summary behind adapters, never inventing conditions.
- [ ] Aggregate traveller counts, travel waves and cohorts: only with density and a separate privacy review.
- [ ] Daily commute rooms, meetups, bus-stop connect; monthly commute summaries as a retention feature.
- [ ] Photos and journal media; push notifications beyond journey essentials; reminders.
- [ ] Persist routes across reload with reviewed privacy, storage and deletion behaviour.
- [ ] GPS-following guidance, bounded offline-map downloads, update integrity, eviction and on-device rerouting.
- [ ] User-suggested Spots; curated Explore destinations replaced over time by route guides.

## Archived

The 2026-09-25 direction reset removed the public LIVE privacy-contract items
(transcript protocol, fixed partitions and publication windows, collusion and
distributed query budgets, cohort/threshold projection), differential-privacy and
verified-person and verified-contributor authority and operations, evidence
independence and anti-Sybil rules for aggregates, the V3 community traffic summary staging trial and
production decision, per-journey consent / private route preparation / private
Quick Signal lifecycle verification, and the active-journey LIVE list. Their code
stays in the repository, default-off. The earlier list is in git history for this
file; archived ledgers and the older roadmap (`todo.txt`) are under
[`docs/archive/`](docs/archive/README.md).
