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

ADR 0023 defines internal signal admission against owned active journeys, current
consent and a versioned server-owned route context. Its pure application check is
not an authority store or token issuer. Admission is invalid after route revision,
consent generation or journey lifecycle changes. Future ingestion must bind the
check atomically to accepted writes; evidence withdrawal remains a separate policy.

Internal `PresenceConsent` defines opt-in defaults, monotonic generation changes,
terminal journey completion and owner/journey-bound leases of at most 90 seconds.
ADR 0026 now persists one minimal latest row per account with journey-scoped CAS,
opt-out reads and completion revocation under the account/journey transaction.
There is still no public consent command or lease issuer. A same-state off write
does not advance generation, so public intent ordering must prevent a delayed
enable with that same generation before claiming all reordered opt-outs win.

Presence must be opt-in for an owned active journey. Durable consent state alone
is not proof of presence or publication eligibility. A client-supplied route or
segment identifier is not proof of membership. Before ingestion, implement
server-issued route membership based on verified routing context; arbitrary
coordinate or segment queries remain denied. No precise stranger points,
participant lists, exact endpoints, stable public actor IDs or movement history
may appear in any output.

Redis owns short-lived presence leases, initially capped at90seconds with heartbeats no more often than30seconds. The server supplies timestamps and expiry. Each actor has a monotonic consent generation bound to the active journey. Ghost Mode and completion invalidate that generation and remove discoverable state; older/reordered heartbeats must not recreate it. Consent/revocation state needs a durable source and reliable propagation to every replica. A missing generation, unavailable authority or Redis failure suppresses output. Pub/Sub alone cannot guarantee revocation.

Before any crowd output, define fixed corridor partitions and publication windows; reject arbitrary bounding boxes, overlapping custom segments and repeated fine-grained queries. Proposed conservative minimum is10 distinct eligible actors, with coarse bands instead of exact counts. This threshold is not an anonymity guarantee: release remains disabled until differencing, sparse-corridor, overlapping-window and colluding-account tests pass. Do not release counts at every membership change or tailor count differences to individual block lists.

Blocking is directional user intent with bilateral visibility/delivery exclusion. Membership enumeration is forbidden. Room creation, subscription, reconnect replay and message fanout must all enforce current block state and consent generation. Existing journey ownership APIs may be used only through intentional application interfaces; no cross-domain repository access. Separate report/moderation contracts and abuse quotas are required before messaging.

Retention: expire future lease payloads within 90 seconds, no raw GPS history and
no persistent social movement trail. PostgreSQL retains only the latest bounded
consent tuple until replacement or account deletion; it has no timestamp or
history. Completion revokes matching state atomically. Future Ghost commands,
membership/cache removal and delivered-event invalidation still need reliable
propagation. Logs contain operational outcome codes and aggregate metrics only,
never identifiers, coordinates, full queries or token-bearing URLs.

Acceptance before exposure: concurrent Ghost Mode/heartbeat race; stale heartbeat after journey completion; reordered consent updates across replicas; expired leases; Redis outage; invalid membership; blocked REST/socket/replay delivery; account deletion; query-budget exhaustion and anti-correlation tests. Existing PresencePolicy is a pure internal building block and does not satisfy this release gate.
