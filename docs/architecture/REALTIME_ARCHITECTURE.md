# Realtime architecture

Short temporary Spot chat and the festival route room (see
[`../PRODUCT.md`](../PRODUCT.md)) use bounded authenticated foreground HTTP refresh in the pilot, not WebSockets
([ADR 0066](../adr/0066-bounded-http-refresh-for-spot-chat.md)): every 15–20 s
while a Spot panel or room is open, about once a minute otherwise, never in the
background, one request in flight, cursor-based. `backend/realtime` stays
scaffold only; WebSockets are revisited after Pongal only if the festival room
behaves like a live group chat. Either way, the Spot panel,
map markers and chat share the same authorized, expiry-aware projection, and every
read checks current membership, blocks, restrictions and moderation; a slower
transport does not permit stale authorization or client-side privacy filtering.
Realtime carries no presence, locations or traveller counts. The archived LIVE list
used bounded HTTP refresh (ADR 0022); its code stays default-off.

Gateway scaffold fails closed; no public socket subscriptions yet.
Future socket handshake authentication must be followed by per-subscription authorization. Room membership expires with the Spot activity, journey or festival event window; aliases are per room. Block/Ghost Mode changes must propagate across replicas.
Redis fanout can carry ephemeral updates; durable writes require persistent delivery/reconciliation, not Pub/Sub as the only record. Duplicate/reordered messages need stable event IDs and bounded reconciliation.
