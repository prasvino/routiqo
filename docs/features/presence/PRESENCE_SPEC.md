# Presence implementation gate

Status: design for the next phase; no presence ingestion, storage or public output enabled.

Presence must be opt-in for an owned active journey. A client-supplied route or segment identifier is not proof of membership. Before ingestion, implement server-issued route membership based on verified routing context; arbitrary coordinate or segment queries remain denied. No precise stranger points, participant lists, exact endpoints, stable public actor IDs or movement history may appear in any output.

Redis owns short-lived presence leases, initially capped at90seconds with heartbeats no more often than30seconds. The server supplies timestamps and expiry. Each actor has a monotonic consent generation bound to the active journey. Ghost Mode and completion invalidate that generation and remove discoverable state; older/reordered heartbeats must not recreate it. Consent/revocation state needs a durable source and reliable propagation to every replica. A missing generation, unavailable authority or Redis failure suppresses output. Pub/Sub alone cannot guarantee revocation.

Before any crowd output, define fixed corridor partitions and publication windows; reject arbitrary bounding boxes, overlapping custom segments and repeated fine-grained queries. Proposed conservative minimum is10 distinct eligible actors, with coarse bands instead of exact counts. This threshold is not an anonymity guarantee: release remains disabled until differencing, sparse-corridor, overlapping-window and colluding-account tests pass. Do not release counts at every membership change or tailor count differences to individual block lists.

Blocking is directional user intent with bilateral visibility/delivery exclusion. Membership enumeration is forbidden. Room creation, subscription, reconnect replay and message fanout must all enforce current block state and consent generation. Existing journey ownership APIs may be used only through intentional application interfaces; no cross-domain repository access. Separate report/moderation contracts and abuse quotas are required before messaging.

Retention: expire lease payloads within90seconds, no raw GPS history, no persistent social movement trail. Remove membership on completion, Ghost Mode and account deletion. Retain only the minimal revocation generation needed to prevent replay, with deletion semantics specified alongside its eventual database migration. Logs contain operational outcome codes and aggregate metrics only, never coordinates, full queries or token-bearing URLs.

Acceptance before exposure: concurrent Ghost Mode/heartbeat race; stale heartbeat after journey completion; reordered consent updates across replicas; expired leases; Redis outage; invalid membership; blocked REST/socket/replay delivery; account deletion; query-budget exhaustion and anti-correlation tests. Existing PresencePolicy is a pure internal building block and does not satisfy this release gate.
