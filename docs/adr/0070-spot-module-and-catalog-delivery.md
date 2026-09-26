# ADR 0070: Spot module and catalog delivery

Date: 2026-09-26
Status: accepted for the Diwali 2026 dry run; implemented default-off
(`ROUTIQO_SPOTS_API_ENABLED`) with [SPOTS_SPEC.md](../features/spots/SPOTS_SPEC.md)

## Context

The Android Spots-ahead panel needs the curated Spot catalog and an activity read
from the server. [ENGINEERING_CONTEXT.md](../architecture/ENGINEERING_CONTEXT.md) §5
left open whether `spot` is a rename of `routeupdate` or a new module.
`routeupdate` holds the archived private-LIVE route binding, the route-anchor
catalog and private Quick Signal storage. That code stays default-off
([PRODUCT.md](../PRODUCT.md)). Its categories are an upper-case enum tied to the
private signal model, and its flags and profiles are built around per-journey
consent.

## Decision

1. **New module `com.routiqo.core.spot`** with the usual api, application, domain
   and infrastructure layers. It is not a rename: `routeupdate` keeps its
   identifiers and stays off.
   - `spot` depends only on intentional interfaces: `identity.application.AuthRateGate`,
     `routing.domain.RoutingRegion`/`Coordinate`, `security.FeatureFlags`/
     `ConditionalOnExactlyTrue`, `security.NativeAuthGuard`, and a new read-only
     `journey.application.ActiveJourneyReader`.
   - An architecture test forbids `spot` from depending on `routeupdate`,
     `publiclive`, `privacy`, `verification`, or any module's infrastructure.
   - Posts, signals, moderation and alerts for Spots land in this module. Where they
     reuse `routeupdate` mechanics (idempotent commands, abuse budgets, expiry), the
     mechanics move behind interfaces instead of `spot` calling archived services.
2. **The catalog is an immutable startup file** (`routiqo-spots/1`, at
   `ROUTIQO_SPOT_CATALOG_PATH`).
   - The loader is strict and bounded, modelled on ADR 0031's anchor loader:
     256 KiB and 512 Spots, exact keys, a fixed district list, and coordinates
     inside the routing region.
   - There is no hot reload or merge. A new version is a new file and a restart.
   - With the flag on, a missing or invalid catalog stops startup. With the flag off,
     the file is never read.
3. **Delivery.**
   - `GET /api/v1/native/spots/catalog` serves a public projection without
     provenance. It is serialized once at startup and capped at 256 KiB.
   - The ETag is the quoted catalog version, and a matching `If-None-Match` returns 304.
   - `POST /api/v1/native/spots/activity` takes 1–20 sorted Spot IDs in the body and
     requires an active journey owned by the account. It answers per known Spot;
     until posts exist, every Spot is `quiet`.
   - The rate gates are per account: 10 catalog reads and 20 activity reads per minute.
     Both use the JDBC gate, so they hold across replicas.
4. **Requested Spot IDs are private** (ADR 0067). They are parsed by hand, not by
   Spring's message converters, so framework DEBUG logging cannot record them. They
   are never stored, logged or used as a rate key. A log-capture test enforces this.
5. **Exact flag.** `ROUTIQO_SPOTS_API_ENABLED` enables the capability only when it is
   exactly `true` (`FeatureFlags`). The native guard admits the two paths only then.
6. **Native transport.**
   - `safe-transport.ts` and `RoutiqoSafeHttpModule.kt` allow exactly these two paths
     and methods.
   - They send `If-None-Match` only on the catalog GET, and accept a 304 only for that
     request. Every other 3xx is still rejected as a redirect.

## Consequences

- **Multi-replica:** every replica must be deployed with the same catalog file.
  During a rolling deploy, replicas can briefly serve two versions. That is harmless:
  the ETag differs, so the app replaces its copy, and activity responses carry
  `catalogVersion`.
- **Server-side exposure:** the server learns which of the next 20 Spots a
  traveller asks about, but does not keep it. Corridor sections (SPOTS_SPEC,
  *Planned for Pongal*) reduce this to a stretch of road before the public launch.
- **Adding a district** is a code change, so the official-alert reader and its
  review keep pace with the catalog.
- The display-label rule is duplicated from `RouteAnchor` rather than shared, to
  keep `spot` independent of archived code.
- The contract declares `alertIds` and `alerts` with `maxItems: 0`. Official alerts
  and posts relax the contract additively when they are built.
