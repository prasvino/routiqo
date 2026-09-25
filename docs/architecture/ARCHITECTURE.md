# Routiqo architecture
One repository contains mobile, consumer web, moderation web, a Java core API, realtime gateway, and workers. The core is a domain-oriented modular monolith; realtime/worker deployment boundaries accommodate different runtime characteristics.

Clients depend on generated OpenAPI types and platform-neutral domain helpers. Design tokens cross platforms; platform UI does not have to.
PostgreSQL/PostGIS owns durable domain state. Redis owns ephemeral room/chat state, caches and rate-limit counters with explicit TTLs; it holds no presence. Signed S3 uploads (voice notes first) avoid proxying media through Java.

Current catalog remains curated seed data, not live traffic. Opt-in authenticated journeys and private route planning are implemented; route planning can use an explicit one-time location reading. No public social presence or operational rooms are enabled; the pilot model collects no continuous location on the server. See BUILD_STATUS.md for verified implementation rather than treating planned architecture as live functionality.

See ADRs and BUILD_PLAN for exact implementation boundaries.

Product direction (2026-09-25, [`../PRODUCT.md`](../PRODUCT.md)): Journey, Spots and Ask Ahead on active input only. Planned core-api domains `spot`, `askahead`, `room` and `routeguide` reuse existing journey, outbox, signal storage, expiry maintenance, anchor catalog and moderation infrastructure. Spot passage is detected on the device, opt-in, and deleted within 24 hours. The earlier LIVE list, cohort publication and presence designs are archived and their code stays default-off. Stack, modules and working rules: [`ENGINEERING_CONTEXT.md`](ENGINEERING_CONTEXT.md).
