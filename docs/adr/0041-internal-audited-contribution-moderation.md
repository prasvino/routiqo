# ADR 0041: Internal audited contribution moderation

Date: 2026-09-19
Status: implemented and independently reviewed for the bounded internal scope

## Decision

Replace the unaudited production restriction mutation service with one internal
command boundary. Reuse the identity-owned, database-ordered enabled-account pair
authority for the operator and subject. Require a current database grant for the
exact action, exact subject revision, a closed action/reason, and an immutable
operator-scoped request identity. Commit the restriction, minimized audit receipt
and operator action debit in one transaction. Signal ingestion receives only the
read-only restriction interface.

This is an implementation prerequisite for moderation, not an administrative
endpoint, identity provider, report investigation system or permission to publish
LIVE. The operator UUID must eventually come from an independently authenticated
administrative boundary. Consumer authentication does not confer an operator role.
No seeded role, grant-management endpoint or self-service elevation is added.

## Authority and ordering

Lock the two distinct enabled accounts in PostgreSQL UUID order, then the required
operator grant, retained request receipt and action-budget rows. Sample current
server time after potentially blocking reads. Reject ambient transactions, missing
or disabled accounts, self-targeting, missing/wrong/expired/future grants, malformed
commands and unavailable authority. Check current permission even for exact replay.

Grants are action-scoped, not target-scoped: a restrict grant authorizes this
internal boundary to restrict any distinct enabled subject. A restore grant is
separate. Each grant lasts at most 24 hours. Provisioning and revocation remain a
controlled operator prerequisite, with account-before-grant locking; an arbitrary
SQL update without the protocol is not an approved administration workflow.

Both restriction and restoration require the exact current subject revision.
Restricting an already restricted subject may advance its revision, invalidating
outstanding grants. Restoration returns UNASSESSED, never an independent-witness
assessment. New commands at terminal revision are denied. Existing private receipt
replay and withdrawal remain available under their own unchanged contracts.

## Retention and bounds

Keep successful command receipts for 30 days (720 elapsed hours) from first execution. They contain
opaque operator/subject/request identities, closed action/reason, before/after
revision, resulting restriction and server timestamps. No location, report body,
source evidence, free text or moderation hold. The period is an internal
engineering bound, not an approved legal or operational evidence-retention policy.
Either account deletion removes identifying audit records. Receipts expire
logically; physical cleanup is bounded and separately callable, not scheduled here.

Limit retained receipts to 1,000 per operator without evicting fresh receipts.
Keep a separate, at-most-20-slot operator-only debit ledger for the rolling hour.
Subject deletion and audit purge must not erase charges. The ledger stores no
subject or evidence; expired slots can be reused or removed by bounded leaf-only
cleanup. Operator deletion removes it.
Future-dated charges deny new work. Exact retained retry adds no debit or effect.

While its receipt is retained, the same request with a different fingerprint is
denied. An exact retained retry
returns its original minimized result, not the subject's current state. Once the
receipt is gone, the old exact expected revision prevents reapplying its effect.
The request UUID is not a lifetime uniqueness claim: reuse with a different target
or revised intent after cleanup is a new command subject to current authority,
revision and budgets. Callers must generate fresh IDs for new intents; no indefinite
identity tombstone is retained merely to enforce client behavior.

## Review and remaining gates

Design review required separate deletion-resistant debits, exact revision checks,
post-lock time checks, fail-closed capacity, removal of the raw production mutation
service and read-only ingestion access. These are acceptance criteria, not optional
follow-up work. See the [feature specification](../features/live/AUDITED_CONTRIBUTION_MODERATION_SPEC.md).

Strong administrative authentication/step-up, safe grant provisioning, scoped queue
reads, transport request limits, report-reference authority, evidence investigation, appeals, action alerts,
tamper-resistant external audit and production retention/backup operations remain
unfinished. Database grants do not defend against a database administrator. Public
projection and existing feature gates remain closed under ADR 0038.
