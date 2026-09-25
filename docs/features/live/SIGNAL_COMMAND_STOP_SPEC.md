# Internal stop for an issued private signal command

Status: implemented and independently reviewed, 2026-09-19. Guarded browser exposure is ADR 0047; no UI or public projection.

> **Direction brief (2026-09-25):** Kept. Stop/withdraw becomes the user-facing "delete my post" for signals, posts and voice notes; the uncertain-write fence still applies. Do not claim remote erasure of copies already delivered to disconnected devices. See [PRODUCT.md](../../PRODUCT.md).

## Recovery gap

Existing withdrawal requires a retained receipt. During an uncertain acceptance,
a withdrawal can find no receipt and fail before the delayed acceptance arrives.
That failure is not a cancellation fence. A future contribution UI must not claim
that aborting transport or attempting receipt withdrawal stopped a pending write.

Add a distinct internal stop operation for a known issued command; preserve all
existing issue/accept/replay/withdraw contracts. This is preparation for safe UI
recovery, not public revocation delivery or permission to retain new evidence.

## Authority and state transition

`CatalogSignalService.stopCommand(actor, journey, command)` delegates to one
existing owned-journey callback in SignalStorageService. Authenticate and scope
the actor outside this internal boundary as today; reject null/nil identifiers.
Require current owned journey, including completed journeys. Do not require
sharing, current route/catalog, active journey or an unsuspended contributor to
stop the owner's command. Do not create new grants or receipts.

Read the owner receipt first under authority. If present, require matching owner,
journey and command and the existing nonfuture/logically retained time rules.
Apply the existing terminal withdrawal transition, preserving a prior withdrawn
or superseded result and all original timestamps. Expired receipt gives generic
denial and is not returned or renewed. Do not enter the public withdraw method
from inside this callback; share a narrow owned helper if needed.

If no receipt exists, read the owner grant and require matching owner/journey/
command. Mark UNUSED as CONSUMED using the existing store operation; skip writes
for already CONSUMED. An existing expired grant may still be consumed: stopping
authority must not require fresh contribution eligibility. Missing/foreign grant
is generic denial. Do not create a tombstone for arbitrary unknown command IDs.

Return a redacted immutable internal result: commandId and an optional retained
terminal receipt. Validate that any receipt has matching commandId and is not
ACTIVE. A result without a receipt means further new acceptance of this issued
command is stopped; it must NOT claim the command was never accepted historically.
Consumed-without-receipt can also follow old receipt cleanup. A lost stop response
can be retried with the exact command; normal cleanup may later make it unknown.

Account/journey serialization gives two outcomes: if stop wins first, a queued
new acceptance sees a consumed/missing grant and denies; if acceptance wins first,
stop withdraws its retained receipt. Existing retained replay returns the terminal
receipt and cannot reactivate evidence. No grant/acceptance/rolling budget refund,
new debit, lifetime renewal, geometry, provider call, cache or schema is added.

## Verification before completion

- Real PostgreSQL unused-grant stop, exact repeated stop, zero receipt creation,
  preserved budget, unchanged grant timestamps and denied later acceptance.
- Acceptance first then stop: terminal retained receipt, exact replay stays
  terminal; prior superseded outcome preserved. Legacy withdrawal unchanged.
- Both account-lock race orderings: stop-before-accept and accept-before-stop.
- Stop after Ghost, completion, context replacement and suspension; foreign actor,
  wrong journey, missing command and expired receipt fail without mutation.
- Inject failure after real grant consumption / receipt state update and prove
  rollback. Cleanup must never make old commands newly acceptable.
- Result validation/redaction and one authority callback; no nested transactions.
- Targeted and full Java checks plus independent review.

The domain slice is exposed by ADR 0047 with a separate request quota, strict DTO and same-origin security. No UI is included; uncertainty recovery and truthful expiry/cleanup messages remain required.


## Verified evidence

76 focused backend tests passed, including both account-lock orderings, real
post-update rollback/redaction, expiry cleanup, forged storage snapshots and
partial API flags. Full core check/bootJar passed: 431 tests across 61 suites,
zero failures/errors/skips. Independent source/test review approved. No schema
migration, production activation, real provider or device verification is claimed.
