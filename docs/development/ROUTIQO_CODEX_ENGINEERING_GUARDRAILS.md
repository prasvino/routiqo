# Routiqo --- Codex Engineering Guardrails

> **Purpose:** This document complements `ROUTIQO_MASTER_CONTEXT.md`.
> The master context explains **what Routiqo is and what to build**.
> This document defines **how Codex must build it** so architecture,
> security, privacy, reliability, performance, and maintainability do
> not gradually degrade during implementation.
>
> These rules are engineering guardrails, not optional suggestions.
> Feature-specific specifications may add stricter requirements but must
> not silently weaken these rules.

------------------------------------------------------------------------

## 1. Documentation Model

Use three levels of engineering documentation.

### Level 1 --- Permanent engineering constitution

Maintain these before substantial feature implementation:

``` text
AGENTS.md

docs/
├── architecture/
│   ├── ARCHITECTURE.md
│   ├── BACKEND_ARCHITECTURE.md
│   ├── FRONTEND_ARCHITECTURE.md
│   ├── REALTIME_ARCHITECTURE.md
│   ├── DATA_ARCHITECTURE.md
│   └── OFFLINE_ARCHITECTURE.md
├── security/
│   ├── SECURITY.md
│   ├── AUTHENTICATION.md
│   ├── AUTHORIZATION.md
│   └── SECURITY_CHECKLIST.md
├── privacy/
│   ├── LOCATION_PRIVACY.md
│   ├── ANTI_STALKING.md
│   └── DATA_RETENTION_AND_DELETION.md
├── quality/
│   ├── TESTING_STRATEGY.md
│   ├── PERFORMANCE.md
│   ├── OBSERVABILITY.md
│   └── CODE_REVIEW.md
└── development/
    └── CODEX_WORKFLOW.md
```

These documents define long-lived system invariants.

### Level 2 --- Feature specifications

Before implementing a substantial feature, create or update a focused
specification.

Examples:

``` text
docs/features/
├── journey/JOURNEY_SPEC.md
├── presence/PRESENCE_SPEC.md
├── route-chat/ROUTE_CHAT_SPEC.md
├── route-updates/ROUTE_UPDATES_SPEC.md
├── moderation/MODERATION_SPEC.md
├── trip-journal/TRIP_JOURNAL_SPEC.md
└── intelligence/JOURNEY_INTELLIGENCE_SPEC.md
```

A feature specification should cover, as applicable:

-   Purpose and scope.
-   Functional behavior.
-   UX states.
-   Domain model.
-   API contract.
-   Database/data changes.
-   Authentication and authorization.
-   Privacy implications.
-   Abuse/threat cases.
-   Rate limits.
-   Realtime behavior.
-   Offline behavior.
-   Error handling.
-   Idempotency.
-   Observability.
-   Performance expectations.
-   Migration/compatibility concerns.
-   Acceptance criteria.
-   Automated tests.
-   Explicitly out-of-scope functionality.

### Level 3 --- Task implementation plans

For complex changes, create a short-lived implementation plan before
coding:

``` text
instructions/YYYY-MM-<feature-or-change>.md
```

Include:

-   Goal.
-   Existing implementation.
-   Proposed change.
-   Files/modules likely affected.
-   Security/privacy impact.
-   Failure modes.
-   Data migration impact.
-   Tests.
-   Acceptance criteria.
-   Rollout.
-   Rollback.

Do not create enormous speculative implementation documents months
before a feature is built. Permanent rules should be stable; detailed
plans should be written close to implementation time.

------------------------------------------------------------------------

# 2. Codex Working Contract

For every non-trivial task, Codex must:

1.  Read root `AGENTS.md`.
2.  Read `ROUTIQO_MASTER_CONTEXT.md`.
3.  Read the relevant architecture/security/privacy documents.
4.  Inspect existing implementation before proposing structural changes.
5.  Identify the relevant feature specification.
6.  Define or confirm acceptance criteria.
7.  Identify security, privacy, realtime, offline, and data
    implications.
8.  Implement the smallest coherent change.
9.  Add/update automated tests.
10. Run relevant formatting, linting, compilation, tests, and contract
    checks.
11. Review its own diff for regressions.
12. Update OpenAPI/event contracts/documentation when behavior changes.
13. Create an ADR for significant lasting architecture decisions.
14. Report verification performed and unresolved risks.

A task is **not complete merely because the happy path works locally**.

------------------------------------------------------------------------

# 3. Security Invariants

Routiqo handles live movement and communication between strangers. Treat
security requirements as product correctness.

