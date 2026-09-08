# Realtime architecture
Gateway scaffold fails closed; no public socket subscriptions yet.
Future socket handshake authentication must be followed by per-subscription authorization. Membership expires with travel context. Block/Ghost Mode changes must propagate across replicas.
Redis fanout can carry ephemeral updates; durable writes require persistent delivery/reconciliation, not Pub/Sub as the only record. Duplicate/reordered messages need stable event IDs and bounded reconciliation.

