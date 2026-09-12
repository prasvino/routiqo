# Data architecture

Live planning (ADR 0022): PostgreSQL owns accepted structured evidence, consent
authority and idempotent receipts; derived projections/leases may use bounded Redis
caches. No raw GPS history or permanent Live archive. The canonical lifecycle and
unresolved moderation/deletion decisions are in ROUTIQO_LIVE_SPEC.md. Finalize the
retention/storage ADR before migrations; cache TTL is not proof of database purge.

PostGIS stores durable geographic/domain data. Flyway owns versioned schema changes. Redis entries require explicit TTL; Redis is not durable business truth. S3 stores validated media.
Local development uses Docker Compose with loopback ports and disposable development credentials. No production data is imported.
Initial catalog is curated seed content, with conservative descriptive facts and no claimed live conditions. Plans are local drafts, not persisted server journeys.