## Authentication

-   All protected HTTP APIs require server-side authentication.
-   Realtime/WebSocket connections require authentication.
-   Tokens/secrets must never be logged.
-   Use short-lived access credentials and secure refresh/session
    handling.
-   Refresh/session revocation must be supported.
-   Administrative authentication must be separated by authorization
    policy from consumer access.

## Authorization

Authentication alone is insufficient.

Every resource access must verify that the authenticated actor is
permitted to access the requested object/action.

Never trust client-provided:

-   User IDs.
-   Journey IDs.
-   Route IDs.
-   Room IDs.
-   Role claims not cryptographically/server verified.
-   Location values as proof of authorization.

A valid WebSocket connection must **not** imply permission to subscribe
to arbitrary channels.

Authorization must be checked for each protected subscription/resource.

## Input handling

-   Validate all external input.
-   Apply explicit limits to payload sizes.
-   Validate uploaded file type/size/content.
-   Use parameterized database access.
-   Do not expose internal exceptions or stack traces to clients.
-   Reject unsupported fields where appropriate rather than silently
    accepting dangerous input.

## Secrets

-   No credentials/API keys in source control.
-   No production secrets in client bundles.
-   Use environment/secret management.
-   Secret scanning must run in CI.

## Admin

Administrative actions require:

-   Explicit roles/permissions.
-   Server-side authorization.
-   Audit logging.
-   Protection from consumer endpoints accidentally exposing admin
    operations.

------------------------------------------------------------------------

# 4. Location Privacy Invariants

The core privacy rule is:

> **No Routiqo feature may require exposing one user's precise real-time
> location to an unrelated stranger.**

## Required processing boundary

Conceptually:

``` text
Precise GPS
    ↓
Secure location ingestion
    ↓
Route/map matching
    ↓
Privacy transformation
    ↓
Approximate route segment / coarse cell / aggregate
    ↓
Presence and social APIs
```

Raw GPS must not leak across this boundary.

## Stranger-facing APIs must not expose

-   Raw latitude/longitude.
-   Exact current position.
-   Exact home address.
-   Exact workplace endpoint.
-   Historical movement trails.
-   Stable identifiers that enable location tracking.
-   APIs allowing nearby-user enumeration.

## Presence

Prefer aggregate information:

``` text
"128 travellers on this route"
"23 travellers around this route segment"
```

rather than individually trackable moving dots.

Apply:

-   Minimum anonymity thresholds.
-   Coarse/fuzzy spatial representation.
-   Time-limited presence.
-   Automatic expiry.
-   Anti-enumeration.
-   Rate limits.
-   Temporal/spatial smoothing where necessary.

## Ghost Mode

Ghost Mode is a hard privacy control.

When enabled:

-   Stop publishing social presence.
-   Remove/expire existing discoverable presence promptly.
-   Do not continue exposing cached presence through another channel.
-   Realtime subscriptions must respect the new privacy state.
-   Automated tests must verify this behavior.

## Logging

Precise GPS is sensitive operational data.

-   Do not place precise coordinates in ordinary application logs.
-   Do not include coordinates in exception messages unless absolutely
    necessary and protected.
-   Do not send unnecessary precise coordinates to
    analytics/observability vendors.
-   Establish explicit retention/deletion rules.

------------------------------------------------------------------------

# 5. Anti-Stalking Requirements

The system must actively prevent product features from being repurposed
into tracking tools.

Design against:

-   Following one specific stranger over time.
-   Repeated nearby-user queries.
-   User enumeration by map coordinates.
-   Correlating recurring exact home/work endpoints.
-   Scraping room membership.
-   Searching for a person across routes.
-   Repeated unwanted contact.
-   Block circumvention.

Where applicable use:

-   Aggregation.
-   Fuzzing/coarsening.
-   Minimum crowd thresholds.
-   Rate limits.
-   Query budgets.
-   Visibility expiry.
-   Internal abuse signals.
-   Blocking.
-   Trust restrictions.
-   Audit/alerting for suspicious access patterns.

Do not add a feature that materially weakens these protections without
explicit review and an ADR/security review.

------------------------------------------------------------------------

# 6. Route Room and User-Generated Content Safety

Route Rooms are temporary contextual spaces, not permanent open chat
groups.

## V1 restrictions

Do not implement without explicit later approval:

-   Unrestricted direct messages.
-   Live group audio/video calling.
-   Public exact participant locations.
-   Permanent route groups.

## Room behavior

