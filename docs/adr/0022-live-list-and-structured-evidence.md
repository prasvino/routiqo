# ADR 0022: Journey-scoped LIVE list and structured evidence

Status: accepted planning direction; implementation pending.

The first Routiqo Live release is an active-journey list with Live Moments and
Quick Signals, composed from existing journey, presence and Route Update concepts.
Product details belong in `docs/features/live/ROUTIQO_LIVE_SPEC.md`.

## Decision

- Use the Java core modular monolith for evidence ingestion and authorized moment
  projection. Reuse intentional application interfaces for journey ownership and
  presence consent. No cross-domain repository access or parallel social model.
- Keep accepted evidence, idempotency and consent authority in PostgreSQL with
  explicit bounded retention. Derived data and leases may use Redis with TTLs;
  Redis Pub/Sub is not the only record of acceptance or revocation. Tables and
  exact lifetimes require the next storage design review before migrations.
- Deliver the first list through bounded authenticated HTTP refresh. It does not
  depend on operational WebSockets; later realtime must use the same authorized
  projection and revalidate revocation, consent, blocks and expiry.
- Use deterministic evidence rules first. No AI dependency, public participant
  counts, identity lists, chat, photos, or persistent live client cache in release 1.
- Reuse structured Route Updates as Quick Signal evidence. Live Moments are derived
  situation projections, not permanent groups. Map and list eventually share data.

## Consequences and remaining decisions

This limits infrastructure and keeps the first utility test independent of chat,
AI and full native navigation. HTTP refresh adds bounded delay; the UI must show
freshness honestly. Core/Redis failures suppress output instead of trusting stale
authorization. No live feature is implemented by accepting this ADR.

Before aggregate endpoints, a cohort/admission ADR must settle fixed spatial/time
partitions, thresholds, independent-evidence criteria, query budgets, Ghost/read
semantics and block-safe suppression under repeated/colluding queries. Before
migrations, settle cleanup, moderation holds, deletion and retry tombstones.
Before Ask Ahead or WebSockets, separately settle recipient selection/consent and
multi-replica revocation/fanout. These are explicit release gates, not assumed safe
because identifiers are opaque or actors are authenticated.
