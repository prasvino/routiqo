# Routiqo TODO

Release checklist for the Pongal pilot. Product scope, phase goals and gates come
from [`docs/PRODUCT.md`](docs/PRODUCT.md); the engineering sequence per phase is in
[`docs/development/BUILD_PLAN.md`](docs/development/BUILD_PLAN.md). Only
[`docs/quality/BUILD_STATUS.md`](docs/quality/BUILD_STATUS.md) records verified
behaviour: tick an item here only when its evidence is recorded there or in the
matching `docs/validation/*_PENDING.md` ledger.

**Timeline (decided 2026-09-25):** Diwali 2026 (outbound rush 5–7 November) is the
**dry run** on a reduced scope with friends and early testers;
Pongal 2027 (tentatively 8–14 January, to be finalised) is the **public launch**. A second, smaller dry run on a December long
weekend covers what Diwali leaves out. See PRODUCT.md *Pilot* and *Decisions*.

**Corridors (decided):** trunk Chennai (incl. Kilambakkam) → Chengalpattu →
Tindivanam → Villupuram → Ulundurpet → Perambalur → Trichy on GST Road, with
branches Trichy → Thanjavur, Trichy → Madurai → Tirunelveli and Madurai →
Tuticorin, for both Diwali and Pongal. Chennai → Krishnagiri → Salem → Coimbatore
is added for Pongal. Android on physical devices is the primary client; web
serves route guides and planning.

## Diwali dry-run critical path (needed by about 1 November 2026)

Everything else waits until after Diwali. Items below are also listed in their
phase.

- [ ] Phase 1 gate: real Google sign-in, hosted maps/routing for the corridors, full journey on 3+ physical Android phones.
- [ ] Android Journey map with Spots ahead (full-screen journey mode from Home).
- [ ] ~150–200 seeded Spots on the trunk and branches, with a named curator.
- [ ] Public one-tap signals and short text posts; per-type expiry; "Still true?" and "No longer true".
- [ ] Bounded HTTP refresh for Spot content (ADR 0066); offline rule for queued signals and posts.
- [ ] Ghost Mode stops all sending; delete my post; account deletion covers posts and signals.
- [ ] Report/Block on every item; moderator hide; admin login and a named, time-boxed moderator rota for the dry run.
- [ ] Rate limits, stricter for new accounts.
- [ ] Official alerts shown on Spots and the Journey for the corridor districts (north-east monsoon season).
- [ ] Minimal staging deployment, monitoring and rollback for the dry-run testers.

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

- [ ] Review OSM/dataset coverage, provenance, licensing, update cadence, storage and import for the trunk and branch corridors (Chennai incl. Kilambakkam to Trichy, Thanjavur, Madurai, Tirunelveli and Tuticorin); add Chennai–Coimbatore before Pongal.
- [ ] Provision controlled Valhalla, Photon and tile hosting, including MapLibre styles, sprites and glyphs.
- [ ] Configure fixed service origins, corridor geographic bounds, internal-service protection and redacted provider/proxy logs.
- [ ] Validate real routing, alternatives, search, attribution, coverage failures, outages, egress behaviour and regional updates.
- [ ] Supply a reachable staging HTTPS API (`persistence,google-auth,native-auth`, database, rate secret, valid certificate, reviewed proxy).

Android Journey map:

- [x] Author the Android Journey map spec — drafted as [ANDROID_JOURNEY_MAP_SPEC.md](docs/features/journey/ANDROID_JOURNEY_MAP_SPEC.md) with [ADR 0067](docs/adr/0067-on-device-journey-route-and-spots-ahead.md), proposed and awaiting review (full-screen journey mode opened from Home with a persistent "Back to journey" bar; route on map, "me", Spots-ahead panel slot, offline and permission states).
- [ ] Implement selected-route display and explicit, foreground-only location permission during an active journey; no background tracking and no real location requested in agent QA.

Journey reliability (Android first):

