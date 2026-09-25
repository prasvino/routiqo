# ADR 0035: Default-off browser Quick Signal API

Status: accepted and implemented as a private, separately disabled transport. No
public Live projection, UI, moderation workflow or automatic retry is enabled.

> **Status update (2026-09-25):** Partly superseded by the direction brief. Idempotent issue/accept/withdraw transport is kept for Spot signals (withdraw becomes "delete my post"). The private-only, consent-gated framing is retired; signals are public under per-room aliases. See [PRODUCT.md](../PRODUCT.md).

## Decision

Expose only owner-scoped POST command issue, acceptance and withdrawal leaves
when `web-auth`, `routing` and `persistence` profiles and the independent
`ROUTIQO_LIVE_SIGNAL_API_ENABLED=true` flag are all present. The same condition
controls the security allowlist and requires the real configured
`CatalogSignalService`; incomplete composition fails startup.

Requests retain the existing session, exact account, same-origin CSRF, strict
20 KiB JSON and durable peer controls. Separate database account request budgets
permit 30 issue, 60 acceptance and 30 withdrawal attempts per minute. Existing
transactional storage budgets remain 10 successful grants and 5 new acceptances
per account and minute. Retained replay and withdrawal do not spend a new storage
acceptance budget.

Issue accepts one opaque anchor ID and derives categories from current bound
catalog authority. Acceptance supplies only the exact server grant fingerprint
using canonical UUIDs, a closed signal enum and decimal-string generations and
revision. It uses fixed 15-minute evidence and 24-hour receipt durations within
the existing domain bounds. These values apply only to this disabled private
transport; they do not approve public retention or publication. Withdrawal is a
terminal private receipt transition. Minimal no-store responses omit actor,
journey, catalog, geometry and signal value metadata.

The same-origin proxy allowlists exactly the three POST leaves with its standard
eight-second deadline, 64 KiB response cap, manual redirects and no retry. API
code depends only on the catalog-aware facade and cannot reach raw signal storage
or context replacement.

## Limits

An accepted private receipt is not physical-presence proof or permission to
publish. Real catalog/provider operations, hourly and per-category abuse limits,
an operated purge job, moderation and revocation delivery, cohort-safe projection
and release UI remain gates. A lost issue response is not recoverable and creates
a new grant only through a deliberate new request; exact retained acceptance
replay never renews evidence or retention.
