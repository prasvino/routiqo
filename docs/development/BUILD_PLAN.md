# Current implementation plan

Updated 2026-09-25 for the direction reset. Product scope, phase goals and gates
come from [`../PRODUCT.md`](../PRODUCT.md); the release checklist is
[`../../todo.md`](../../todo.md); verified behaviour is recorded only in
[`../quality/BUILD_STATUS.md`](../quality/BUILD_STATUS.md). The former LIVE
execution queue and L0–L5 stages are archived verbatim in
[`../archive/development/BUILD_PLAN_LIVE_QUEUE_2026-09.md`](../archive/development/BUILD_PLAN_LIVE_QUEUE_2026-09.md).

## Rules that apply to every phase

- The user's current instructions set task scope; this plan does not authorize
  deployment, purchases or flag activation.
- New capabilities ship default-off behind server and client flags until their
  phase gate passes. Archived capabilities (per-journey consent, private route
  preparation, private Quick Signal controls, V3 community traffic, public LIVE
  prerequisites) stay in code, default-off; do not extend them. Removing any of
  that code is a separate decision for the user.
- Write a focused spec (data, authorization, privacy, offline and failure
  boundaries) before building each new capability, and an ADR for significant
  architecture or dependency choices.
- Active input only: no continuous location on the server. **Traveller counts
  are forbidden**; report counts ("3 reports in 20 min") are allowed. Short text
  posts, voice notes and short temporary Spot chat are in scope; private DMs,
  follower graphs and background prompts are not.
- Ghost Mode stops all sending and outranks reconnect and outbox replay. Blocks
  apply to REST, realtime, room subscription, Ask Ahead recipient selection and
  replay.
- Android on physical devices is the primary client. Emulator and synthetic
  evidence does not close a phase gate. Cloud agent sessions have no Android SDK;
  record device checks as pending.
- Keep four tabs (Home, Explore → Guides when route guides ship, Trips, Profile)
  and existing offline journey writes. The active Journey is a full-screen map
  mode opened from Home with a "Back to journey" bar, not a tab; no chat tab.
  Explore is demoted: keep it working, invest in route guides instead.

## Timeline (decided 2026-09-25)

- **By about 1 November 2026:** Phase 1 gate plus the reduced Phase 2 scope for
  the Diwali dry run: Spots ahead, one-tap signals, text posts, expiry with
  "Still true?" / "No longer true", report/block/hide moderation, official alerts
  on the corridor districts. Nothing else is started before Diwali.
- **Diwali 2026 (outbound rush 5–7 November):** first dry run (Phase 4) on the trunk and
  branches.
- **November–December:** rest of Phase 2 (voice notes, Spot chat, festival room)
  and Phase 3 (Spot passage, post-passing prompts, Ask Ahead, route guides),
  then a December long-weekend dry run.
- **Pongal 2027 (tentatively 8–14 January, to be finalised):** public launch (Phase 5), adding the Chennai →
  Salem → Coimbatore trunk.

## Phases and gates

| Phase | Goal | Gate to move on |
| --- | --- | --- |
| 1. Foundations | Google sign-in, hosted maps and routing, Android tested on real devices | App installs and runs a full journey on 3+ physical Android phones |
| 2. Spots and posts | Seeded Spots, signals, posts, voice notes, expiry, moderation | 10 testers complete a real highway trip on a corridor and post (the Diwali dry run can serve as this trip for the reduced scope) |
| 3. Ask Ahead and route guides | Questions on Spots, post-passing prompts, publish a guide | Questions get answered on a test trip |
| 4. Dry runs | Diwali 2026 on the reduced scope; a December long weekend for the rest | No blocking bugs; moderation works |
| 5. Pongal pilot | Public launch on the trunk (Chennai incl. Kilambakkam → Trichy), branches (Thanjavur, Madurai → Tirunelveli, Tuticorin) and the Coimbatore trunk | Measured against PRODUCT.md success signals |

## Engineering sequence

### Phase 1 — Foundations

Reuse:

- Native Android account/journey integration, redirect-safe transport, secure
  vault, SQLite outbox and reconnect dispatch
  (`../features/journey/NATIVE_ANDROID_JOURNEY_SPEC.md`,
  `../features/journey/OUTBOX_SPEC.md`, `../features/journey/DISPATCH_SPEC.md`).
- Native Google sign-in and `native-auth` HTTP
  (`../features/auth/NATIVE_AUTH_HTTP_SPEC.md`, [OAuth setup](GOOGLE_OAUTH_SETUP.md)).
