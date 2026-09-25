# Realtime architecture

Short temporary Spot chat and the festival route room (see
[`../PRODUCT.md`](../PRODUCT.md)) need a transport decision: bounded authenticated
foreground HTTP refresh or WebSockets through `backend/realtime`. This is open and
requires an ADR before implementation, weighing pilot density, Android battery and
network conditions, and multi-replica fan-out. Whichever is chosen, the Spot panel,
map markers and chat share the same authorized, expiry-aware projection, and every
read checks current membership, blocks, restrictions and moderation; a slower
transport does not permit stale authorization or client-side privacy filtering.
Realtime carries no presence, locations or traveller counts. The archived LIVE list
used bounded HTTP refresh (ADR 0022); its code stays default-off.

Gateway scaffold fails closed; no public socket subscriptions yet.
Future socket handshake authentication must be followed by per-subscription authorization. Room membership expires with the Spot activity, journey or festival event window; aliases are per room. Block/Ghost Mode changes must propagate across replicas.
Redis fanout can carry ephemeral updates; durable writes require persistent delivery/reconciliation, not Pub/Sub as the only record. Duplicate/reordered messages need stable event IDs and bounded reconciliation.
