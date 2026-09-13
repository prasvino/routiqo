# ADR 0031: Provider-backed route anchor resolution

Status: accepted and implemented as a default-off internal boundary. No public
registration, journey binding, signal grant or presence output is enabled.

## Decision

Route Update owns an immutable, operator-curated anchor catalog loaded from one
configured local JSON file. The loader accepts at most 256 KiB of strict UTF-8,
requires an exact versioned schema, one to 512 distinct anchors and closed Quick
Signal categories, and rejects unknown, duplicate, malformed or trailing input.
Catalogs and failures redact coordinates, identifiers and file contents. Every
configured anchor must lie within the configured routing region.

The internal resolver asks the existing region-guarded Valhalla provider for fresh
routes and accepts a selected alternative from zero through two. It rejects ambient
transactions before provider work, so no account, journey or database lock is held
across routing. It never accepts caller geometry or persists provider geometry.
Empty provider routes are distinct from a route with no eligible anchors; a missing
selected alternative is a conflict and never falls back to another route.

An anchor is relevant only when it is within 100 metres of an actual selected-route
vertex. Anchors within or exactly 1,000 metres of either requested endpoint or
either selected geometry endpoint are excluded. Distance uses spherical
great-circle calculation with mean Earth radius 6,371,008.8 metres, longitude wrap
and floating-point clamping. Segment interpolation, nearest-anchor fallback and
radius expansion are forbidden. More than 128 matches rejects the full result.

The result contains only catalog version, route/no-route state and an immutable map
of opaque anchor UUIDs to configured category sets. It contains no coordinates,
route geometry, endpoints, instructions, actor or journey data. Route relevance is
not evidence of physical presence, accessibility or permission to publish.

## Configuration and limits

Production wiring requires `web-auth` and `routing` plus
`ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true` and a local
`ROUTIQO_LIVE_ANCHOR_CATALOG_PATH`. The flag defaults false and no example catalog
is supplied. The resolver reuses the same parsed routing-region bean as guarded
Valhalla; startup performs no provider request. Missing or invalid configuration
fails closed with redacted diagnostics.

Future integration must authenticate and budget an actor, recheck owned journey,
consent and context authority after provider work, fence replacement, and recheck
catalog categories when issuing a grant. Provider quality, grade separation,
sparse geometry, endpoint inference, catalog operations, moderation, durable
binding and cohort-safe publication remain separate release gates.
