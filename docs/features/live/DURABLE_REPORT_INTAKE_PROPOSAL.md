# Durable private report intake proposal

Status: proposed for review, not implemented or production-approved.

Update 2026-09-25: [REPORTING_PROTOCOL_PROPOSAL.md](REPORTING_PROTOCOL_PROPOSAL.md) proposes answers to the four review questions below, based on the implemented V3 canonical-report path, and recommends retiring this separate store for V1.

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

## Candidate resolution for the next design review — 2026-09-19

This narrows the four questions above; it does not approve storage, retention,
publication or a reference authority. No executable report adapter accompanies it.

### Separate evidence identity, authorization reference and submission receipt

The evidence owner must allocate an immutable internal canonical evidence/version
identity. A reporter-bound, expiring reference authorizes access to that identity;
reference rotation must not allocate new evidence or bypass per-reporter evidence
deduplication. Never expose the canonical ID, contributors or a raw signal receipt
as report targeting authority. The public projection design must still define
which immutable object is reportable, how corrections/windows change its identity,
and how long deduplication remains meaningful. Seven-day report retention cannot
promise permanent deduplication after purge.

Keep the original request fingerprint separate from the canonical deduplication
key. Reusing a request with a different reason or different authorization reference
conflicts even when both references resolve to the same evidence. A new request
with a rotated authorized reference still deduplicates against the same canonical
evidence. Current StructuredReport.evidenceRefId alone cannot represent both roles;
its existing pure tests are not proof of this future persistence contract.

### Minimized owner receipt after revocation

Preferred candidate: an enabled authenticated reporter may recover only its own
retained exact submission receipt after source access expires or is revoked. That
response contains an opaque case ID and original submission/receipt expiry times,
not source content, reference, canonical ID, contributor, location, current case
state or moderation result. It establishes only that the earlier submission was
received. It neither renews source access nor performs another intake/effect.

The original bounded fingerprint is compared under reporter/request ownership;
renewing an authorization reference is not an exact retry. Logically expired or
purged receipts cannot establish replay. Missing source authority still denies
new submissions. Reporter deletion removes the receipt; disabled reporters cannot
read it. This candidate is deliberately distinct from an operator action retry,
which still requires current action permission under ADR 0041.

### Transaction discovery is not authority

New intake cannot enter the current reporter-only authority and then nest
EnabledAccountPairAuthority: the existing pair boundary rejects ambient
transactions. Any evidence-owner lookup before locking is a bounded hint only.
The actual protocol must discover a bounded participant set, acquire required
account locks in PostgreSQL UUID order, then acquire the documented evidence/
publication/block/report locks and revalidate the complete authority snapshot.
A changed participant set must abort; it must not acquire another account out of
order or spin without a bounded request budget. Final server time is sampled
after blocking reads. No providers or network calls occur inside the transaction.

This is a constraint, not a completed lock protocol. The evidence owner must list
all mutation paths, deletion/expiry cleanup and revocation writers with their lock
order, including aggregate evidence with more than two contributors. Do not infer
that the existing two-account boundary can authorize an arbitrary cohort. Prove
submit/block, submit/delete, submit/expiry and concurrent same-request behavior in
real PostgreSQL before implementing the durable store.

### Investigation without an undeclared evidence hold

A retained receipt is not investigable source evidence. Operator reads require
independent authentication, assignment/target scope and current evidence-owner
permission. If that source is unavailable, the case cannot support an evidence-
based restriction merely from its report count, reason or opaque identifiers.
Do not copy content, retain an expired contributor mapping, extend source lifetime
or label the case ACTIONED to make the queue appear complete.

The proposal must define an explicit evidence-unavailable outcome distinct from
an operator finding that a report is unfounded. Existing OPEN/DISMISSED/ACTIONED
states do not express that distinction. Whether the short source lifetime permits
a useful investigation service, and what honest reporter/operator copy follows,
remain approval gates. No automatic enforcement or hidden retention extension is
approved by this candidate.

Independent review requires the next protocol to specify the retained reference
fingerprint representation and its deletion policy without retaining a reusable
capability. Deduplication is reporter plus canonical evidence, never global across
reporters. Expiry of a reporter's submit reference does not prove source expiry;
reference rotation cannot grant operator investigation access. Conversely source
deletion or applicable consent/block revocation can end permissible investigation
before its TTL. Specify which revocations affect only reporter access and which
end source availability under the same atomic protocol.

Evidence-unavailable closure applies only to unresolved cases and must serialize
with any authorized action. It must not rewrite historical ACTIONED outcomes or
undo an enforced restriction implicitly. Add explicit tests for expiry/action and
revocation/action races. Candidate quotas, retention, canonical identity scope,
fingerprint storage, participant/retry bounds and useful investigator access all
remain unapproved. Review found no basis to turn this candidate into a test-only
production store.
