# ADR 0061: Native private route preparation

Status: accepted for implementation; public activation is not authorized.

> **Status update (2026-09-25):** Partly superseded by the direction brief. Work is paused. Consent epochs and the explicit "check / prepare private route" ceremony are retired; the native route-context transport may be reused so Android shows Spots ahead automatically on journey start. See [PRODUCT.md](../PRODUCT.md).

Expose existing owned route-context read/bind through a separately gated native
leaf and strict Android transport. Reuse the two-transaction binder, shared durable
quotas, fresh resolver output, exact context expectation and consent rechecks. Widen
resolver composition for native-auth without enabling its flag or changing catalog
validation. No new schema, stored endpoints, provider or public projection.

Connect explicit check/prepare controls to synchronous consent and selected-route
epochs. UI confirmation is a short-lived request acknowledgement, never independent
authorization. Reads recover an expectation without associating it with the current
displayed route. No automatic/offline bind replay; failed or cancelled attempts need
explicit Check. Preserve ordinary offline directions independently of LIVE consent.

Contract and acceptance criteria: `../features/live/NATIVE_ROUTE_PREPARATION_SPEC.md`.