- Valhalla/Photon adapters, routing runtime and coverage rules, MapLibre on web and
  native (ADR 0021, ADR 0060, `../features/journey/ROUTING_RUNTIME_SPEC.md`,
  `../features/journey/ROUTING_COVERAGE_SPEC.md`,
  `../features/journey/MAP_DATASET_SPEC.md`,
  `../features/journey/NATIVE_ROUTE_PLANNING_SPEC.md`).
- Native history and journal (`NATIVE_ACCOUNT_HISTORY_SPEC.md`,
  `NATIVE_JOURNAL_*_SPEC.md`).

Sequence:

1. Configure Google Web and Android OAuth clients and a staging HTTPS API; run a
   real sign-in on Android.
2. Import and host Valhalla, Photon and tiles/styles scoped to the trunk and
   branch corridors (add Coimbatore before Pongal); set fixed origins, bounds and
   redacted logs.
3. New spec: **Android Journey map** (full-screen journey mode opened from Home
   with a "Back to journey" bar; selected route on map, foreground-only "me"
   during an active journey, a panel slot for Spots ahead, offline and permission
   states). Implement behind a flag.
4. Close the native Android, routing, history and journal ledgers on 3+ physical
   phones with a signed internal build.

The native private route preparation checkpoint (ADR 0061, commit `f5c5d7e`) is
paused and default-off; it is not part of Phase 1. See
[`../validation/IMPLEMENTATION_RESUME.md`](../validation/IMPLEMENTATION_RESUME.md).

### Phase 2 — Spots and posts

Reuse:

- Curated anchor catalog, loader and route-anchor matching become seeded Spots
  and "Spots ahead" (`../features/live/ROUTE_ANCHOR_RESOLUTION_SPEC.md`,
  `ANCHOR_DISPLAY_METADATA_SPEC.md`, ADRs 0031/0042).
- Signal storage, idempotent commands, abuse budgets, expiry maintenance and
  stop/withdraw become Spot signal and post infrastructure
  (`SIGNAL_STORAGE_SPEC.md`, `SIGNAL_COMMAND_SPEC.md`, `SIGNAL_ABUSE_BUDGET_SPEC.md`,
  `LIVE_EXPIRY_MAINTENANCE_SPEC.md`, `SIGNAL_COMMAND_STOP_SPEC.md`; ADRs 0028,
  0036, 0037, 0046/0047). Withdraw becomes "delete my post".
- Restrictions, blocks, audited moderation, moderator queue and grant console
  (ADRs 0039, 0040, 0041, 0056, 0057; `DURABLE_BLOCK_POLICY_SPEC.md`,
  `AUDITED_CONTRIBUTION_MODERATION_SPEC.md`, `V3_MODERATOR_WORKFLOW_SPEC.md`,
  `V3_OPERATOR_GRANT_ADMIN_SPEC.md`, `DURABLE_REPORT_INTAKE_PROPOSAL.md`),
  re-scoped from traffic summaries to posts, voice notes and chat.
- Journey outbox and idempotency for offline posts (PRODUCT.md *Offline rule*).
- Official alert pilot, optional, shown on Spots and the Journey (ADR 0050).
  Extend the fixed district list from the Chennai area to the corridor districts
  before Diwali (north-east monsoon season).

New specs, in order (1, 2 and 5 are needed for Diwali):

1. **Spots** — seeding about 150–200 Spots on the trunk and branches, catalog
   versioning, live/fade, Spots-ahead ordering, honest empty states, bounded
   refresh per ADR 0066.
2. **Posts and signals** — public one-tap signals (from private Quick Signals),
   text posts, the decided per-type lifetimes on server time, "Still true?"
   (resets to half base life, capped), "No longer true" (two distinct accounts
   expire early), highlights ranked by confirmations, report counts, rate
   limits.
3. **Voice notes** — signed direct S3 upload, limits, retention, deletion and
   report/hide.
4. **Aliases and rooms** — server-generated random per-room aliases from a
   curated English/Tamil-friendly word list, numbered collisions, account-level
   blocks without revealing identity, audited moderator lookup; Spot chat and
   the festival route room over bounded HTTP refresh
   ([ADR 0066](../adr/0066-bounded-http-refresh-for-spot-chat.md);
   `backend/realtime` stays scaffold only).
5. **Pilot moderation** — report intake for posts/voice/chat, content hide
   action (follow-up to ADR 0041), simplified time-boxed grants; see
   [PILOT_MODERATION_RUNBOOK.md](PILOT_MODERATION_RUNBOOK.md).

Then implement Spots read path, signals and posts, voice, moderation, chat — each
default-off, with Ghost Mode, block, deletion and offline tests.

### Phase 3 — Ask Ahead and route guides

Reuse: Spot and post infrastructure from Phase 2; completed journeys, journals and
history as route-guide source material (`../features/journey/TRIP_JOURNAL_SPEC.md`).

