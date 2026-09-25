# Data architecture

Journey, Spots and Ask Ahead (planned; see [`../PRODUCT.md`](../PRODUCT.md)):

- **Spots:** a seeded catalog for the pilot corridor (about 50–100), reusing the
  curated anchor catalog and route-anchor matching; later user suggestions go
  through moderation.
- **Posts and signals:** tied to a Spot, not to the author's position; per-room
  alias, capture time, server-time expiry by type, "Still true?" confirmations,
  author delete. Reuse the transactional signal storage, idempotent receipts and
  abuse budgets. After expiry only per-Spot highlights (top tips) remain; no chat archive.
- **Voice notes:** objects in S3 via signed direct upload; PostgreSQL holds
  metadata, moderation state and expiry. Expired or deleted objects are purged.
- **Ask Ahead:** questions pinned to a Spot and expiring with it; answers.
  Recipient-selection records are internal, bounded and short-lived (to avoid
  repeat targeting); the asker never learns who was offered a question.
- **Spot passage:** opt-in, coarse time, deleted within 24 hours by a bounded
  purge job reusing ADR 0036 expiry maintenance. No continuous location or GPS
  history is stored.
- **Route guides:** published from finished journeys with exact home, office and
  start/end addresses stripped; private until published.

Finalize the retention/storage ADR before migrations; cache TTL is not proof of
database purge. The archived LIVE model (ADR 0022) planned PostgreSQL consent
authority and Redis-cached projections/leases; its built code remains default-off.

PostGIS stores durable geographic/domain data. Flyway owns versioned schema changes. Redis holds ephemeral room/chat state, caches and rate-limit counters, each with an explicit TTL; it holds no presence and is not durable business truth. S3 stores validated media (voice notes; photos later).
Local development uses Docker Compose with loopback ports and disposable development credentials. No production data is imported.
Initial catalog is curated seed content, with conservative descriptive facts and no claimed live conditions. Plans are local drafts, not persisted server journeys.
