# ADR 0040: Internal durable block policy

Date: 2026-09-18
Status: accepted for implementation; no public/API activation

Store private directional block intent with monotonic revisions in moderation.
Evaluate bilateral exclusion through both directions only after two current
enabled accounts are serialized by an identity-owned ordered-pair transaction.
Acquire both accounts in PostgreSQL UUID order using one ordered locking query,
before any edge read or mutation; reject ambient transactions and sanitize errors.

Absence is initial unblocked revision zero within verified account authority;
missing/disabled accounts or unavailable storage never mean clear. Preserve
stale-revocation precedence, exact enabling, irreversible maximum revision and
strict persisted CAS transitions. Both account FKs cascade deletion.

Retain at most 100 directed rows per blocker, including unblocked revision
tombstones, with no event history, location or report payload. Count under the
locked blocker authority. Never delete an unblocked revision just to free capacity,
because delayed stale commands could regain effect. Existing-edge changes remain
possible at capacity. A public UX/capacity/reset policy requires further review.

This enables internal block policy tests and future authorized composition. It
does not expose account lookup, contributor identities or arbitrary public targets,
nor solve aggregate suppression under ADR 0038. Public reference authorization,
request budgets, UI and projection/realtime invalidation remain separate gates.

Acceptance: DURABLE_BLOCK_POLICY_SPEC.md, including opposite-direction concurrent
writes, deletion races, saturated revisions, bounded capacity and failure denial.
