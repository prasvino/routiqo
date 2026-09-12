# Routiqo architecture
One repository contains mobile, consumer web, moderation web, a Java core API, realtime gateway, and workers. The core is a domain-oriented modular monolith; realtime/worker deployment boundaries accommodate different runtime characteristics.

Clients depend on generated OpenAPI types and platform-neutral domain helpers. Design tokens cross platforms; platform UI does not have to.
PostgreSQL/PostGIS owns durable domain state. Redis owns ephemeral presence with TTLs. Signed S3 uploads avoid proxying media through Java.

Current catalog remains curated seed data, not live traffic. Opt-in authenticated journeys and private route planning are implemented; route planning can use an explicit one-time location reading. No public social presence or operational rooms are enabled. See BUILD_STATUS.md for verified implementation rather than treating planned architecture as live functionality.

See ADRs and BUILD_PLAN for exact implementation boundaries.

Next product slice: ADR 0022 and docs/features/live/ROUTIQO_LIVE_SPEC.md define a journey-scoped LIVE list with deterministic structured evidence and bounded HTTP refresh. Core/PostgreSQL/Redis boundaries remain; no new infrastructure or operational Live capability is introduced by the plan.
