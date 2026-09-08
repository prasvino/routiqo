# Data architecture
PostGIS stores durable geographic/domain data. Flyway owns versioned schema changes. Redis entries require explicit TTL; Redis is not durable business truth. S3 stores validated media.
Local development uses Docker Compose with loopback ports and disposable development credentials. No production data is imported.
Initial catalog is curated seed content, with conservative descriptive facts and no claimed live conditions. Plans are local drafts, not persisted server journeys.