-   Rooms are scoped to route/journey/time context.
-   Membership/subscription authorization is server controlled.
-   Rooms expire or become inactive.
-   Clients cannot enumerate all members/rooms arbitrarily.
-   Reconnection must not duplicate messages.
-   Block state must apply to realtime delivery as well as REST
    retrieval.

## Rate limiting

Every user-generated communication path requires rate limiting,
including:

-   Text messages.
-   Reactions.
-   Route updates.
-   Reports.
-   Media.
-   Future voice snippets.
-   Connection/meeting requests if introduced.

New/untrusted accounts should have stricter limits.

## Moderation pipeline

Use layered controls:

``` text
Input
  ↓
Authentication / authorization
  ↓
Rate limiting
  ↓
Deterministic spam/abuse checks
  ↓
Automated moderation
  ↓
Risk decision
  ↓
Publish / restrict / review
```

Moderation must not depend solely on an LLM/provider.

Provide easy:

-   Report.
-   Block.
-   Enforcement.
-   Human escalation for serious cases.

------------------------------------------------------------------------

# 7. Trust and Reputation

Maintain an internal trust model using appropriate signals such as:

-   Account age.
-   Verification.
-   Successful usage history.
-   Useful contributions.
-   Spam behavior.
-   Reports.
-   Blocks.
-   Enforcement history.

Use trust to control capabilities/rate limits.

Do not expose sensitive internal risk scores publicly.

Do not infer protected/sensitive personal characteristics from journey
behavior.

------------------------------------------------------------------------

# 8. Authentication Documentation

Maintain one canonical `AUTHENTICATION.md` describing:

-   Login flows.
-   Token/session model.
-   Refresh/rotation.
-   Revocation.
-   Logout.
-   Device/session management.
-   Web authentication.
-   Mobile authentication.
-   WebSocket authentication.
-   Account lock/recovery as applicable.
-   Configuration/secrets.
-   Security tests.

Avoid multiple undocumented authentication paths.

For WebSockets:

``` text
Handshake
   ↓
Authenticate
   ↓
Establish connection
   ↓
Authorize requested subscription
   ↓
Subscribe
```

Never stop at handshake authentication.

------------------------------------------------------------------------

# 9. Multi-Replica Correctness

Production correctness must assume:

``` text
replicas > 1
```

even if local development uses one instance.

Do not build correctness around process-local structures such as:

``` text
ConcurrentHashMap<userId, Session>
```

for state that must be visible across replicas.

This applies especially to:

-   WebSocket presence.
-   Route-room membership.
-   Notifications.
-   Live traveller counts.
-   Rate limiting.
-   Background jobs.
-   Distributed locks.
-   Cache invalidation.

Expected architecture:

``` text
Realtime A ─┐
Realtime B ─┼── Redis / durable event infrastructure as required
Realtime C ─┘
```

In-memory state is acceptable only when it is explicitly
disposable/local and correctness does not depend on another replica
seeing it.

Add multi-instance tests or integration simulations for critical
realtime behavior.

------------------------------------------------------------------------

# 10. Backend Architecture Guardrails

Core backend remains a **domain-oriented modular monolith** unless an
ADR justifies extraction.

Organize by domain:

``` text
identity
user
journey
route
location
privacy
presence
place
update
chat
moderation
journal
notification
media
intelligence
```

Within modules prefer:

``` text
api/
application/
domain/
infrastructure/
```

Rules:

-   Domain code must not depend on controllers.
-   Domain code must not depend directly on Redis, databases, Mapbox, or
    AI vendors.
-   Controllers must not bypass application/domain rules by manipulating
    repositories directly.
-   Modules should communicate through intentional interfaces/events,
    not arbitrary cross-package repository access.
-   Do not create a new microservice merely because a module exists.

Use architecture tests (for example ArchUnit) to enforce important
dependency rules.

------------------------------------------------------------------------

# 11. API and Contract Guardrails

-   Every public HTTP API must be represented in OpenAPI.
-   Generate TypeScript API clients from the contract.
-   Do not manually maintain duplicate client DTO definitions where
    generation is feasible.
-   API changes must consider backward compatibility.
-   Validate contract changes in CI.
-   Error responses should follow a consistent schema.
-   Pagination is required for unbounded collections.
-   Large lists must not be returned without explicit limits.
-   Writes that may be retried should support idempotency where
    appropriate.

Define domain event schemas under `contracts/events` even before Kafka
is introduced.

------------------------------------------------------------------------

# 12. Database and Data Guardrails

