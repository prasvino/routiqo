# Routiqo engineering entry point

## Read before implementation

1. `docs/PRODUCT.md` (product source of truth, from the 2026-09-25 direction brief)
2. `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md` and `docs/architecture/ENGINEERING_CONTEXT.md`
3. `docs/design/ROUTIQO_UI_UX_SYSTEM.md` for user-facing work
4. Relevant feature specifications and architecture decision records. Material in `docs/archive/` is historical, not a requirement.

The user's current instructions govern task scope. Documents describe requirements; do not interpret their suggested tasks as independent authorization to deploy, purchase services, or expand scope. Historical Wayfind references are lessons, not Routiqo architecture decisions.

## Product invariants

- Product name: Routiqo. It tells you what your journey is like right now, from people who were just there. It is not turn-by-turn navigation, a follower network or a tracker of people.
- Three concepts: **Journey**, **Spots**, **Ask Ahead** (`docs/PRODUCT.md`). New features fit inside them or wait. Earlier names (LIVE, Live Moments, Quick Signals, Living Route, Same Situation, Route Chat/Updates) are merged into these; see the PRODUCT.md glossary. Do not treat planned capabilities as implemented.
- Useful with few users, and effortless contribution: one tap beats typing, voice beats long text. Honest empty states; never fake activity.
- The active Journey is a map-first experience with the Spots ahead. Explore is demoted and replaced over time by route guides. Conversation belongs to a Spot or a festival route room, never a permanent chat tab.
- Pilot: Pongal 2027 exodus on GST Road (Chennai to Trichy/Madurai, plus Kilambakkam). Android on physical devices is the primary client; web serves route guides and planning.
- Daily commutes and one-time trips are distinct. Rich trip journals and periodic commute summaries are distinct.
- The pilot includes short text posts, voice notes, one-tap signals, temporary Spot chat and a festival route room for the event window. It excludes private DMs, permanent route groups, follower graphs, live group calls, photos, aggregate traveller counts and public individual location tracking.
- Treat the Lovable mock as a visual/interaction reference, not production architecture.

## Stack and boundaries

- `apps/mobile`: React Native, Expo, TypeScript.
- `apps/web` and `apps/admin`: separate Next.js/TypeScript applications.
- `packages`: shared tokens, appropriate primitives, validation, utilities, configuration, generated API clients.
- `backend/core-api`: Java 25/Spring Boot domain-oriented modular monolith.
- `backend/realtime` and `backend/workers`: independently deployable Java applications; scaffold only needed behavior.
- TypeScript uses pnpm workspaces/Turbo; Java uses Gradle Kotlin DSL and a checked-in wrapper. Do not force Java builds into Turbo.
- Backend domain modules use intentional interfaces and api/application/domain/infrastructure boundaries. No arbitrary cross-domain repository access.
- PostgreSQL/PostGIS owns durable data; Redis owns ephemeral room, chat and cache state with explicit TTLs.
- OpenAPI and event schemas belong in `contracts`; generate TypeScript transport models/clients.
- Use versioned migrations, bounded/indexed queries, and explicit transaction boundaries. Never hold DB transactions open for external API/AI calls.
- Signed direct S3 uploads; open-source maps direction in ADR 0021 (MapLibre/Valhalla/Photon), with migration pending; AWS target. Kafka, OpenSearch, ClickHouse, Kubernetes are not V1 defaults.
- Record significant architecture choices and external dependencies in ADRs.

## Security, privacy, and reliability

- Authenticate protected HTTP/socket access and authorize each object, action, and subscription on the server.
- Never expose precise stranger GPS, exact home/work endpoints, movement history, participant lists, or coordinate-based lookup of people.
- Active input only: posts, voice notes and signals are tied to a Spot, not the poster's position. The server does not collect continuous location. Spot passage is opt-in, detected on the device, sent only as an answer with a coarse time, and deleted within 24 hours. Aggregate traveller counts need a separate privacy review before any build.
- Ghost Mode stops all sending, including Spot passage and queued posts, across caches, channels and replicas; test it.
- Content expires by type using server time; rooms and membership expire. Aliases are per room and never link posts across rooms. Blocking applies to REST, realtime delivery and Ask Ahead recipient selection.
- Rate-limit posts, voice uploads, signals, questions, reports and reactions, with stricter limits for new accounts. Combine rules, moderation (quick hide), trust controls, reports, and human escalation. Cover business spam, false alarms and Tamil/Tanglish abuse.
- Do not log secrets, tokens, or unnecessary precise location. Do not commit credentials or put secrets in client bundles.
- Multi-replica correctness cannot depend on process-local shared state.
- Preserve active journeys through network loss. Durable queues, idempotency, reconciliation, and reconnect behavior must be designed alongside writes; later offline hardening does not postpone these foundations.
- AI providers belong behind adapters. Minimize external context, preserve uncertainty/provenance, and keep core journeys usable during provider failures.

## Codex orchestration

For substantial implementation tasks, follow
`docs/development/CODEX_ORCHESTRATION.md`.

For substantial work, GPT-6 Astra plans and orchestrates, GPT-6 Sol implements,
and Astra reviews and verifies the integrated result. GPT-6 Luna handles super
basic bounded work. Follow active session rules for delegation. The root retains
responsibility for architecture and security/privacy invariants.

## Implementation and verification

- Inspect existing code; define acceptance criteria and a focused feature specification before substantial work.
- Implement in coherent phases. Do not present a shell, fixture, or disabled integration as a working production feature.
- Enforce TypeScript strictness and API/module boundaries in tooling.
- Add meaningful tests for domain behavior, privacy, authorization, contracts, retries, and affected UI flows.
- Run relevant lint/type/build/test checks; report actual results and blockers.
- UI uses shared tokens and accessible platform-appropriate controls. Include loading, empty, error, offline, permission-denied, and reconnect states where applicable.
- Isolate high-frequency GPS/socket state. Measure performance on representative Android devices and networks.
- Run and inspect rendered UI, exercise interactions, check small screens/large text/reduced motion, and retain visual QA evidence.
- Do not bypass failing security/privacy/architecture checks to finish a task.
