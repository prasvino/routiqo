# ADR 0030: Default-off browser consent API

Status: accepted and implemented behind an inactive server flag. No consent UI,
signal ingestion, presence lease or public projection is enabled.

## Decision

Expose the authenticated owner's private journey consent state at GET and POST
`/api/v1/journeys/{id}/consent` only when
`ROUTIQO_LIVE_CONSENT_API_ENABLED=true`. Both controller registration and the
web-auth security allowlist depend on that flag. An absent or false flag retains
the default deny behavior.

The boundary reuses browser session cookies, the exact account partition header,
same-origin and CSRF checks, bounded JSON input and database-backed peer limits.
It adds durable per-account minute budgets of 60 reads, 10 enables and 20 disables.
The enable and disable budgets are separate so repeated enables cannot exhaust the
revocation budget. Rate and persistence failures fail closed with bodyless 503;
exhaustion returns 429 and a fixed 60-second retry hint. Responses and failures are
private and `no-store`.

POST accepts only an exact explicit consent intent and calls ADR 0029's
`submitIntent`; it never calls the legacy `change` operation. Consent generations
are canonical decimal strings from zero through `Long.MAX_VALUE` on both request
and response, preserving values outside JavaScript's safe-integer range. Clients
must reconcile the committed response and must not refresh and automatically retry
a conflicted enable.

The same-origin web proxy permits only GET and POST at the exact consent path and
preserves the existing cookie, account, origin, CSRF, body and response bounds.
OpenAPI is the transport source of truth. The browser flag remains false in sample
configuration until real OAuth, UI reconciliation and downstream revocation
delivery are reviewed together.

## Limits

This API changes only private durable consent. It does not issue signal grants,
publish presence, invalidate future caches or delivery channels, prove location,
or make offline enable safe. There is no UI, offline queue, background retry,
timer, provider anchor registration or public Live output in this decision.
