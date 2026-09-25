> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Cohort publication design checkpoint (L0.2b)

Status: safety direction recorded in ADR 0038, not publication approval. ADR 0023 resolves internal
admission consistency only. This checkpoint records concrete remaining decisions
instead of treating a minimum actor threshold as proof of privacy.

The [public LIVE privacy protocol implementation contract](PUBLIC_LIVE_PRIVACY_PROTOCOL_SPEC.md)
records the current human-verification direction and fail-closed acceptance criteria.
It does not approve a public projection or relax the collusion threat boundary.

## Fixed first-release direction

- Active owned-journey relevance, server-owned anchor registry and no arbitrary
  spatial/segment or nearby-person queries.
- No individual presence, member directory, crowd counts or raw report counts.
- Deterministic structured evidence before AI; report band and coarse freshness,
  never an exact ETA or evidence-free road-safety claim.
- A shared canonical projection, not per-viewer subtraction of blocked reporters.
- Fixed publication windows and bounded admission/read/write budgets before any
  public projection. Candidate minimum ten distinct eligible actors remains
  provisional and does not make colluding accounts independent witnesses.

## Why publication remains closed

Suppressing a single reporter is not enough: an attacker can supply the other
reports, observe moment appearance/disappearance or manipulate a block edge to
infer the target's contribution. Even a shared projection can leak if withdrawal
causes immediate distinguishable output changes. Delaying updates alone does not
prove safety, and cannot silently weaken current authorization/block requirements.

Do not implement a superficially safe `count >= 10` publisher. Admission models
and receipt lifecycle can advance independently while the following decision is
reviewed: what shared suppression, time-window, contribution eligibility and
query-budget policy meets both timely revocation and repeated-query privacy?

## Required decision and adversarial cases

| Decision | Cases the design must resolve |
|---|---|
| Fixed partitions and windows | Overlapping routes, adjacent anchors, zoom/query changes, repeated polling across boundaries |
| Evidence independence | Many accounts from one actor, coordinated matching/contradictory reports, one account across journeys/categories |
| Block-safe shared projection | Attacker creates/removes a known block edge; no individualized subtraction oracle or stable contributor association |
| Withdrawal/deletion | Last qualifying reporter leaves; Ghost off/on; context changes; stale caches; previously delivered offline data |
| Publication condition | Sparse evidence and conflicts; disappearance/freshness/category changes must not identify an individual |
| Query budgets | Multi-session/multi-replica queries, route changes used to reset budgets, unavailable authority |
| Utility tradeoff | Conservative suppression must remain honest; do not invent substitute evidence or mislabel stale data as live |

Before enabling projection, write the cohort ADR with an explicit attacker model,
numeric partitions/windows/budgets, withdrawal semantics and falsifiable tests.
Independent review must challenge the candidate against these cases. This is a
technical design gate, not a request for credentials or user input. L0.3a receipt
state work may proceed; public moment/API/UI exposure may not bypass it.

## Independent progress while publication is closed

ADRs 0024–0036 now implement private command/receipt storage, authenticated owner
transports and default-off bounded cleanup. Receipt purge cannot reset consumed
grants. These verified prerequisites do not confer publication permission.

ADR 0037 adds durable rolling write budgets. ADR 0038 records the adversarial
conclusion: under unrestricted collusion, deterministic useful output and timely
revocation cannot currently meet the stated individual non-association goal.
Nine attacker-controlled contributions plus a target's tenth contribution form
an existence oracle; Ghost/block withdrawal repeats it. Raising ten to another
threshold or globally suppressing on every block does not resolve that conflict.

Implement versioned moderation/trust state, blocking/reporting boundaries and
revocation fences independently. Unknown trust must not default to an independent
witness; Google subject uniqueness, account age and route relevance are not proof.
No public API or hidden feature switch may bypass this decision. A future release
proposal must specify its bounded-adversary assumption, measurable privacy claim,
independence evidence and entire-output adversarial tests before approval.
