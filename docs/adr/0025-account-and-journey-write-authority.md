# ADR 0025: Account and journey write authority

Status: accepted and implemented for account/journey authority. Public Live storage
remains disabled; verification is recorded in BUILD_STATUS.md.

## Decision

Use the existing PostgreSQL account row as the first serialization gate for an
actor's journey and future Live writes. Identity exposes AccountWriteAuthority
through its application boundary; Journey exposes JourneyWriteAuthority for an
owned, locked current journey. Persistence adapters access only their own domain
tables. Callbacks execute synchronously in the same local JDBC transaction.

AccountWriteAuthority owns the outer transaction and rejects an ambient active
transaction rather than joining potentially reversed lock acquisition. Its
callback follows a checked enabled-account FOR UPDATE read. JourneyWriteAuthority
adds an owned journey FOR UPDATE read inside that callback. The existing journey
start and completion paths use the same gate, preserving lifecycle/idempotency.
Session renewal, logout and account deletion already take account before session
locks, so no session-first path is introduced by this change.

Within future Live writes, lock account, journey, consent, context, command grant,
contribution slot and receipt in that order. Account serialization arbitrates
creation of missing actor-owned rows; constraints remain mandatory. Completion,
consent withdrawal and context replacement must use this entry boundary and may
not enter a second account transaction from its callback. Signal transactions
must recheck server time after waiting for locks. Five-second JDBC transaction
timeouts bound query work, not arbitrary callback code or external operations.

## Why

The current pure admission/command policy cannot stop completion or account
deletion from racing evidence acceptance. A separate signal repository with
process-local locks would fail across replicas. Locking a journey first also
conflicts with account deletion cascades. One account gate provides a bounded,
auditable lock order using durable rows that already exist.

## Consequences and limits

One actor's writes serialize; unrelated actors retain concurrency. Do not call
providers, await asynchronous work, emit projections or publish messages inside
the boundary. Passing an actor UUID is not authentication; HTTP/session checks
remain mandatory, and the account lock rechecks account availability at write
time. Completed journeys may be returned for retained private command outcomes;
new signal acceptance still requires ACTIVE and current consent/context.
Persistence/transaction failures at the boundary are redacted availability errors;
browser journey requests receive a bodyless 503 rather than an authentication
failure. Domain-owned adapters must translate known conflicts before that boundary.

The callback API is trusted internal code, not a sandbox. It must use the shared
datasource/transaction manager and same thread. No runtime enforcement can make
arbitrary independently configured adapters participate atomically. Wiring,
architecture review and disposable database tests must verify the composition.

This step needs no migration or new public endpoint. The contract documents
retryable 503 responses for existing journey writes. It does not yet
persist consent, contexts, grants, slots or receipts. Follow ADR 0024 for their
retention and replay rules and JOURNEY_AUTHORITY_TRANSACTION_SPEC.md for tests.
Do not enable signal storage until all mutable authorities and cleanup participate
in the reviewed boundary and the remaining race tests pass.
