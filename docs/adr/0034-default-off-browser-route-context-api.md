# ADR 0034: Default-off browser route-context API

Status: accepted and implemented as a private, separately disabled transport. No
signal ingestion, public Live projection or UI is enabled.

## Decision

Expose owner-only `GET` and `POST /api/v1/journeys/{id}/route-context` only when
`web-auth`, `routing` and `persistence` profiles and the independent
`ROUTIQO_LIVE_ROUTE_BINDING_API_ENABLED=true` flag are all present. Security
allowlisting is conditional on the same flag. Enabling the route requires the real
configured catalog, resolver and `RouteBindingService`; startup fails when that
composition is incomplete.

GET uses a read-only application interface and a separate database account budget
of 60 reads per minute. It returns the current owned private context or null and
does not renew or mutate it. API packages are forbidden from depending on the raw
context replacement service/participant or low-level signal storage.

POST strictly accepts only route mode, two endpoint coordinates, alternative
index and a null or exact current-context UUID. It invokes the existing two-
transaction binding service once, retaining its 10-per-minute database account
budget, provider separation and post-provider authority checks. The response is a
minimal private outcome: context identity, decimal-string revision, sorted opaque
anchor IDs and server times. Geometry, endpoints, actor, catalog and journey
metadata remain absent. Empty outcomes preserve the previous context, and GET is
the only recovery operation after a lost response.

The exact same-origin proxy allowlists only GET/POST on this leaf path. GET retains
the normal eight-second deadline; POST has a bounded 25-second deadline for the
routing call plus two bounded database transactions, a 64 KiB response cap, manual
redirect handling and no retries.

## Limits

This private context is sensitive route intent and is not physical-presence proof,
a signal grant or publication permission. The API remains disabled in examples.
Real regional provider/catalog operations, OAuth rollout, moderation, revocation
delivery and cohort-safe public Live output remain release gates.
