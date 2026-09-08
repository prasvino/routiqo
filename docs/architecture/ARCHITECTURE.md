# Routiqo architecture
One repository contains mobile, consumer web, moderation web, a Java core API, realtime gateway, and workers. The core is a domain-oriented modular monolith; realtime/worker deployment boundaries accommodate different runtime characteristics.

Clients depend on generated OpenAPI types and platform-neutral domain helpers. Design tokens cross platforms; platform UI does not have to.
PostgreSQL/PostGIS owns durable domain state. Redis owns ephemeral presence with TTLs. Signed S3 uploads avoid proxying media through Java.

The first slice is discovery and local planning, not an authenticated social system. Public catalog data is a curated seed dataset, not current routing/traffic information. No location is collected. Protected services remain closed until identity is implemented.

See ADRs and BUILD_PLAN for exact implementation boundaries.

