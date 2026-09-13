# Journey authority transaction boundary

Status: PostgreSQL boundary implemented; final verification is recorded in
BUILD_STATUS.md. ADR 0025 defines the accepted integration contract.
Scope: real PostgreSQL account/journey serialization, not signal receipt storage,
route registration or public Live ingestion. Durable consent now uses this
boundary under ADR 0026.

## Contract and ownership

Identity owns an AccountWriteAuthority application interface with a synchronous
generic callback, supplied an independently authenticated actor ID. Its JDBC
adapter opens a transaction, locks the enabled routiqo_account row FOR UPDATE and
only then calls the callback. Missing/disabled accounts deny with a generic
cause-free SecurityException; possession of an ID is not authentication. Reject
null/nil identities and missing callbacks. No network work belongs in callbacks.

The boundary owns the transaction and rejects entry from an existing transaction
before executing any SQL/callback. This avoids accidentally joining work that has
already acquired journey or session locks in reverse order. It uses the existing
five-second transaction timeout. Callback exceptions roll back all participating
writes. This is a database/query budget, not a mechanism to interrupt arbitrary
Java code; callbacks must be bounded synchronous database work.
Database/transaction failures become a generic cause-free availability exception;
the browser journey API maps that exception to a bodyless 503, preserving retries
instead of reporting an outage as lost authentication. Owned adapters translate
expected domain conflicts before the outer boundary sanitizes persistence errors.
Sanitize SQL exceptions inside the transaction callback as well as at the outer
boundary: the transaction framework can log the original private SQL exception
during rollback (debug) or a failed rollback (error) before the outer catch runs.
Verify that path with
an injected rollback failure and captured framework log exception; this does not
claim control over every driver log or operator logging configuration.

Journey owns JourneyWriteAuthority with a synchronous callback receiving the
current owned Journey, after account then journey FOR UPDATE locks. It supports
completed journeys for private retained-command replay; callers must separately
require ACTIVE for new evidence. Missing/wrong-owner journeys raise the existing
generic JourneyNotFound. Callbacks must not escape to another thread, start a new
transaction, retain transaction capabilities or run providers/projections.

JdbcJourneyStore implements JourneyWriteAuthority using AccountWriteAuthority;
it does not query identity tables. Its start operation enters the account boundary
before insertion. JourneyService is the complete-operation boundary: it enters
owned-journey authority, calls the repository completion participant and then all
configured completion participants before commit. Raw JdbcJourneyStore completion
requires that existing account/journey transaction and is not safe under an
arbitrary transaction or as a direct application entry point.
Existing retry, owner isolation, microsecond timestamps and one-active-journey
semantics remain. Persistence-profile wiring shares the existing datasource and
transaction manager; default preview remains unchanged. No compatibility
constructor may instantiate another domain's persistence adapter behind the
application interface.

## Lock order and subsequent participants

Existing session renewal/logout/deletion already lock account before session.
This slice aligns journey writes and future Live transactions with that account
gate. Future Live participants use account -> journey -> consent -> route context
-> command grant -> contribution slot -> receipt order, with bounded single-actor
work and slot identity actor/anchor/category across journeys. Missing-row inserts
must occur under the account gate and have unique constraints. Never independently
enter this account boundary from inside an already active callback.

This serializes writes for one actor across processes without a global lock.
Durable consent now participates in this transaction. Context, grant/receipt
migrations, cleanup and publication invalidation remain subsequent work. They must participate in this
same transaction before SignalCommandPolicy can authorize actual storage. No
public Ghost Mode, presence lease or cache-revocation claim is made by this slice.

## Acceptance

Use only disposable Testcontainers PostgreSQL, including independent adapter and
transaction manager instances sharing the same database. Verify:

- Missing/disabled account and wrong-owner journey never invoke callbacks.
- Callback sees current owned state; exception rolls back writes and releases locks.
- Ambient transaction entry is rejected without callback work.
- An owned-journey callback holds completion behind the account gate; completion
  committed first is seen as COMPLETED by the later callback.
- Account deletion waits for the boundary; deletion winning first denies later
  work. No orphan or post-deletion write survives.
- Independent actors do not wait on each other's authority gate.
- An independently held account lock exceeds the five-second query budget; the
  waiting authority returns redacted unavailability without invoking its callback.
- Existing start/completion retry/concurrency and authenticated HTTP tests pass.
- SQL/transaction failures expose no private cause or parameters; HTTP availability
  failures remain distinct from authentication failures.

Concurrency tests use bounded latches/futures, prove blocking through PostgreSQL
lock state where practical, release in finally, and avoid timing-only assertions.
No migration or user database mutation is required for this prerequisite.
