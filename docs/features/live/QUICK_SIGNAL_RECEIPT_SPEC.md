# Internal Quick Signal receipt lifecycle (L0.3a)

Status: internal primitive implemented and tested; no durable receipt store or
Live submission service. See BUILD_STATUS.md for verification.

ADR 0024 now defines the next command/grant and persistence direction, including
atomic grant consumption and replay denial after receipt purge. It is a reviewed
design, not implemented durable idempotency.

Scope: pure receipt state and evidence-to-context linkage, not database idempotency
or a submission service. The public Live release and cohort design remain gated.

QuickSignalReceipt binds immutable QuickSignal evidence to non-nil contextId and
nonnegative routeRevision. It retains the original private signal fields for exact
replay comparison. It is not a DTO or proof that the caller passed admission.
Acceptance must later bind the admission check and receipt write atomically.

The caller supplies retainUntil, at least signal.expiresAt and no more than 24 hours
after signal.receivedAt. This is a structural ceiling for an internal primitive,
not an enabled production retention policy. Use half-open receipt lifetime; null
evaluation time is ineligible. Evidence is temporally usable only while the receipt
is ACTIVE, retained and the signal's original evidence lifetime has not expired.
This test does not replace current consent, ownership, block or moderation checks.

States: ACTIVE, WITHDRAWN, SUPERSEDED. Withdrawal and supersession are terminal;
repeated or competing terminal operations preserve the first terminal state.
Never refresh signal receipt/expiry or receipt retention on a transition or retry.
An exact replay may match even after withdrawal/expiry: callers must return the
previous command outcome, not reinterpret a match as permission to recreate it.
Match actor/journey/anchor/value/consent generation AND context ID/revision. Resolve
the command ID in authenticated actor scope separately. Same signal ID with a
different fingerprint must conflict in the future store.

No physical erasure is claimed by withdraw: the receipt keeps private signal
metadata until purge. Final storage/retention design must bound cleanup, deletion,
backup handling and permitted moderation holds. After receipt purge, old commands
must remain unable to replay via expired server admissions; do not mint new
admission automatically for an old command. The command envelope/receipt store
must settle this gate before any endpoint.

Tests: exact signal and receipt expiry, future/null evaluation, overlong/short or
invalid retention, non-nil context and nonnegative revision, extreme instants,
immutable first-terminal-state behavior, no timestamp renewal, changed replay
fields/context/revision and redacted diagnostics. No database, public endpoint,
cohort projection or aggregate confidence is implemented by this slice.
