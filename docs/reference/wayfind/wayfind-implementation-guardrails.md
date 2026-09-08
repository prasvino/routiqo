# Wayfind implementation guardrails

Reviewed: 2026-09-06. Purpose: preserve relevant lessons from the previous app for the TrailSangam build.

This is a reference, not an instruction to modify the old repositories or a decision to reuse their entire stack. Review covered the instruction folders, selected root/deployment documents, and focused frontend source/configuration checks. Backend behavior below is documented behavior, not a fresh backend code audit or runtime verification. No application tests were run for this documentation task.

## Evidence and precedence

- Use current code/configuration to establish implementation facts; use May 1 overviews for newer product context.
- February startup guides remain useful for architectural rules but contain stale implementation details.
- Findings and implementation plans are historical evidence, not proof that an issue remains open or a feature shipped.
- Resolve contradictions explicitly. Do not copy temporary auth, example credentials, old provider assumptions, or deployment claims into the new app.
- See [source map and discrepancies](./wayfind-source-map.md) for exact references.

## Architecture and contracts

The previous frontend uses npm workspaces and Turbo with four packages: React/Vite web, Expo/React Native mobile, shared TypeScript logic, and shared UI. Spring Boot lives in a separate backend repository.

1. Keep domain types, API services, reusable data hooks, and environment resolution in the shared package. Keep browser/native APIs and platform-specific presentation in their respective clients.
2. Route application API requests through the shared API client. It owns bearer auth, refresh retry, response unwrapping, and normalized errors. Signed object-storage uploads are a separate transport, not a reason to duplicate the app API client.
3. Use TanStack Query for server state. Keep DTO-to-UI mapping explicit in services; update shared types, services, hooks, and both clients when a contract changes.
4. Keep the `/api/v1` prefix centrally configured. Avoid independently maintained endpoint paths in multiple services.
5. Preserve structured origin/destination and coordinates. Title parsing is a compatibility fallback in existing code, not a new domain model.
6. Backend guidance uses Controller -> Service -> Repository, DTO responses, and Flyway migrations with Hibernate schema auto-generation disabled. Add versioned migrations rather than changing a deployed schema ad hoc.
7. Strict TypeScript is an intended rule, but the old web package does not enforce it. For a new build, explicitly enable and verify strictness rather than copying its config.

Sources: both `instructions/instructions.md`, both `instructions/overview.md`, frontend architecture guide, shared API client, journey/user services, and TypeScript configs.

## Authentication and account lifecycle

- Use authenticated server identity and backend ownership/visibility checks. The early `X-User-Id` shortcut is obsolete.
- Web refresh transport is an HttpOnly cookie; native mobile uses body transport with secure storage. The explicit header is `X-Auth-Refresh-Transport: cookie|body`.
- Cookie mode must not return the refresh token in JSON. Keep refresh/logout transport selection consistent; do not silently mix sources.
- Existing shared client retries a failed request once after a 401 refresh. Existing auth service coalesces concurrent refreshes through a shared promise. Preserve bounded retry and avoid refresh storms.
- Session expiry, logout, and socket reconnect must work together. A broken socket should not leave the application trapped in an auth loading state.
- Documented account deletion requires exact `DELETE` confirmation and conditional password reauthentication. A capability such as `requiresPasswordReauth` should drive client UI rather than guessing from provider names.
- Deactivation, pending deletion, cancellation/reactivation, and final purge require explicit state transitions. The old deletion specification has contradictory login/reactivation language and unselected retention alternatives: resolve these before implementation.
- Purge needs referential-integrity and media-cleanup verification. A scheduled job existing does not prove erasure works end to end.

Sources: backend auth guide, frontend auth service, native secure storage, deletion specification, production-readiness plan, March findings.

## Realtime and notifications

1. Match the actual client/server protocol. Historical SockJS/raw-WebSocket mismatch caused handshake failures; the later idle-time fix documents raw STOMP WebSocket. Recheck server configuration before reuse.
2. Forward WebSocket upgrades in local and deployed proxies; use environment-configured endpoints.
3. Bound reconnect attempts/delay, set connection timeouts and heartbeat behavior, and prevent reconnect on intentional logout. Verify recovery after idle/backgrounding.
4. Persist durable notifications independently of delivery. Hydrate/reconcile through HTTP, deduplicate incoming events, respect notification preferences, and use controlled fallback polling.
5. An in-memory broker is local to a backend instance. Multi-replica delivery needs verified cross-instance fanout. The existing design documents a Kafka relay; its presence alone does not prove it is enabled in production.
6. Test sender and recipient connected to different replicas, restarts, duplicate delivery, token expiry, and broker outage. Record publish/consume/delivery failures and dead-letter accumulation.

