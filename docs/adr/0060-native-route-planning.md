# ADR 0060: Native explicit route planning

Status: implemented and locally verified; no provider deployment or production activation.

> **Status update (2026-09-25):** Accepted — role note by the direction brief. It aligns with Android as the primary pilot client and remains the basis for the Android Journey. References to LIVE consent are historical. See [PRODUCT.md](../PRODUCT.md).

Expose existing normalized routing/place services through separately gated native
POST leaves using native bearer/account isolation and shared durable browser/native
quotas. Use the bounded Android bridge and memory-only explicit search/calculation
with manual instruction review. Keep travel utility independent of LIVE consent.

Loaded directions remain useful during connection loss; a new calculation remains
an explicit operation. Inputs/results never enter journey outbox or durable stores.
No GPS navigation, download lifecycle, automatic retry, route binding, public
contribution or map-provider migration is included. Existing owned provider and
regional coverage decisions remain binding. Contract and acceptance criteria:
`../features/journey/NATIVE_ROUTE_PLANNING_SPEC.md`.
