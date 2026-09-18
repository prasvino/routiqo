# Durable private report intake proposal

Status: proposed for review, not implemented or production-approved.

## Scope and authority

Persist minimized structured-report/case metadata independently of public output.
A report reference must be issued by a future reviewed evidence/publication owner;
raw account IDs, receipt UUIDs or caller-created opaque IDs are not valid authority.
No public or operator endpoint, permissive reference provider or evidence body is
part of this slice. Without a real reference authority, report intake is unmounted.

A mandatory ReportReferenceAuthority participant must validate current authenticated
reporter access to the exact reference within the same identity-owned account
transaction. It must use the same datasource/thread, perform no external calls,
and coordinate revocation through its documented lock protocol. That protocol and
its concrete implementation remain a publication-design gate. Absence/unavailability
must deny; a callback returning a UUID is not proof of authorization.

## Proposed private persistence

Use the existing enabled-account transaction and StructuredReport/ModerationCase
models. Server clock and case ID; account-scoped request identity. Retained exact
retry returns the same case/times without a fresh quota charge; changed reference
or reason on that request conflicts. Current account validation still applies.
A repeat reference with a different request does not create another case or reset
retention. Return a generic conflict; never reveal another reporter's submission.

Bound metadata to 100 retained cases per reporter and 10 new cases in the preceding
rolling 24 hours, across sessions/journeys. Existing retained exact retry remains
available at quota. The report table itself preserves all charges for that window.
Clock rollback/future rows deny new writes. Cleanup and intake must coordinate row
locks before the final server-time sample; do not let purge/capacity races reset a
live charge. Account deletion cascades all report metadata.

Proposed retention: seven days from first receipt, never extended by retry or case
resolution. Fields: opaque reporter/request/reference/case IDs, closed reason,
server receipt/expiry and case revision/state. No free text, precise location,
contributor profile, evidence copy or indefinite moderation hold. This does not
extend the existing raw signal lifetime or create a right to preserve deleted
content. Logical expiry denies use independently of bounded physical cleanup.
Production operational/audit/legal retention remains a separate release decision.

Persist intake only initially (OPEN cases). Do not persist ACTIONED as if an action
was enforced. Operator resolution requires independent operator authentication,
permission, an atomic audit record and effect-specific mutation/revocation authority;
existing pure resolution transitions are insufficient to authorize production writes.
No queue/list endpoint before scoped operator read authorization and bounded paging.

## Acceptance before implementation

Review the proposed authority seam and retention. Test account isolation, exact
replay and changed-fingerprint conflict, reference validation denial/unavailability,
same-reference deduplication, cross-session rolling quotas, capacity, clock rollback,
expiry boundaries, delete/submit and cleanup/submit races, atomic rollback/redaction,
missing authority composition and no transport access to raw store/service.

A fake reference authority may exist in tests only. It proves application behavior,
not real reference issuance, public reportability, moderation efficacy or publication
privacy. Do not describe this proposal or a test-only composition as operational
reporting. Prefer finishing the already-scoped durable block slice before coding.
## Independent review disposition

Do not implement this proposal yet. A store backed only by test reference authority
would not provide usable reporting. Before the persistence slice, resolve:

1. Canonical evidence identity versus rotating reporter-bound authorization
   references. Deduplicate the evidence, not an arbitrarily renewable capability.
2. Exact retry after evidence revocation. Choose an explicitly minimized owner
   submission receipt or require renewed evidence access; neither may return
   prohibited source evidence. A receipt is not renewed evidence permission.
3. Shared lock order for reporter, subject/block/publication state and concurrent
   revocation. The proposed reporter-only account transaction is not approved
   if real reference validation needs additional account locks.
4. Investigation lifecycle when report metadata outlives source evidence. Define
   what an operator can actually inspect without an undeclared copy or hold.

These are engineering decisions, not credentials. The candidate numbers above
remain unapproved until a useful investigation/retention protocol is reviewed.
Complete durable blocking first; preserve this checkpoint for the real reporting
protocol rather than presenting a test-only composition as completed moderation.