Sources: `idleTime.md`, `ToDoFeb28.md`, backend startup guide, deployment guide, shared websocket service and notification hook.

## Media, search, and derived data

- Preserve the signed-upload sequence: request SAS URL -> upload directly to object storage -> confirm -> asynchronous processing result.
- Validate file size/type on clients and validate content on the backend. Authenticate processing callbacks; expose retry/failure states and clean up orphaned uploads.
- Keep upload/query invalidation coordinated so stop, media, place, and journey views agree after completion.
- Search guidance uses PostgreSQL trigram indexes, bounded cursor pagination, debouncing, and short-lived caching. Validate cache keys/invalidation against visibility and query parameters.
- Native SQL projection timestamp types must match JDBC/Hibernate mappings; the old search bug required `Instant` in projections and explicit conversion in service mapping.
- Location lookup now goes through `/locations/suggest` and `/locations/reverse`; do not restore the old direct browser-provider calls. Provider quotas, permitted usage, attribution, and production terms must be verified when choosing a provider. A debounce interval alone is not a global rate limiter.
- Feed distance/duration/counts should come from real domain data; demo statistics must remain isolated fixtures.
- Recap, replay, and story are distinct contracts. May documentation says existing story generation is deterministic, not an external LLM integration.

Sources: media sections in startup/overview guides, search implementation, places walkthrough, static-fields fix, current location service.

## Performance and release discipline

- Measure startup/bundle/list performance before optimizing. Lazy-load heavy routes/features, virtualize long native lists, load media progressively, and retain useful data during refetch.
- Verify weak-network, reconnect, and long-idle behavior; performance work must preserve auth correctness and user visibility boundaries.
- Existing root scripts provide type-check, lint, shared/web tests, and web builds. Mobile is not included in the root test command, so a passing root test suite does not establish mobile coverage.
- Backend guide calls for unit tests plus targeted integration/event/security checks. Its documented coverage thresholds are historical, not newly verified gates.
- Release configuration needs explicit origins, production secrets, cookie settings, CSP, callback security, and API/WS endpoints. Browser-exposed environment variables must not contain secrets.
- The old deployment guidance separates a static Vite frontend from long-running backend/realtime services. Treat hosting vendors and package versions as previous choices, not commitments for TrailSangam.
- Before release, verify deployment gates and observability exist rather than assuming local scripts or checklist entries enforce them.

## Applying these lessons to TrailSangam

The following are proposed adaptations inferred from the reviewed mock; they are not implemented capabilities established by the old app.

| Mock feature | Reuse or adapt | New domain work to specify |
|---|---|---|
| Daily commute / one-time trip | Shared contracts, journey lifecycle, authenticated APIs | Recurring route template versus individual journey session; schedule/timezone rules |
| Approximate traveller presence | Auth, visibility, realtime infrastructure | Server-side location coarsening, expiry/heartbeat, route membership, aggregation and retention |
| Temporary route chat | Realtime transport and chat UI patterns | Room scope, join/leave authorization, expiry, reconnect after expiry; old direct messages are not temporary rooms |
| Familiar commuters and meetups | Profile, blocking/reporting patterns | Opt-in rules, overlap aggregation, retention, and preventing exposure of movement history |
| Route posts and voice updates | Content/media validation, uploads, moderation | Route/category schema, post freshness, recording lifecycle, short audio processing |
| Trip journals / commute summary | Stops, recap/story contracts, derived metrics | Monthly commute aggregation separate from a per-trip journal |
| Map discovery | Structured locations and search service boundary | Actual map/routing provider, road geometry, attribution, costs, and offline behavior |

Before implementing presence/chat, define what the server collects, what other users receive, when data expires, and what happens after the journey ends. The mock's privacy copy must be backed by server behavior; hiding precise coordinates in UI is insufficient.
