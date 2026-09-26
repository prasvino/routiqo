# Routiqo engineering context

Status: current engineering context from 2026-09-25.

Provenance: extracted on 2026-09-25 from the engineering sections (§14–§31,
§37) of the archived
[`archive/product/ROUTIQO_MASTER_CONTEXT.md`](../archive/product/ROUTIQO_MASTER_CONTEXT.md),
trimmed and updated for the Journey / Spots / Ask Ahead model. The archived
location/presence architecture (§19) is replaced by
[Location and Spot passage](#8-location-and-spot-passage).

Product vision, scope and guardrails live in [`../PRODUCT.md`](../PRODUCT.md);
where this document disagrees with it, PRODUCT.md wins. Only
[`../quality/BUILD_STATUS.md`](../quality/BUILD_STATUS.md) records verified
behaviour. Nothing here is implemented unless BUILD_STATUS says so. Where a
detail is not specified, prefer the simplest production-quality implementation
that preserves these rules, and record lasting decisions in [`../adr/`](../adr/).

## 1. Technology stack

### Clients

- **Android (primary pilot client):** React Native, Expo, TypeScript. Use native
  Kotlin/Swift modules only for performance or platform capabilities (the
  Kotlin safe-HTTP module under `apps/mobile/modules/` is one).
- **Consumer web:** Next.js, React, TypeScript. Used for route guides, trip
  planning, journals, account/profile and destination content.
- **Admin/moderation web:** Next.js, React, TypeScript, kept separate from the
  consumer web app.

### Backend

- Java 25 LTS, Spring Boot, Spring MVC for normal APIs; virtual threads where
  appropriate. Do not make the whole application reactive without a measured
  requirement.
- Gradle Kotlin DSL with a checked-in wrapper.

### Data and infrastructure

| Concern | Choice |
| --- | --- |
| Durable data | PostgreSQL + PostGIS, Flyway migrations |
| Ephemeral state | Redis with explicit TTLs (room/chat state, caches, rate-limit counters) |
| Media | Amazon S3 (voice notes; photos later) via signed direct uploads; CloudFront CDN |
| Maps | MapLibre rendering, self-hosted Valhalla routing, self-hosted Photon search, regional OSM-derived vector tiles served by Martin from versioned archives (ADR 0021). Own-host styles, sprites and glyphs. Existing Mapbox code awaits migration; Mapbox tokens are not prerequisites |
| Mobile persistence | SQLite, offline-first queue design |
| Contracts | OpenAPI; generated TypeScript clients |
| Cloud target | AWS, Docker, Terraform; simple managed/container infrastructure first |
| Push | FCM (Android), APNs (iOS later); journey essentials only in the pilot |
| Observability | OpenTelemetry, Prometheus/Grafana where appropriate, structured logs, client crash monitoring |

Phone-offline rerouting needs a separately validated on-device engine and graph;
server hosting and offline map downloads alone do not provide it.

**Not V1 defaults:** Kafka (define event contracts first; introduce when volume
and fan-out justify it), OpenSearch, ClickHouse, EKS/Kubernetes.

## 2. Architectural style

- **Monorepo.** Mobile, web, admin, backend and contracts evolve together; API
  changes land atomically with their clients.
- **Backend:** a domain-oriented modular monolith for core functionality, plus
  separately deployable realtime and worker applications. No new microservice
  without an ADR and concrete justification. Split a module only for measurable
  scaling, reliability, deployment, security or organisational reasons.
- **Realtime is separate** because long-lived connections scale differently
  from HTTP: Spot chat and festival route room subscriptions, message and signal
  fan-out. The pilot does not use it: Spot chat and rooms use
  bounded HTTP refresh ([ADR 0066](../adr/0066-bounded-http-refresh-for-spot-chat.md),
  [`REALTIME_ARCHITECTURE.md`](REALTIME_ARCHITECTURE.md)). It holds no presence
  and publishes no traveller counts.
- **Workers** host asynchronous work as it appears: moderation, push
  notifications, voice-note processing orchestration, expiry and highlight
  derivation, Spot-passage purge, route-guide preparation, journal generation,
  privacy expiry, analytics export. ADR 0036 currently runs expiry maintenance
  beside core-api with its own scheduler; workers must not duplicate core
  repositories.

## 3. Monorepo structure

```text
routiqo/
├── apps/
│   ├── mobile/          # Expo / React Native, Android first
│   ├── web/             # Consumer Next.js
│   └── admin/           # Admin/moderation Next.js
├── packages/
│   ├── api-client/      # Generated OpenAPI types (never hand-edited)
│   ├── design-tokens/   # Colours, spacing, type, radii, motion
│   ├── shared/          # Planning, outbox, dispatch, routing, journal, backup logic
│   └── config/          # Shared TS/lint/build configuration
├── backend/
│   ├── core-api/        # Spring Boot modular monolith
│   ├── realtime/        # Separately deployable gateway (scaffold, fails closed)
│   └── workers/         # Separately deployable async jobs (scaffold)
├── contracts/
│   ├── openapi/         # core.yaml
│   └── events/
├── docs/
├── tests/               # Cross-package Vitest suites
└── scripts/
```

- `apps/` = deployable TypeScript clients; `packages/` = reusable TypeScript
  libraries; `backend/` = deployable Java applications; `contracts/` =
  language-neutral schemas.
- pnpm workspaces + Turborepo for TypeScript; Gradle for Java. Do not force Java
  builds into Turbo.
- Add `packages/ui` or `packages/validation` only when sharing genuinely improves
  maintainability.

## 4. Mobile source organisation

Feature-oriented. Current `apps/mobile/src/features/` holds `discovery`,
`journey` and `live` (default-off archived flows). Planned features for the
pilot fit inside the three concepts, for example `journey` (map, Spots ahead),
`spots` (Spot panel, signals, posts, voice notes, chat), `ask-ahead`,
`route-guides`, `privacy` (Spot-passage opt-in, Ghost Mode). No `presence`
feature.

Use a reliable server-state library (e.g. TanStack Query) and keep client-only
state minimal. No giant global store. Isolate high-frequency location and
socket state from list and map rendering.

## 5. Java core API organisation

Organise by business domain, not a global controller/service/repository
hierarchy. Each domain has `api/`, `application/`, `domain/`,
`infrastructure/`. Controllers do not manipulate repositories; no arbitrary
cross-domain repository access; domain code does not depend on web, database,
Redis, map or AI vendors.

**Existing** modules in `backend/core-api/src/main/java/com/routiqo/core/`:

| Module | Role now |
| --- | --- |
| `identity` | Google sign-in, sessions, account deletion |
| `journey` | Plans, trips, commutes, journeys; core of the Journey |
| `journal` | Journals and history; source for route guides |
| `routing` | Routing provider boundary (Valhalla target) |
| `routeupdate` | Signal storage, idempotent commands, abuse budgets, expiry maintenance, curated anchor catalog and route-anchor matching, official alerts. Infrastructure for Spot signals and "Spots ahead" |
| `moderation` | Restrictions, blocks, audited moderation, operator grants |
| `privacy` | Per-journey presence consent (archived flow, default-off); candidate home for the Spot-passage opt-in |
| `publiclive` | Public LIVE / DP publication (archived, default-off) |
| `verification` | Verified-contributor authority (archived, default-off) |
| `discovery` | Curated catalog (Explore, demoted) |
| `health`, `security` | Health endpoints; browser/native auth guards |

**Planned** domains (not implemented; names may change in their ADR/spec):

| Module | Role |
| --- | --- |
| `spot` | Seeded Spot catalog (`routiqo-spots/1`, its own loader), Spot posts and signals, voice-note metadata, "Still true?", per-type expiry, highlights |
| `askahead` | Questions on Spots, bounded non-deterministic recipient selection, answers and summaries |
| `room` | Short temporary Spot chat and festival route rooms, per-room aliases, membership expiry |
| `routeguide` | Publishing finished journeys as route guides with endpoint stripping |

`spot` is a new module, not a rename of `routeupdate`
([ADR 0070](../adr/0070-spot-module-and-catalog-delivery.md)). It uses intentional
interfaces only and never depends on archived `routeupdate` code. Archived code
keeps its identifiers and stays default-off.

## 6. API and contracts

- Every public API is in [`contracts/openapi/core.yaml`](../../contracts/openapi/core.yaml).
  Flow: Java API → OpenAPI → generated `packages/api-client` → mobile/web/admin.
  Run `pnpm contracts:generate` then `pnpm contracts:check` after any change.
- Do not hand-maintain duplicate request/response models.
- Use versioning and compatibility discipline.
- Define event contracts under `contracts/events/` before any broker exists.
  Example events: `JourneyStarted`, `JourneyCompleted`, `SpotPostCreated`,
  `SpotSignalCreated`, `VoiceNoteUploaded`, `AskAheadQuestionCreated`,
  `AskAheadAnswered`, `ContentReported`, `ContentHidden`,
  `RouteGuidePublished`, `NotificationRequested`. No location-change events.

## 7. Offline-first

Travel often has poor connectivity. Mobile uses SQLite/local persistence for
active journey state, essential route state (where licences permit), pending
contributions, pending media metadata, preferences and the sync queue.

```text
User action → local durable state → sync queue → network?
                                              ├── no  → retain / retry safely
                                              └── yes → sync → reconcile
```

- Idempotency keys for every retried write. Never lose an active journey because
  connectivity drops.
- **Offline rule (PRODUCT.md):** posts, signals and answers made offline are
  queued in the journey outbox with capture time and an idempotency key. The
  server accepts them only if the type's lifetime has not elapsed since capture,
  and shows them with capture time, never as new. Ghost Mode, sign-out and
  account deletion clear queued social items.
- Cached read-only Spot content is labelled stale offline and dropped at expiry.
- Ghost Mode takes priority over reconnect and outbox replay.

Detail: [`OFFLINE_ARCHITECTURE.md`](OFFLINE_ARCHITECTURE.md).

## 8. Location and Spot passage

The pilot runs on **active input** only. No continuous location is collected on
the server; there is no GPS ingestion pipeline, presence store or traveller
aggregation.

- During an active journey the device may read location in the foreground to
  draw the map and order Spots ahead. This stays on the device.
- **Spot passage** ("I passed Spot X") is detected on the device, only after one
  clear opt-in. It is sent only with an answer (post-passing prompt) or to be
  eligible for Ask Ahead questions, with a coarse time, and deleted within
  24 hours by a bounded purge job (reuse the ADR 0036 expiry-maintenance
  pattern). Log only outcome codes.
- Posts and signals are tied to a Spot, not to the author's position.
- Ghost Mode stops all sending, including Spot passage and queued posts.
- Never expose precise location, endpoints, movement history, participant lists
  or coordinate-based lookup of people.
- Aggregate traveller counts are out of pilot scope and need a separate privacy
  review. Archived presence material: [`../archive/README.md`](../archive/README.md).

Background location, foreground-service behaviour and battery budget for Android
Spot-passage detection are an open question in PRODUCT.md.

## 9. Media

Voice notes need this now; photos later.

```text
Client → request signed upload → core API (authorize, rate-limit, size/type policy)
       → direct upload to S3 → event/worker → validation, moderation, metadata
       → CDN (authorized, expiring access)
```

- Do not proxy large uploads through Spring.
- Validate type, duration and size server-side; reject unexpected formats.
- Voice notes follow their post's expiry and deletion; objects are purged, not
  only hidden. Report/hide applies to voice notes exactly as to text.
- Strip device metadata. Offline-recorded voice notes follow the offline rule.

## 10. Authentication and authorisation

- Secure mobile/web authentication, short-lived access tokens, refresh rotation
  or an equivalent session model, device/session management. Google sign-in is
  current; others as launch requires.
- Role-based authorisation for moderators/admins; object-level authorisation for
  journeys, journals, posts, questions, rooms and reports.
- Socket handshake authentication plus per-subscription authorisation if
  WebSockets are adopted.
- Never trust client-supplied journey, Spot, room or user IDs without checks.
- Aliases never embed or reveal account identifiers.

## 11. Admin and moderation app

`apps/admin` should eventually support: report queues and fast hide for posts,
voice notes and chat; user enforcement, restrictions and blocks; moderation
audit history; **Spot seeding and curation** for the pilot corridor (create,
edit, retire, merge suggestions); **festival route room configuration** (corridor,
event window, rules); official alert review; feature flags; basic operational
analytics. Keep admin out of the consumer web app. The pilot needs a lighter
access process and an on-call rota during the rush.

## 12. Design system

- The Lovable prototype is a visual reference, not production source. Recreate
  cleanly; do not reverse-engineer bundles.
- Tokens in `packages/design-tokens` (colour, spacing, typography, radius,
  shadow, motion) are shared across mobile/web/admin. Do not force a universal
  component abstraction across React Native and web.
- Personality: warm, travel-oriented, modern, premium but approachable, Indian
  and Tamil context without stereotype. Journey and map screens are more
  utility-dense than route-guide and journal screens.
- Full system: [`../design/ROUTIQO_UI_UX_SYSTEM.md`](../design/ROUTIQO_UI_UX_SYSTEM.md).

## 13. Testing

Every feature needs appropriate automated verification.

- **Backend:** unit, domain, repository integration (PostgreSQL needs Docker),
  API integration, security/authorisation, privacy invariants, realtime tests if
  realtime is adopted.
- **Frontend:** unit/component where valuable, navigation/feature, contract,
  critical E2E.
- **System:** contract, E2E, load, failure/retry/offline, abuse/rate-limit,
  expiry, location-privacy.
- **Android:** physical-device evidence for phase gates; cloud sessions have no
  SDK, so record device checks as pending.

Critical flows: sign in; start a journey; location permission denied and
granted; Journey with Spots ahead; post a signal, text post and voice note;
"Still true?"; Ask Ahead ask and answer; post-passing prompt; Spot chat /
festival room; report and block; Ghost Mode; connectivity loss and recovery;
end journey; publish a route guide; account deletion.

Detail: [`../quality/TESTING_STRATEGY.md`](../quality/TESTING_STRATEGY.md).

## 14. Observability

Structured logs with correlation IDs. Instrument HTTP, socket sessions (if
any), database, Redis, AI calls, background jobs, push workflows, map/provider
calls and media uploads.

Monitor: API latency/errors; database and Redis latency/memory; expiry and purge
job lag (including the 24-hour Spot-passage purge); moderation queue depth and
time-to-hide; post, voice-upload and report rates against rate limits; room
health; AI cost/latency/failure; mobile crash rate; journey start/completion.

Do not log precise location, tokens or secrets. Spot passage is logged as
outcome codes only. See [`../quality/OBSERVABILITY.md`](../quality/OBSERVABILITY.md).

## 15. CI and developer experience

- Local dependencies through Docker Compose (`pnpm infra:up`): PostGIS, Redis,
  S3-compatible storage.
- CI (`.github/workflows/verify.yml`) runs `pnpm check`, `pnpm build`,
  `pnpm secrets:check` and `gradlew check bootJar`. Path-aware checks are a goal:
  contract changes verify backend plus affected clients.
- Before merge: formatting, linting, type checking, unit and relevant
  integration tests, dependency/security scanning, build verification.

## 16. Working method

For each substantial task:

1. Read `AGENTS.md`, `docs/PRODUCT.md` and the relevant specs/ADRs.
2. Inspect existing code before changing architecture.
3. State acceptance criteria.
4. Implement the smallest coherent change, default-off until its phase gate.
5. Add or update tests.
6. Run relevant format/lint/type/build/test checks.
7. Review the diff for security and privacy regressions.
8. Fix failures.
9. Update OpenAPI, docs and ADRs when required.
10. Report what changed, verification performed and unresolved risks.

Do not silently weaken privacy, security, testing or module boundaries to make a
feature easier.
