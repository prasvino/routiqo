# Presence implementation gate

Status: design for the next phase; no presence ingestion, storage or public output enabled.

Routiqo Live integration: `docs/features/live/ROUTIQO_LIVE_SPEC.md` composes this
boundary into the first journey LIVE list. Same Situation is internal eligibility,
not a public member directory. No public presence/report counts in the first
release. Existing numerical threshold candidates below are not release approval.
Moment existence, condition, freshness and disappearance also require suppression
and anti-correlation tests. Admission to a validated route is relevance, not proof
of physical location. Reading never silently enables publication. Ghost withdrawal
invalidates accepted evidence for future projection as well as new heartbeats.
The cohort ADR must resolve block-safe aggregation before any public projection.

Internal `PresenceConsent` now defines opt-in defaults, monotonic generation changes, terminal journey completion and owner/journey-bound leases of at most90seconds. Its tests cover stale heartbeats after Ghost Mode/re-enable, expiry boundaries and generation overflow. This is a pure transition policy: callers still need authoritative membership checks, atomic durable compare-and-set, rate limits and revocation propagation. Never use it as process-local consent storage or as proof that public presence is safe.

Presence must be opt-in for an owned active journey. A client-supplied route or segment identifier is not proof of membership. Before ingestion, implement server-issued route membership based on verified routing context; arbitrary coordinate or segment queries remain denied. No precise stranger points, participant lists, exact endpoints, stable public actor IDs or movement history may appear in any output.

Redis owns short-lived presence leases, initially capped at90seconds with heartbeats no more often than30seconds. The server supplies timestamps and expiry. Each actor has a monotonic consent generation bound to the active journey. Ghost Mode and completion invalidate that generation and remove discoverable state; older/reordered heartbeats must not recreate it. Consent/revocation state needs a durable source and reliable propagation to every replica. A missing generation, unavailable authority or Redis failure suppresses output. Pub/Sub alone cannot guarantee revocation.

Before any crowd output, define fixed corridor partitions and publication windows; reject arbitrary bounding boxes, overlapping custom segments and repeated fine-grained queries. Proposed conservative minimum is10 distinct eligible actors, with coarse bands instead of exact counts. This threshold is not an anonymity guarantee: release remains disabled until differencing, sparse-corridor, overlapping-window and colluding-account tests pass. Do not release counts at every membership change or tailor count differences to individual block lists.

Blocking is directional user intent with bilateral visibility/delivery exclusion. Membership enumeration is forbidden. Room creation, subscription, reconnect replay and message fanout must all enforce current block state and consent generation. Existing journey ownership APIs may be used only through intentional application interfaces; no cross-domain repository access. Separate report/moderation contracts and abuse quotas are required before messaging.

Retention: expire lease payloads within90seconds, no raw GPS history, no persistent social movement trail. Remove membership on completion, Ghost Mode and account deletion. Retain only the minimal revocation generation needed to prevent replay, with deletion semantics specified alongside its eventual database migration. Logs contain operational outcome codes and aggregate metrics only, never coordinates, full queries or token-bearing URLs.

Acceptance before exposure: concurrent Ghost Mode/heartbeat race; stale heartbeat after journey completion; reordered consent updates across replicas; expired leases; Redis outage; invalid membership; blocked REST/socket/replay delivery; account deletion; query-budget exhaustion and anti-correlation tests. Existing PresencePolicy is a pure internal building block and does not satisfy this release gate.