## PostgreSQL/PostGIS

Use for durable business/geospatial state.

Do not use the relational database as a continuous raw GPS telemetry
sink by default.

Requirements:

-   Schema changes use versioned migrations.
-   Migrations are reviewed for lock/downtime risk.
-   Queries supporting hot endpoints require index review.
-   Avoid N+1 query patterns.
-   Pagination for large collections.
-   Transactions must have clear boundaries.
-   Do not hold database transactions open while calling external
    APIs/AI.

## Redis

Use for:

-   Ephemeral presence.
-   Hot realtime state.
-   Rate limiting.
-   Short-lived counters/cache.

Redis must not become an undocumented second source of truth for durable
business data.

All ephemeral keys require intentional TTL/expiry strategy.

## Retention

Every sensitive data category should have a documented retention policy.

------------------------------------------------------------------------

# 13. Account Deletion and Data Lifecycle

Before public launch, maintain `DATA_RETENTION_AND_DELETION.md`.

It must explicitly address:

-   Profile.
-   Authentication sessions/refresh tokens.
-   Push tokens.
-   Saved places.
-   Journeys.
-   Trip Journals.
-   Photos/media.
-   Route updates.
-   Chat messages.
-   Reports/moderation evidence.
-   Precise/raw location remnants.
-   AI-derived user data.
-   Analytics.
-   Backups.
-   Third-party provider data.

Deletion must revoke active sessions promptly.

Do not retain precise movement history indefinitely "in case it is
useful later."

Aggregated data may remain only when it is genuinely non-identifiable
and permitted by the defined policy/legal requirements.

Deletion workflows require automated tests and operational
observability.

------------------------------------------------------------------------

# 14. AI Guardrails

AI is an internal capability, not an authority.

## Architecture

No business module should directly call a specific AI vendor.

Use internal interfaces/adapters.

## AI output

AI-generated:

-   Incidents.
-   Predictions.
-   Summaries.
-   Recommendations.

must preserve uncertainty and provenance.

Do not convert weak crowd evidence into statements of verified fact.

## Moderation

AI moderation is one layer only.

Combine:

-   Rules.
-   Rate limits.
-   Reputation.
-   Automated classifiers/models.
-   Reports.
-   Human review/escalation.

## Privacy

Do not send unnecessary precise location or personal data to an AI
provider.

Minimize/redact context before external AI calls.

Document what data each AI workflow sends externally.

## Cost/reliability

AI calls require:

-   Timeouts.
-   Retries only when safe.
-   Circuit-breaking/fallback behavior where appropriate.
-   Usage/cost metrics.
-   Failure handling that does not break the core journey experience.

The app must remain usable if AI services are temporarily unavailable.

------------------------------------------------------------------------

# 15. Offline and Mobile Reliability

Assume intermittent connectivity.

Requirements:

-   Active journey state survives temporary network loss.
-   Pending writes use a durable local queue where necessary.
-   Retries must not create duplicate updates/messages.
-   Use idempotency keys for retryable server writes.
-   Define conflict/reconciliation behavior.
-   Network reconnect should restore relevant realtime subscriptions
    safely.
-   Do not repeatedly request location/network resources in a
    battery-hostile way.

Background location collection must be:

-   Purpose limited.
-   Battery aware.
-   Permission aware.
-   Platform compliant.
-   Disabled when no longer required.

------------------------------------------------------------------------

# 16. Performance Guardrails

Do not use vague requirements such as "make it fast."

Before significant optimization:

1.  Measure baseline.
2.  Identify bottleneck.
3.  Define measurable acceptance criteria.
4.  Implement.
5.  Re-measure.
6.  Check correctness/privacy regressions.

Areas requiring special attention:

### Mobile/map

-   Do not rerender all markers on every GPS sample.
-   Cluster/aggregate traveller presence.
-   Virtualize long lists.
-   Load thumbnail-sized media where appropriate.
-   Avoid unnecessary global-state updates.
-   Coalesce high-frequency realtime events.
-   Monitor memory and battery usage.

### Backend

-   Bound query/result sizes.
-   Use pagination.
-   Avoid N+1.
-   Cache only with explicit invalidation/TTL.
-   Do not perform expensive AI/media operations synchronously in
    request threads when they can be queued.
-   Apply timeouts to all external dependencies.

### Realtime

Measure:

-   Active connections.
-   Fan-out latency.
-   Reconnect rate.
-   Message throughput.
-   Dropped/failed deliveries.
-   Redis latency.
-   Per-route hot spots.

