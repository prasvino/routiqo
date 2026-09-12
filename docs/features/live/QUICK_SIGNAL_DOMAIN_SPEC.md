# Quick Signal domain slice (L0.1)

Status: internal model implemented and tested; no application wiring or public
Live behavior. Verification is recorded in BUILD_STATUS.md.

Scope: internal immutable structured Route Update evidence, not an exposed Live
feature. Canonical product direction is ROUTIQO_LIVE_SPEC.md. No controllers,
database migration, signal storage, admission or public aggregate in this slice.

## Acceptance

QuickSignalValue owns its category. Fixed enums cover the five first-release
categories and values; do not accept arbitrary strings or independent mismatched
category/value pairs. Serial transport names will be defined in OpenAPI later.

QuickSignal contains a signal UUID (future idempotency identity), internal actor,
journey and server-defined anchor UUIDs, a value, consent generation and server
receipt/expiry instants. No coordinates, free text, names or counters. Non-null, non-nil IDs
and enums, nonnegative generation and a positive lifetime of at most 15 minutes
are structural bounds. The caller supplies the actual expiry; there is no default
runtime retention, automatic timestamping or automatic renewal. A stored evidence
lifetime is distinct from database retention and from a presence lease.

Freshness is half-open: receivedAt <= now < expiresAt. Future evidence is not
current. Exact retries must match actor, journey, anchor, value and consent
generation; the caller separately resolves the signal ID under authenticated actor
scope. Matching does not authorize reuse or create a new record, extend expiry,
change the receipt, permit publication or resurrect withdrawn/expired evidence.
Missing or mismatched replay context fails closed.

The contribution slot is actor + anchor + category, across journey changes. Two
journeys must not turn one actor into independent corroboration. This equality
helper does not implement storage uniqueness, supersession or multi-replica CAS.
Signal construction does not prove membership, location, consent or authenticity.
All such authorities are checked later through application interfaces.

Generic cause-free validation and redacted toString must not reveal IDs, report
values, anchor, generation or observation timestamps. Records are internal and must
never be directly serialized as public DTOs. Before public endpoints, implement
server-issued admission, current consent checks, idempotent receipt storage,
transactional contribution replacement, distributed quotas and the cohort ADR.

Tests cover every category, invalid/null fields, negative generation, zero/negative/
overlong lifetime, exact receipt/expiry/future boundaries, all replay mismatches,
same slot across journeys, distinct actor/anchor/category and redacted diagnostics.
No aggregation/confidence approval can be inferred from these unit tests.
