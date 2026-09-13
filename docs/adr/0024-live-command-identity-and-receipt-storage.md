# ADR 0024: Admission-bound command identity and bounded receipt storage

Status: accepted and implemented for internal grants, receipts, contribution slots,
budgets and cleanup (SIGNAL_COMMAND_SPEC.md, SIGNAL_STORAGE_SPEC.md and ADR 0028).
Public signal ingestion and moment publication remain disabled.

## Problem

QuickSignalReceipt models retry comparison and terminal state, but an in-memory
record does not stop concurrent duplicate writes. Deleting a receipt must not let
an old command be accepted as new. A client-chosen ID attached to renewed admission
would make that lifecycle ambiguous. Route context and consent must also remain
current at the moment evidence is accepted, not only when a request begins.

## Command identity

The server issues an opaque command UUID with one specific short-lived admission;
the command ID is the signal ID. Clients cannot register their own command IDs or
request reissuance of a chosen ID. Generate unpredictable fresh IDs, enforce unique
insertion and never intentionally recycle IDs. The ID is not authentication:
resolve it only in the authenticated actor's scope and recheck journey/admission.

Store a grant rather than introducing a signed bearer token or another JWT flow.
The grant binds actor, journey, context/revision, anchor, consent generation,
permitted categories, issuedAt and expiresAt. Its validity is at most 90 seconds
and it must expire no later than the associated admission. First submission fixes
the exact value/fingerprint in the receipt. Every retry uses that same command ID
and payload. Asking for a new grant is a new deliberate contribution subject to
quotas, not an automatic retry action.

If a receipt exists, return its bounded prior command outcome only after current
authentication/ownership checks; do not refresh its timestamps or imply the report
is still published. Changed fingerprints conflict. "Exists" means logically
retained at server time, not an expired row awaiting physical cleanup. After
retainUntil do not return the stored outcome. For a missing or logically expired
receipt, a current unused grant and current authoritative admission are mandatory.
These are necessary conditions, not sufficient permission: if an expired receipt
row is still present, deny new acceptance because it proves prior acceptance and
contradicts an UNUSED grant. Logical expiry forbids returning an old outcome; it
does not require ignoring evidence of an inconsistent consumed state. After
physical purge, the consumed or missing grant must independently prevent replay.
An expired/missing grant cannot be recreated from the request. Thus a purged old receipt cannot turn
an old envelope into a fresh write. No fresh grant is minted implicitly by replay.

## Transaction and domain boundaries

PostgreSQL owns grants, receipts and latest contribution slots. Define application
interfaces for current journey, consent and route context; never read another
domain's repository directly. Acceptance must participate in one transactional
authority boundary, or use equivalent checked versions that fail atomically.
The current pure SignalAdmissionPolicy alone is not this boundary.

Within that boundary: authenticate actor, verify ownership and resolve an existing
receipt without requiring its old grant to remain live. Only for new acceptance,
lock/recheck current active authorities and the unused grant, enforce quotas, bind the exact fingerprint,
supersede the prior actor/anchor/category contribution and insert the new receipt.
Mark the grant consumed atomically with acceptance. Receipt purge must never leave
or reconstruct a live unused grant: preserve its consumed status until expiry,
or remove the grant entirely so new acceptance fails closed. A short-retention
receipt must not reset a still-live command. Never infer unused grant state from
a missing receipt. Grant cleanup must not cascade-delete a still-retained receipt;
retained command outcomes remain independently available within their own lifetime.
The command key is actor plus server-issued command ID; the contribution-slot key
is actor plus anchor plus category, irrespective of journey. A changed context or
consent generation between check and commit must deny acceptance. Expensive provider
calls and projection work never run inside this transaction.

Specify and test one consistent lock order across acceptance, journey completion,
consent withdrawal, context replacement and deletion before wiring repositories.
Unique constraints and conditional updates must enforce the same invariants as the
pure models. Database exceptions must not expose private parameters. Distributed
quota operations must not be replaced by process-local maps or reset by changing
the route/journey/connection.

## Retention and invalidation

For the initial internal store, use explicit receipt retention no longer than 24
hours from first acceptance; preserve the original expiry on retries. Evidence
freshness is at most 15 minutes. Raw signal metadata stays private and is purged
with its receipt; withdrawal/supersession prevents further eligibility but does not
claim immediate physical erasure. No moderation hold or permanent report archive
is introduced in this slice. Public moderation workflows need their own retention
decision before being enabled.

Grant validity remains at most 90 seconds. Expired grant rows may be cleaned in
bounded batches without extending validity. A receipt is sufficient to recognize
a retained command outcome after its grant expires. Pending context registration
must retain only the current bounded anchor set, with an explicit lifetime capped
at 24 hours; expired/replaced contexts require new server identity/revision rather
than restoring old admission. Do not accumulate a route-context history.

Logical expiry/revocation is checked on every use even if cleanup is delayed.
Define cleanup batch sizes, indexes and lag monitoring in the persistence spec;
database outages can delay physical cleanup, which must never be described as
guaranteed immediate erasure. Account deletion invalidates authority first and
removes its grants, receipts, slots and contexts through owned application hooks.
Backups and any future moderation exceptions require documented deletion policy.

Consent/context revision changes immediately invalidate future eligibility. Receipt
state updates may be batched only if reads/publication also check current authority
and cannot reuse a stale projection. Durable invalidation delivery is required
before any cached/public output; Redis Pub/Sub alone is insufficient.

## Next implementation acceptance

1. Design migrations, domain-owned authority interfaces and the common transaction
   lock order; use disposable database tests, not existing user data.
2. Verify concurrent duplicate command, different payload/same command, contribution
   replacement across journeys, rollback, grant expiry and replay after purge,
   including receipt expiry/purge while its consumed grant has not yet expired.
3. Verify Ghost/route-change/completion/delete races and bounded cleanup with both
   live and expired receipts; no failed acceptance may become an accepted receipt.
4. Keep repositories internal until server route registration, authenticated grant
   issuance, quotas and moderation dependencies exist. No public endpoint is
   authorized merely by passing storage tests.

This decision does not resolve the separate cohort publication issues described
in COHORT_PUBLICATION_DESIGN.md. The first LIVE UI remains gated on those decisions
and appropriate end-to-end verification.