Actual numeric SLOs should be added once baseline measurements exist.

------------------------------------------------------------------------

# 17. Observability Guardrails

All production components require useful telemetry.

Use:

-   Structured logs.
-   Metrics.
-   Distributed tracing where appropriate.
-   Correlation/request IDs.
-   Client crash/error monitoring.

Monitor at minimum:

-   HTTP latency/error rate.
-   WebSocket connections/disconnections.
-   Subscription failures.
-   Presence update rate.
-   Route-room throughput.
-   Redis latency/memory.
-   Database latency/pool usage.
-   Background job failures.
-   AI latency/cost/failure.
-   Moderation queues.
-   Journey start/completion.
-   Mobile crashes.

Never solve observability by logging sensitive location or
authentication data.

Alerts should be actionable rather than noisy.

------------------------------------------------------------------------

# 18. Testing Requirements

Tests are part of the feature, not post-implementation cleanup.

## Security/privacy tests

Examples of mandatory invariant tests:

``` text
shouldNeverExposePreciseLocationToRouteParticipant()
ghostModeShouldRemoveDiscoverablePresence()
blockedUserCannotReachTargetThroughRealtimeChannel()
unauthorizedUserCannotSubscribeToAnotherJourney()
expiredJourneyCannotJoinRouteRoom()
routePresenceCannotBeEnumeratedByCoordinates()
exactHomeLocationIsNeverReturnedToStrangers()
```

## Backend

-   Domain/unit tests.
-   Repository integration tests.
-   API integration tests.
-   Authorization tests.
-   Migration tests where useful.
-   Realtime integration tests.
-   Worker/idempotency tests.

## Frontend

-   Critical component/feature tests.
-   Navigation/state tests.
-   Permission-denied flows.
-   Offline/reconnect flows.
-   Generated API-client compatibility.

## System

Critical E2E paths:

-   Login.
-   Start commute/trip.
-   Deny/allow location.
-   Enter Living Route.
-   Receive aggregate presence.
-   Ghost Mode.
-   Add route update.
-   Join route room.
-   Block/report.
-   Lose/recover connectivity.
-   End journey.
-   View journal.
-   Account deletion.

## Load/chaos scenarios

As the product matures, test:

-   Large numbers of travellers on one corridor.
-   WebSocket reconnect storms.
-   Redis degradation.
-   AI provider outage.
-   Database slowdown.
-   Multiple realtime replicas.
-   Duplicate/reordered events.

------------------------------------------------------------------------

# 19. Automated Quality Gates

Documentation is not sufficient. Enforce rules in tooling where
possible.

CI should include as appropriate:

-   Java compile/tests.
-   TypeScript type checking.
-   Linting/formatting.
-   OpenAPI validation.
-   Contract compatibility checks.
-   ArchUnit architecture tests.
-   Dependency vulnerability scanning.
-   Secret scanning.
-   Static analysis.
-   Container scanning.
-   Terraform validation.
-   Database migration checks.
-   Build verification.

A failing security/privacy/architecture gate must not be bypassed merely
to merge a feature.

------------------------------------------------------------------------

# 20. Code Review Checklist

Before completing a substantial change, verify:

### Functionality

-   Acceptance criteria satisfied.
-   Error/empty/loading states handled.
-   Backward compatibility considered.

### Security

-   Authentication required where needed.
-   Object-level authorization correct.
-   Input limits/validation present.
-   No secrets/tokens logged.

### Privacy

-   Does this expose new location/user information?
-   Could this enable tracking or enumeration?
-   Does Ghost Mode still work?
-   Are retention implications documented?

### Realtime

-   Works across multiple replicas?
-   Reconnect behavior correct?
-   Duplicate/out-of-order handling considered?
-   Block/report/authorization enforced on socket paths?

### Data

-   Migration safe?
-   Query bounded/indexed?
-   TTL defined for ephemeral data?
-   No accidental new source of truth?

### AI

-   External data minimized?
-   Uncertainty represented?
-   Failure fallback exists?
-   Cost/latency observable?

### Quality

-   Tests added?
-   Relevant checks run?
-   Documentation/contracts updated?
-   No unnecessary architectural complexity?

------------------------------------------------------------------------

# 21. ADR Requirements

Create an Architecture Decision Record when introducing or changing
decisions such as:

-   New deployable service.
-   New persistent datastore.
-   Kafka/event backbone.
-   Search engine.
-   AI provider strategy.
-   Authentication/session model.
-   Location retention policy.
-   Public individual-presence model.
-   New cross-user communication capability.
-   Major framework/library.
-   Kubernetes/EKS adoption.
-   Breaking API strategy.

