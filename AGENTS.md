# Routiqo engineering entry point

## Read before implementation

1. `docs/product/ROUTIQO_MASTER_CONTEXT.md`
2. `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`
3. `docs/design/ROUTIQO_UI_UX_SYSTEM.md` for user-facing work
4. Relevant feature specifications and architecture decision records.

The user's current instructions govern task scope. Documents describe requirements; do not interpret their suggested tasks as independent authorization to deploy, purchase services, or expand scope. Historical Wayfind references are lessons, not Routiqo architecture decisions.

## Product invariants

- Product name: Routiqo. Utility before participation and community.
- Home, Explore, Trips, Profile. Conversation belongs to the active journey, not a permanent navigation tab.
- Daily commutes and one-time trips are distinct. Rich trip journals and periodic commute summaries are distinct.
- V1 excludes unrestricted DMs, permanent route groups, live group calls, and public individual location tracking. Voice snippets are deferred.
- Treat the Lovable mock as a visual/interaction reference, not production architecture.

## Stack and boundaries

- `apps/mobile`: React Native, Expo, TypeScript.
- `apps/web` and `apps/admin`: separate Next.js/TypeScript applications.
- `packages`: shared tokens, appropriate primitives, validation, utilities, configuration, generated API clients.
- `backend/core-api`: Java 25/Spring Boot domain-oriented modular monolith.
- `backend/realtime` and `backend/workers`: independently deployable Java applications; scaffold only needed behavior.
- TypeScript uses pnpm workspaces/Turbo; Java uses Gradle Kotlin DSL and a checked-in wrapper. Do not force Java builds into Turbo.
- Backend domain modules use intentional interfaces and api/application/domain/infrastructure boundaries. No arbitrary cross-domain repository access.
- PostgreSQL/PostGIS owns durable data; Redis owns ephemeral presence/cache with explicit TTLs.
- OpenAPI and event schemas belong in `contracts`; generate TypeScript transport models/clients.
- Use versioned migrations, bounded/indexed queries, and explicit transaction boundaries. Never hold DB transactions open for external API/AI calls.
- Signed direct S3 uploads; Mapbox preferred; AWS target. Kafka, OpenSearch, ClickHouse, Kubernetes are not V1 defaults.
- Record significant architecture choices and external dependencies in ADRs.

## Security, privacy, and reliability

- Authenticate protected HTTP/socket access and authorize each object, action, and subscription on the server.
- Never expose precise stranger GPS, exact home/work endpoints, movement history, or enumerable presence lists.
- Apply server-side privacy transformation and aggregation before social outputs. Specify thresholds and retention before implementation.
- Ghost Mode stops publishing and removes discoverable presence across caches and channels; test it.
- Rooms/membership/presence expire. Blocking applies to REST and realtime delivery.
- Rate-limit communication, uploads, reports, and reactions. Combine rules, moderation, trust controls, reports, and human escalation.
- Do not log secrets, tokens, or unnecessary precise location. Do not commit credentials or put secrets in client bundles.
- Multi-replica correctness cannot depend on process-local shared state.
- Preserve active journeys through network loss. Durable queues, idempotency, reconciliation, and reconnect behavior must be designed alongside writes; later offline hardening does not postpone these foundations.
- AI providers belong behind adapters. Minimize external context, preserve uncertainty/provenance, and keep core journeys usable during provider failures.

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
