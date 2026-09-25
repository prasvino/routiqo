# Private owner route-area choice snapshot

Status: implemented, tested and independently reviewed, 2026-09-19. Internal read-only application
prerequisite only. ADR0045 adds gated composition, owner HTTP and browser clients;
the reader itself performs no grant mutation and no contribution UI is mounted.

> **Direction brief (2026-09-25):** Kept as infrastructure for the Spot signal picker, but the "private" framing and the on-consent requirement are retired: consent coupling is replaced by one Spot-passage opt-in plus Ghost Mode, and posting is an explicit act. Restriction and context checks still apply. See [PRODUCT.md](../../PRODUCT.md).

## Authority

Route Update owns a small choice reader using the existing JourneyWriteAuthority,
PresenceConsentParticipant, LiveRouteContextParticipant, ContributionRestrictionReader,
immutable RouteAnchorCatalog and Clock. Enter exactly one owned-journey authority
callback, then read consent, context and restriction in the existing signal order.
Require an active journey and matching actor/journey identities throughout; current
on-consent; a non-suspended matching restriction; and a present context with non-empty
catalog provenance equal to the configured version. Sample time after all participant
reads and require issuedAt <= now < expiresAt. Missing/expired/foreign/inactive/
off/mismatched metadata yields one generic cause-free unavailability result/exception.
Existing authority access failures remain governed by their existing boundaries.
Retain full Instant precision when sampling time; do not round a post-expiry read
back into validity. Snapshot lifetime is positive and no greater than 24 hours.

Every current context anchor must exist in the current catalog and have a validated
displayLabel. Deny the complete snapshot if any is missing; do not silently truncate,
drop an area or invent labels. Return only those exact anchors, ordered by canonical
UUID string, with immutable category sets/lists derived from the catalog. No provider,
grant, acceptance store, HTTP, cache, persistence write, budget debit or nested authority
work is allowed. Existing 128-context/512-catalog bounds cap processing.

## Snapshot

An immutable internal snapshot contains context ID, exact revision, exact consent
generation, context issuedAt/expiresAt and 1..128 choices (anchor ID, validated label,
closed categories). No actor/journey ID, raw coordinates, geometry, route endpoints,
catalog version or whole-catalog access in the returned model. Redact snapshot,
choice and service toString and errors. Defensively copy collections and validate
identity/duplicate/count/temporal/value bounds at construction.

This is an observed owner snapshot, not a command grant, physical-presence proof,
anti-Sybil assessment or public eligibility. A later write must use current authority.
Existing issuance is not exact-context CAS when the anchor survives replacement;
ADR 0044 adds a separate internal expected-context issuance entry point. The
legacy browser contract remains anchor-only. `EXACT_CONTEXT_SIGNAL_ISSUANCE_PLAN.md`
records its implementation, evidence and remaining guarded browser contract work;
it does not expose this reader or change the existing HTTP contract.
The internal reader cannot be exposed directly without reviewed no-store browser
guards, request quotas, DTO/string encoding, feature gates and lifecycle tests.

## Verification

Test exact labeled subset/category derivation, canonical ordering/immutability and
redaction; foreign/missing/inactive journey; off or mismatched consent; absent,
future, expired or mismatched-owner context; absent/old catalog provenance; missing
anchor/label; suspended or mismatched restriction; and expiry occurring during
participant reads. Prove the owned callback is used once and no write interface is
required. Reuse existing authority/participant infrastructure; no new transaction
implementation or public transport in this slice. Full core check/bootJar required.