- [ ] Verify SQLite/SecureStore restart, account isolation, backup behaviour, location denial, accessibility, performance and unreliable networks on real devices.
- [ ] Complete broader cross-device restoration and unresolved-conflict recovery without overwriting pending local work.
- [x] Web: explicitly discard a server-refused journey start/finish after a fresh server check (ADR 0063; `docs/features/journey/OUTBOX_CONFLICT_RESOLUTION_SPEC.md`). Native Android controls and real two-device sign-in QA remain.
- [x] Web: explicit, default-off account planning copy (ADR 0062; `docs/features/journey/ACCOUNT_PLANNING_BACKUP_SPEC.md`).
- [ ] Validate the account planning copy with real Google sign-in across two devices on HTTPS staging, then add native Android controls on the same contract.
- [x] Web journey sync scenario tests: lost/delayed responses, duplicate taps, competing tabs, network flap, account switch mid-request, storage failure (`tests/journey-network-resilience.test.ts`). Real-network, two-device and native runs remain.
- [x] Web recovery flows across two simulated devices sharing one server (`tests/journey-recovery-flows.test.ts`). Native and real-sign-in runs remain.
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

- [x] Spots spec drafted: [SPOTS_SPEC.md](docs/features/spots/SPOTS_SPEC.md), proposed and awaiting review (seeding, catalog versioning, "live" state and fade, Spots-ahead ordering)
- [x] Posts and signals spec drafted: [POSTS_AND_SIGNALS_SPEC.md](docs/features/spots/POSTS_AND_SIGNALS_SPEC.md), proposed and awaiting review. Covers types, the decided lifetimes (60 min / 2 h traffic and queue signals; 90 min / 3 h traffic and incident posts; 24 h / 36 h food, fuel, restroom and Good/Avoid), "Still true?" (resets to half base life, one per account, not the author), "No longer true" (two distinct accounts expire early), highlights ranked by confirmations, delete-my-post.
- [ ] Voice notes: signed direct S3 upload, size/duration limits, retention, deletion, report/hide.
- [ ] Spot passage: on-device detection per the PRODUCT.md decision (foreground service during an active journey, balanced accuracy ~100 m / 30 s, next ~20 Spots ahead, ~150 m pass radius, no background-location permission), opt-in, coarse time, 24-hour deletion, outcome-code logging.
- [ ] Aliases and rooms: server-generated random per-room aliases from a curated English/Tamil-friendly word list, numbered collisions, account-level blocks without revealing identity, audited moderator lookup; Spot chat and festival route room lifetime.
- [x] Spot chat transport decided: bounded foreground HTTP refresh ([ADR 0066](docs/adr/0066-bounded-http-refresh-for-spot-chat.md)).

Spots:

- [ ] Seed about 150–200 Spots on the trunk and branches (tolls, major eateries, fuel, restrooms, bus stands, Kilambakkam), weighted to the trunk, by reusing the curated anchor catalog format, loader and route-anchor matching; record curator and provenance. Add Coimbatore-trunk Spots before Pongal.
- [ ] Show Spots ahead on the Android Journey map, ordered by distance along the route, appearing automatically on journey start.
- [ ] Spot reads: authenticated, authorized per object, bounded foreground refresh with cancellation, backoff and one request in flight.
- [ ] Loading, empty, error, stale and offline states with truthful source and freshness; honest empty Spots, never faked activity.

Contribution:

- [ ] Public one-tap signals on Spots, evolved from private Quick Signals (reuse signal storage, idempotent commands, abuse budgets, expiry maintenance; withdraw becomes "delete my post").
- [ ] Short text posts on Spots.
- [ ] Voice notes via signed direct S3 uploads, recorded only by explicit action.
- [ ] Per-type expiry on server time with the decided lifetimes, "Still true?" and "No longer true", and top tips becoming highlights after expiry.
- [ ] Report counts allowed ("3 reports in 20 min"); traveller counts not shown.
- [ ] Offline rule: posts, signals and answers queue in the journey outbox with capture time and idempotency key; the server rejects items past their lifetime; cached Spot content is labelled stale and dropped at expiry.
- [ ] Rate-limit posts, voice uploads, signals, "Still true?" and reports; stricter limits for new accounts.

Privacy and identity:

- [ ] Per-room aliases; no follower graph, no private DMs; aliases never embed account identifiers.
- [ ] Ghost Mode stops **all** sending (posts, signals, Spot passage, queued outbox items) and takes priority over reconnect and replay.
- [ ] Clear Spot state on Ghost Mode, account changes, sign-out and journey completion; reauthorize on reconnect.
- [ ] Account deletion removes the user's posts, voice notes, signals, answers and route guides; users can delete their own posts.
- [ ] Spot chat for each Spot and a festival route room per corridor for the event window, over bounded HTTP refresh (ADR 0066). Not needed for Diwali.

Moderation (re-scoped to posts, voice notes and chat; see the
[pilot moderation runbook](docs/development/PILOT_MODERATION_RUNBOOK.md)):

