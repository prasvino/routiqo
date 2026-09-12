# Cohort publication design checkpoint (L0.2b)

Status: design in progress, not publication approval. ADR 0023 resolves internal
admission consistency only. This checkpoint records concrete remaining decisions
instead of treating a minimum actor threshold as proof of privacy.

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

Implement immutable context-linked receipt transitions and then specify the
admission-bound command envelope and transactional receipt store. In particular,
receipt purge must not allow an old command to be accepted again: the future
issuer should own command identity and validity rather than letting a client
relabel an old UUID under renewed admission. No token format or issuer is approved
by this checkpoint. Settle that storage/issuance decision before implementing a
durable acceptance path; keep the current pure models unmounted.
