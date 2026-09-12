# Realtime architecture

The first Routiqo Live release uses bounded authenticated foreground HTTP refresh
per ADR 0022 and ROUTIQO_LIVE_SPEC.md. WebSockets are later scope. List and eventual
map/socket delivery must share the same authorized, expiry-aware projection.
HTTP reads still check current consent, admission, blocks and moderation; delaying
WebSockets does not permit stale authorization or client-side privacy filtering.

Gateway scaffold fails closed; no public socket subscriptions yet.
Future socket handshake authentication must be followed by per-subscription authorization. Membership expires with travel context. Block/Ghost Mode changes must propagate across replicas.
Redis fanout can carry ephemeral updates; durable writes require persistent delivery/reconciliation, not Pub/Sub as the only record. Duplicate/reordered messages need stable event IDs and bounded reconciliation.
