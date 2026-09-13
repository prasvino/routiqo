# ADR 0027: Durable Live route context

Status: accepted and implemented for internal persistence. No public route
registration, admission issuer, signal storage or Live projection is enabled.

## Decision

Store one latest Live route context per account in PostgreSQL. Route Update owns
the row and its application boundary. The row binds an unpredictable server-issued
context UUID and nonnegative revision to one owned journey, 1–128 distinct non-nil
anchor UUIDs, and a positive explicit lifetime no longer than 24 hours. It stores
no geometry, endpoints, context history or provider response. Account and owned-
journey foreign keys enforce ownership and cascade deletion.

Requested lifetime bounds are checked before normalization. Computed expiry is
floored to PostgreSQL microsecond precision and must remain later than issuedAt,
so returned and persisted envelopes have identical half-open boundaries.

`LiveRouteContextService` enters `JourneyWriteAuthority`. Its JDBC participant
locks the latest context row before sampling its injected server clock, so time
spent waiting cannot preserve stale eligibility or predate a replacement. Reads
are half-open and return empty for missing, future, expired, differently bound or
completed contexts without writing or extending expiry.

Replacement is explicit. A current same-journey row requires its exact expected
context UUID; absent, expired or differently bound rows require no expected UUID.
A future row or stale expectation conflicts. Every success mints a fresh UUID.
Same-journey replacements increment revision, including after logical expiry;
physically absent or newly bound journey rows begin at zero. Overflow and invalid
lifetime or anchors fail without mutation. Context existence is private route
intent, not authentication, physical-presence evidence or publication permission.

## Transaction and completion order

The normal lock order is account, journey, consent, then context. Journey
completion participant priorities are explicit: consent runs before context.
The context participant deletes only the row bound to the completed journey;
participant failure rolls the journey, consent and context changes back together.
Terminal completion retries do not invoke participants. Application callbacks are
synchronous on the shared datasource, transaction and thread and do no provider
work or nested authority entry.

## Expiry maintenance

A callable internal operation physically deletes 1–100 expired rows in its own
five-second transaction. It selects the expiry index with `FOR UPDATE SKIP LOCKED`
and deletes only the exact selected actor, context identity and expiry. Logical
expiry remains authoritative when cleanup is delayed.

Maintenance is a documented leaf-only lock-order exception: it locks context rows
and never acquires account, journey, consent or later signal locks. This prevents a
cleanup path from reversing the application write order. It rejects ambient
transactions and sanitizes database/transaction failures before rollback logging.
No scheduler, cascading receipt/grant cleanup or claim of immediate erasure exists
in this phase.

## Consequences and remaining gates

Multi-replica replacements serialize through PostgreSQL, and completion/account
deletion cannot leave a current matching context. The current row is retained only
until replacement, completion, account deletion or eventual expiry cleanup.

Anchor validation against a trusted route provider and region, admission issuance,
durable commands/grants/receipts/slots, quotas, Ghost/cache invalidation, cohort
suppression and public output remain separate gates. Current consent must still be
checked atomically for any future signal acceptance. ADR 0026's unchanged opt-out
ordering limitation also remains; route-context persistence does not solve it.