An ADR should record:

-   Context.
-   Decision.
-   Alternatives.
-   Security/privacy impact.
-   Operational impact.
-   Consequences.

------------------------------------------------------------------------

# 22. What Codex Must Not Do

Unless explicitly approved:

-   Do not create many microservices.
-   Do not introduce Kafka/OpenSearch/ClickHouse/Kubernetes merely
    because they appear in the long-term architecture.
-   Do not expose raw stranger GPS.
-   Do not implement public individual tracking.
-   Do not add unrestricted DMs.
-   Do not add live group calls.
-   Do not weaken authorization to simplify development.
-   Do not store tokens in insecure client storage.
-   Do not log secrets or raw sensitive location unnecessarily.
-   Do not bypass generated API contracts with ad-hoc duplicate models.
-   Do not put durable business truth only in Redis.
-   Do not use process-local state for cross-replica correctness.
-   Do not perform unbounded database queries.
-   Do not make synchronous AI calls a hard dependency for basic journey
    operation.
-   Do not silently introduce a new external dependency/provider.
-   Do not declare a task complete while relevant tests/checks are
    failing.

------------------------------------------------------------------------

# 23. Feature Implementation Template

For each substantial feature, Codex should use a specification
resembling:

``` markdown
# <Feature>

## Goal
What user/business problem is being solved?

## Scope
What is included?

## Out of Scope
What is intentionally excluded?

## User Flow
Primary and edge flows.

## Domain Model
Entities/value objects/state transitions.

## API Contract
Endpoints/events/realtime messages.

## Authorization
Who may perform/read each operation?

## Privacy
What user/location data is processed/exposed/retained?

## Abuse Cases
How can this be misused?

## Rate Limits
What limits apply?

## Data Model
Tables/indexes/TTL/cache implications.

## Realtime
Subscriptions, multi-replica behavior, ordering/reconnect.

## Offline
Queue/retry/idempotency/conflict behavior.

## AI
If used: data sent, output constraints, fallback.

## Observability
Logs/metrics/traces/events.

## Performance
Expected hot paths and measurable constraints.

## Failure Modes
Dependency failures, retries, degraded behavior.

## Tests
Unit/integration/E2E/security/privacy/load tests.

## Acceptance Criteria
Concrete definition of done.

## Rollout / Rollback
How to deploy safely.
```

Not every section needs implementation for every feature, but every
section should be consciously considered.

------------------------------------------------------------------------

# 24. Initial Documents Codex Should Produce

Before substantial Routiqo feature development, create concise first
versions of:

1.  `AGENTS.md`
2.  `docs/architecture/ARCHITECTURE.md`
3.  `docs/architecture/BACKEND_ARCHITECTURE.md`
4.  `docs/architecture/FRONTEND_ARCHITECTURE.md`
5.  `docs/architecture/REALTIME_ARCHITECTURE.md`
6.  `docs/architecture/DATA_ARCHITECTURE.md`
7.  `docs/architecture/OFFLINE_ARCHITECTURE.md`
8.  `docs/security/SECURITY.md`
9.  `docs/security/AUTHENTICATION.md`
10. `docs/security/AUTHORIZATION.md`
11. `docs/privacy/LOCATION_PRIVACY.md`
12. `docs/privacy/ANTI_STALKING.md`
13. `docs/privacy/DATA_RETENTION_AND_DELETION.md`
14. `docs/quality/TESTING_STRATEGY.md`
15. `docs/quality/PERFORMANCE.md`
16. `docs/quality/OBSERVABILITY.md`
17. `docs/development/CODEX_WORKFLOW.md`

Keep them concise and executable. Expand them when implementation
exposes real decisions.

------------------------------------------------------------------------

# 25. Final Engineering Principle

The master product context tells Codex **what Routiqo should become**.

These guardrails ensure that as implementation grows, Routiqo remains:

-   Secure.
-   Privacy preserving.
-   Anti-stalking by design.
-   Correct across multiple replicas.
-   Moderated.
-   Observable.
-   Testable.
-   Offline tolerant.
-   Performant.
-   Maintainable.
-   Capable of scaling without premature complexity.

The governing rule is:

> **Prefer enforceable invariants, automated tests, contracts, and CI
> gates over repeatedly asking an AI coding agent to "be careful."**

When a feature conflicts with privacy, safety, security, or
architectural invariants, redesign the feature rather than bypassing the
invariant.
