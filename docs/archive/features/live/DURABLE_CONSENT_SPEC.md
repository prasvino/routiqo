> **Archived 2026-09-25.** Per-journey consent and private LIVE contribution flows, replaced by one Spot-passage opt-in, simple Ghost Mode and public Spot signals. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Durable journey consent

Status: implemented and tested as internal persistence. No HTTP, presence lease
issuance, cache publication or LIVE UI is enabled by this work. ADR 0026 records
the accepted behavior and limits.

Implement a privacy-owned PostgreSQL participant and application service using
ADR 0025 account -> journey -> consent locking. The service enters
JourneyWriteAuthority; the participant runs synchronously in that existing
transaction, never starts another authority transaction, and rejects calls outside
an active transaction. Only privacy infrastructure accesses consent rows.

Store at most one consent row per account: actor, bound journey, generation,
sharing and journey-active flag. Use account PK and an owned-journey composite FK
with cascade deletion, nonnegative generation and sharing-implies-active checks.
No coordinates, timestamps, mutation history or per-journey consent archive.
Retain the minimal latest revocation state until replaced by another active
journey or account deletion; never purge a live journey's generation independently.
Migration V8 must be tested solely in disposable PostgreSQL, not user volumes.

Reads require current owned Journey authority. Missing/different-journey rows
return opt-out generation-zero state for the requested journey without replacing
the stored row. Completed journeys always return inactive opt-out state. Reading
does not opt in or replace the latest row. A new active journey may replace the
previous row only on explicit change; generations are scoped to actor AND journey
as in PresenceConsent. Existing journey IDs can never restart after completion.

Changes require expected generation. Compare it before applying the desired state,
including same-value writes; stale values conflict with generic cause-free errors.
Use existing PresenceConsent transitions: default off, actual changes increment,
same-state updates preserve generation, completed journeys cannot enable sharing.
A successful change retried with its old generation requires a read/reconciliation;
do not claim a durable command receipt for this API. Reject generation overflow
without partial writes or private diagnostics. No client can supply stored state.

Legacy `change` writes preserve generation by domain contract. In particular, writing
off while already off at generation zero does not fence a delayed enable that also
expects zero. An actual on-to-off Ghost transition advances generation and rejects
the stale enable. The internal explicit-intent path in `CONSENT_INTENT_SPEC.md`
now fences every accepted disable. No public mutation API is enabled.

Completion must revoke the current bound consent row atomically with the journey
update. Add an intentional journey-owned completion participant interface; privacy
provides its implementation. Invoke participants under existing account/journey
locks before commit; failures roll back both updates. No consent row means no-op;
a row bound to a different journey is unchanged. Terminal retries do not increment
again. At maximum generation terminal completion saturates rather than overflowing,
as superseded by ADR 0029. Avoid dependency cycles: privacy JDBC participant has no Journey service
dependency; the application consent service depends on JourneyWriteAuthority and
the privacy participant. Production wiring must include the participant, with a
configured-service test proving real completion revokes consent.

Tests: migrations/DB ownership constraints, opt-out defaults and no read mutation,
CAS/reordered enable after Ghost, same-state updates, overflow rollback, one-row
replacement across journeys, completed-journey reads never replace newer state,
independent adapters racing same expected generation, consent change versus actual
configured completion, completion participant rollback, account deletion cascade,
transaction-required participant, redacted errors and existing journey/auth tests.
Use lock observations/latches for races and no process-local authority maps.

Future signal writes must call the participant inside the same journey transaction
as current context and receipt writes. Persisted Ghost state alone does not revoke
previously delivered caches or make public aggregation safe; those gates remain.