- [x] Default-off moderator queue, audited review/suppression and controlled grant console exist (ADRs 0056/0057); reuse them rather than rebuild.
- [ ] Report and Block on every post, voice note and chat message; report reasons include business promotion, false alarm, abuse (Tamil/Tanglish) and personal data.
- [x] Reporting protocol implemented for V3 under ADR 0064 (receipt-first retry, 7/30-day retention split, 10-per-24h quota, UNSAFE-first queue, groups close as `CLOSED_EVIDENCE_UNAVAILABLE` after expiry). Reuse it rather than rebuild.
- [ ] Re-target the ADR 0064 reporting path from V3 summaries to posts, voice notes, chat and signals: evidence identity, reference authorization, exact retries after revocation, lock order and durable intake.
- [ ] Confirm what moderators can see after content expires for posts, voice and chat, building on ADR 0064's retention split.
- [ ] Moderator "hide content" action (follow-up to ADR 0041's restriction boundary).
- [ ] Reuse the admin app login with real admin OAuth/MFA; lighter pilot access: a small named rota with time-boxed grants for the event window.
- [ ] Blocks apply to REST and realtime delivery, room subscription, Ask Ahead recipient selection and replay.
- [ ] Propagate hide/block/delete across reads, caches and delivery channels; deny when authority is unavailable.
- [ ] Verify multi-user, concurrent, replay, abuse and revocation scenarios with two or more real accounts.

Official alerts (optional):

- [x] Default-off Chennai district official-alert pilot (NDMA CAP reader, owner-only API, web list).
- [ ] Before Diwali: show alerts on Spots and the Journey, and extend coverage from the Chennai-area districts to the corridor districts (Villupuram, Kallakurichi, Perambalur, Trichy, Thanjavur, Madurai, Tirunelveli, Tuticorin and the districts between; add Coimbatore-trunk districts before Pongal — confirm the list against the routes when seeding). Run staging validation per `docs/features/live/PROVIDER_LIVE_PILOT_SPEC.md` before activation.

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
- [ ] Rename Explore to Guides on web and Android when route guides ship (tabs: Home, Guides, Trips, Profile).
- [x] Signed-out web planning accessibility pass (synthetic API): keyboard/focus, 320 px reflow, 130% zoom and 200% text, reduced motion, storage-failure recovery (`docs/quality/evidence/planning-accessibility-2026-09-25/`).
- [ ] Verify authenticated web planning flows used for guides, screen readers and native equivalents.
- [ ] Verify journal conflicts, older history, summary completeness and backup/restore with real sign-in (route-guide source material).

## Phase 4 — Dry runs

Gate: **no blocking bugs, and moderation works**, with friends and early users.

- **Diwali 2026 dry run** (reduced scope; see the critical path above).
- **December long-weekend dry run** for voice notes, Ask Ahead, post-passing
  prompts, Spot chat, the festival room and route guides.

- [ ] Set exact success targets (share of journeys seeing a fresh Spot item, Ask Ahead answer rate within 15 minutes, prompt tap rate, return-journey reopen) before the Diwali dry run; measure with privacy-preserving aggregates.
- [ ] Measure Spot-passage battery cost on the Phase 1 phones against the ~3–4% per hour target before the December dry run.
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
- [ ] Finalise the Pongal dates (tentatively 8–14 January 2027) and festival room windows.
- [ ] Capacity plan for the Pongal rush on all corridors, including ADR 0066 refresh load; festival route room windows set.
- [ ] On-call moderator rota for the full event window.
- [ ] Launch publicly on the corridors, enabling only flags whose phase gates passed.
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

ADR 0065 (the V1 public LIVE policy, accepted earlier on 2026-09-25) set V1
production to official alerts plus private Quick Signals and kept ADR 0055 as a
closed staging experiment. The direction brief adopted later that day archives
the ADR 0055 community summary and makes one-tap signals public on Spots; ADR
0065's principles carry forward (see its status note). Its completed ADR 0055
pilot work stays in code, default-off, and its fail-closed flag tests keep
running: P0 fail-closed flags, P1 withdrawal suite, contribution isolation and
quota, threshold boundaries, and API/telemetry leakage checks. Open ADR 0055
pilot items (adversarial/Sybil red team, pilot metrics, comprehension study,
`ParticipationEligibility`, production decision) are archived with it; see
`docs/archive/features/live/ADR0055_PILOT_MEASUREMENT_PROTOCOL.md`.
