# ADR 0038: Publication threat boundary and safety prerequisites

Date: 2026-09-18
Status: accepted safety direction; public publication remains unapproved

## Decision

Do not implement an automatically publishing threshold-only traveller projection.
Keep private ingestion distinct from publication permission. Fixed public anchors,
canonical shared windows and bounded reads are necessary reductions in query
flexibility, but do not establish contributor independence or anonymity.

Under the current threat model an attacker can create enough accounts to supply
all but one required contribution. The difference between moment existence and
absence reveals whether the remaining person contributed. Repeating after Ghost,
withdrawal or block changes can expose the same fact. Increasing the threshold,
removing counts, hiding identities or delaying a deterministic update alone does
not remove the attack. Trust scores based solely on account age, successful Google
login or route ownership do not establish independent humans or presence.

Public release therefore requires an explicit bounded-adversary assumption and
reviewed privacy/utility analysis. No numeric cohort threshold/window is silently
promoted to a safe production default. If arbitrary collusion remains in scope,
the current deterministic publication proposal cannot claim the required guarantee.
This is an engineering/privacy decision gate, not a missing API credential.

## Enforceable independent work

1. Enforce durable rolling and per-category write budgets (ADR 0037).
2. Establish moderation-owned versioned contributor safety state: publication
   eligibility defaults to unassessed, suspension dominates, and an expired or
   revoked assessment never recovers automatically. Assessment is an auditable
   operator decision, not a proof of human uniqueness or permission to publish.
3. Establish bounded reason-enum reports tied to server-authorized evidence, with
   private case references and idempotency; never expose reporter/subject identity
   or accept arbitrary public target-account identifiers.
4. Establish durable block policy and revocation revisions. Revisions invalidate
   previously captured eligibility; enabling or unblocking does not resurrect old
   evidence. Domain-owned interfaces must compose within ordered transactions.
5. Require current account, journey, consent, context/catalog, safety and evidence
   state at future publication reads. Missing/stale/unavailable authority denies.
   No positive publication decision can come from a private receipt alone.

Do not create public reporting or admin endpoints before independent authentication,
operator permissions, bounded evidence references and audited mutation paths exist.
Do not create dummy permissive trust providers to make a demo work.

## Candidate protocol to evaluate, not a release contract

Use server-owned nonoverlapping anchors, one canonical projection per fixed window,
at most one contribution per eligibility principal/category/window, no viewer-specific
subtraction, no exact counts or timestamps, current read authorization and bounded
delivery lifetime. Suppression must be generic and consistent across viewers.
Even this protocol requires analysis of suppression timing and block edges; global
suppression can still reveal a state change and lets attackers deny availability.

Any future numeric proposal must specify the independent-principal evidence and
collusion bound, overlapping/time-window composition, permitted inference/leakage,
withdrawal semantics, read/write/query budgets, stale client exposure and utility
under sparse evidence. Obtain independent adversarial review before API output.

## Acceptance evidence

Exercise 9-attacker/1-target and analogous threshold-minus-one cases; unrelated
account rotation; repeated adjacent/window queries; block/unblock toggles; terminal
withdrawal; exact replay after revocation; expired operator assessments; concurrent
moderation and acceptance; restart/replica disagreement; missing authority.
Tests of implementation prerequisites do not prove the unresolved public protocol.

External OAuth, regional data, device access and deployment remain outside this
run. None is needed to implement and verify the above private safety foundations.

## Why the remaining gate needs a changed, measurable assumption

For a deterministic public output F, require that removing any one eligible
person never changes any observable output (existence, content, freshness or
suppression timing). This requires F(D) = F(D without that person) for each
adjacent pair of datasets. Repeated removals connect every finite dataset to the
empty dataset, so this absolute requirement forces a constant output. A useful
deterministic traveller-evidence output cannot satisfy that absolute guarantee.
This argument concerns the strong universal claim, not every practical privacy
policy. A reviewed bounded-inference policy can make a different claim explicitly.

A collusion bound alone is also insufficient unless it bounds the adversary's
knowledge of other contributions, query composition and temporal observations.
A future proposal must state its exact neighboring-data relation, permitted
inference and observation transcript. If it introduces randomized output, its
privacy budget, composition, utility and truthful treatment of uncertainty need
separate review; do not fabricate road conditions to satisfy a statistical target.
These are engineering/product privacy semantics, not missing credentials.
