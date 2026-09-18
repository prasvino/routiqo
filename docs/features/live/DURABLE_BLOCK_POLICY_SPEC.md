# Durable internal block policy

Status: implemented and independently reviewed; final core check/bootJar passed 363 tests across 50 suites. No public block API, contributor lookup,
aggregate suppression or delivery implementation is authorized by this spec.

## Scope

Persist the reviewed DirectionalBlock policy as moderation-owned directed edges.
Use two current enabled accounts in one trusted synchronous identity-owned
transaction: lock account rows in database UUID order BEFORE any block row.
Use one `ORDER BY id FOR UPDATE` query and verify both enabled rows; do not
substitute Java UUID.compareTo ordering. Count outgoing capacity only after those
locks, which serialize all new edges for a blocker.
Reject identical/nil/missing/disabled identities and ambient transactions, enforce
the existing five-second budget and sanitize DB errors before rollback logging.
This boundary establishes account existence/serialization, not permission for an
arbitrary public caller to target another account. Future API target references
and independent actor authentication remain mandatory.

Mutations are trusted internal application operations. Blocking with a stale
revision wins; unblocking requires an exact revision; future/negative intents
deny. Every accepted mutation advances revision. Saturation is permanently blocked.
Repeated block at the saturated maximum returns unchanged; clearing still denies.
Persist only exact +1 transitions with matching pair identity and CAS state/revision.
Store one row per directed pair (blocker_id, target_id), positive revision and
blocked boolean; absence is initial unblocked revision zero. Both account FKs
cascade deletion. Retain an unblocked row's revision to reject stale re-enables;
no action history or location/evidence data is stored.

Bound state growth: at most 100 directed edge rows per blocker, counting retained
unblocked revision rows. Both block and initial exact-unblock intents can create a retained revision row; apply the same capacity gate to every new row. New targets beyond the bound deny with a generic bounded
capacity error; mutations of existing targets still work. Do not erase revision
rows to free capacity and thereby revive stale commands. This conservative private
foundation cap is not a reviewed public UX or product block limit.

Read a pair through the same ordered account authority, returning only a trusted
internal bilateral clear/blocked decision from both directional records. Missing
edge rows inside validated current account authority mean explicit initial states;
unavailable storage/accounts deny, never clear. Reversed or unrelated pair states
cannot be combined. This is a snapshot valid inside that transaction, not a reusable publication or delivery capability. Future output must compose current authority and revocation in its own reviewed transaction; do not introduce check-then-publish races. Do not translate this into viewer-specific aggregate subtraction.

## Verification

Actual PostgreSQL tests: directional persistence and bilateral exclusion; stale
block versus exact unblock and repeated revocation; concurrent opposite-direction
writes through independent adapters (prove stable lock order/no deadlock); target
deletion and both cascades; account disabled/missing/self denials; atomic rollback
and concurrent block-versus-account-deletion behavior;
and redacted DB failures; max revision; 100-row bound including unblocked entries;
existing-edge revocation at capacity; ambient transaction rejection. Architecture
must keep API packages away from raw mutation/participant/pair authority paths.

No public endpoint, role grants, query directory, fixture data, activation or
production migration. Public block-safe publication and stale-output invalidation
remain explicitly unresolved under ADR 0038.