New specs:

1. **Spot passage** — per the PRODUCT.md decision: location foreground service
   with visible notification only during an active journey, balanced accuracy
   about every 100 m / 30 s, no background-location permission, on-device match
   against the next ~20 Spots ahead with a ~150 m pass radius; one opt-in,
   coarse time, 24-hour deletion, outcome-code logging; prompt waits for
   stopped/slow (under ~10 km/h for 20 s) or the next app open and expires after
   ~30 min; battery target ~3–4% per hour measured on the Phase 1 phones.
2. **Ask Ahead** — bounded, non-deterministic recipient selection that respects
   blocks and avoids repeat targeting; asker never learns recipients; honest
   no-answer state; answer summaries.
3. **Route guides** — publishing a completed journey with journal notes and Spot
   tips; strip exact home, office and start/end addresses; web display for
   planning. Rename Explore to Guides when this ships.

### Phase 4 — Dry runs

Diwali 2026 on the reduced scope, then a December long weekend for voice notes,
chat, Ask Ahead, prompts and guides. No new product features beyond each
run's scope. Set exact success targets; production database, secrets,
networking, TLS and flags; migration/backup/rollback rehearsal (including the V13
acceptance-writer quiet period); expiry throughput and alerts; redacted monitoring;
on-call moderator rota; privacy disclosures and retention; authenticated release QA
and load checks.

### Phase 5 — Pongal pilot

Signed Android release, remote CI on the release commit, Pongal capacity plan,
festival room windows, on-call rota, then launch on the corridors with only
gate-passed flags enabled and measure against the success targets.

### Later, not pilot

Pulse, aggregate traveller counts (separate privacy review), commute rooms,
meetups, photos, push beyond journey essentials, GPS-following guidance and
downloaded offline maps. See PRODUCT.md.

## Historical foundation plan

The following records the initial scope, not the current implementation backlog.

Date: 2026-09-06. User authorized autonomous development, pausing only sections that require missing decisions/credentials.

## Acceptance criteria
- Pinned pnpm/Turbo TypeScript workspace and Java 25 Gradle multi-project build.
- Next.js web/admin and Expo mobile applications compile with strict TypeScript.
- Local PostGIS, Redis, S3-compatible services have health checks and loopback-only ports.
- Public catalog/status endpoints described in OpenAPI; generated client stays in sync.
- All unimplemented protected backend paths fail closed. No fake authentication or live presence.
- Useful discovery UI with search/category filtering, place details, saved destinations, and local journey plans.
- Local drafts survive reload, validate persisted data, and are clearly separate from live journeys.
- Mobile reuses domain helpers/tokens while keeping native UI/storage platform-specific.
- Backend lifecycle/privacy rules have meaningful tests even before exposed endpoints.
- Rendered desktop/mobile-width web QA, keyboard use and real interactions verified.
- CI runs relevant contracts, strict types, lint, tests, builds, Java and secret scanning.

## Visual thesis
Warm ivory, dark forest teal, editorial travel photography, and restrained route-inspired details. Discovery is inviting; planning remains legible and practical.

## Content plan
Home: destination prompt, journey CTA, one photographic route feature, curated discovery.
Explore: searchable/filterable destination collection.
Trips: durable local plans, clear empty state, edit/remove flow.
Profile: local preferences and privacy explanation; no pretend logged-in account.

_2026-09-25:_ Explore is demoted. Keep it working; curated destinations are
replaced over time by route guides (Phase 3). Home leads with the Journey and
Spots ahead once they exist.

## Interaction thesis
A focused journey-planning dialog, subtle image hover affordance, and short state transitions with reduced-motion support. No decorative map or fabricated traveller counts.

## Deferred sections
- Auth provider/session implementation: document secure model, do not create bypass.
- Maps migration: follow ADR 0021 (MapLibre/Valhalla/Photon/Martin); first generalize
  provider contracts and migrate web rendering/adapters, then verify regional data
  services and native downloads/navigation. Android development build/device and
  regional datasets are prerequisites; Mapbox credentials are not required.
- Live presence/rooms: _archived 2026-09-25._ Presence, anonymity thresholds and cohorts are out of the pilot (see `../archive/README.md`). Short temporary Spot chat and the festival route room are Phase 2, with per-room aliases, expiry, blocks and moderation.
- Real Android/iOS validation: _superseded._ An API 36 emulator exists; physical Android devices (3+ phones) are the Phase 1 gate. iOS is not in the pilot.
- External AI, S3 production, push, cloud deployment: require provider configuration.

Offline draft persistence is implemented alongside planning; it is not postponed to a late phase. Local planning does not claim a live server journey.
