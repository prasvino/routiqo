# Durable Live route context

Status: implemented and tested as internal storage. ADRs 0031 and 0032 add a
default-off provider-backed resolver and private two-transaction binding; no
public registration, admission issuer or projection exists. ADR 0027 records the
storage behavior and limits.

> **Direction brief (2026-09-25):** Kept as the store for a Journey's Spots ahead. The 15-minute bound context used by route binding is too short for long highway trips (Chennai to Madurai) and needs a longer or renewable lifetime. Consent-participant ordering is retired with per-journey consent. See [PRODUCT.md](../../PRODUCT.md).

ADR 0033 adds nullable catalog provenance to the stored envelope. Provider-bound
replacement requires and stores the resolver's non-nil immutable catalog version.
Historical and ordinary trusted replacements remain unvalidated and explicitly
clear provenance; matching anchor IDs never manufacture it.

ADR 0034 exposes a minimal private owner read/bind transport behind an independent
default-off flag. It reads through a restricted interface and binds only through
the provider-backed coordinator; the API cannot call raw replacement. Responses
omit actor, journey, catalog, geometry and endpoints, and do not grant publication.

Add a routeupdate-owned stored context envelope around LiveRouteContext with server
issuedAt/expiresAt. Validate the original requested lifetime as positive and at
most 24 hours, then floor computed expiry to PostgreSQL microsecond precision and
reject any lifetime that becomes nonpositive after normalization. Eligibility is half-open;
null/future evaluation is ineligible. Preserve redacted diagnostics. Store one
latest row per account, owned-journey composite FK and account FK with cascade
deletion, unique non-nil context UUID, nonnegative revision, and 1..128 non-nil
distinct anchor UUIDs. No geometry, endpoints, context history or automatic renewal.
Use migration V9 and disposable PostgreSQL tests only.

The application service enters JourneyWriteAuthority. Evaluate the injected
server Clock after acquiring all relevant locks, including the context row lock;
the participant may own the clock or invoke a trusted time supplier after locking.
A JDBC participant reads/replaces the context
inside that transaction and rejects calls outside an active transaction. Reads of
missing, expired, future-dated or differently bound contexts return empty; completed
journeys always return empty. Reads never write or refresh expiry.

Explicit replacement accepts a trusted, already-validated anchor set, requested
bounded lifetime and optional expected current context ID. A current context
requires its exact expected ID; missing/expired/different-journey context requires
no expected ID. Future-dated stored rows deny replacement. Stale expected IDs
conflict, including an expected ID for an expired context. Only ACTIVE journeys
can replace. Every successful replacement generates a new server UUID; callers
cannot supply a chosen new ID. Advance revision for the same bound journey row;
new journey or physically absent row may begin at zero because a fresh UUID is
mandatory. Overflow fails without mutation. Do not recycle an expired context,
mint new identity on an implicit retry, or perform provider work in a transaction.
Returning anchors does not authorize admission or establish physical presence.

ADR 0032 adds a mandatory Route Update attempt participant to the JDBC context
writer. Every successful replacement invalidates a pending binding attempt for
the account after the context write in the same transaction, preserving context
then attempt lock order. The binding coordinator consumes its own attempt before
replacement, so this invalidation is a no-op for a successful bind. This rule is
enforced at the persistence participant rather than only at a service facade, so
trusted direct participants cannot bypass the stale-provider fence.

Implement JourneyCompletionParticipant to delete only the matching current context
atomically with real service completion. This follows consent in the documented
account -> journey -> consent -> context order. Make participant ordering explicit
and deterministic in production composition; no arbitrary cross-domain repository
access or dependency cycle. Completion rollback restores the deleted context.
Account deletion cascades the latest row. Ghost does not need to erase route
context in this slice, but current consent is always separately required for future
signal admission; context storage is never permission to publish.

Provide a callable internal bounded expiry-maintenance operation (1..100 rows,
indexed expiry, fixed query/time budget). ADR 0036 specifies a separately
default-off scheduler using this existing operation.
Use FOR UPDATE SKIP LOCKED and recheck exact row identity/expiry on deletion.
Maintenance may lock only context rows and must never acquire account/journey or
other authority locks afterward: this leaf-only deletion is an explicit lock-order
exception, avoiding reversed-order deadlocks. No receipt/grant cascade is allowed
from context cleanup in future schemas. Logical expiry remains authoritative while
cleanup is delayed; no claim of immediate erasure or an operating cleanup job.
Maintenance rejects ambient transactions, uses shared configured datasource/TM,
and sanitizes SQL/transaction failures before rollback logging and at the outer
boundary, reusing the established generic availability behavior where appropriate.

Acceptance: domain/DB bounds and ownership; opt-out/nonmutating reads; exact TTL
boundaries and time sampling after lock wait; fresh identity and stale replacement
rejection; independent-adapter same-expected race; expired and purged contexts
cannot restore admission; completion/deletion/rollback; explicit participant order;
bounded cleanup skipping a locked row and preserving a concurrent replacement;
cleanup failures/transaction requirements/redaction. Keep public Live, grant
category validation, physical-presence and cohort-publication gates explicit in
docs/status.
