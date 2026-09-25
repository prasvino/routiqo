# ADR 0055: Consented community traffic summary proposal

Date: 2026-09-23
Status: **experimental candidate architecture under [ADR 0065](0065-public-live-v1-policy.md) (2026-09-25): closed, explicitly consented, disabled-by-default staging experiment only.** Production activation requires a separate explicit decision after the pilot evidence listed there. Previously: V3 implementation and staging evaluation authorized on 2026-09-23.

> **Status update (2026-09-25):** Superseded by the direction brief. The V3 community traffic summary (12/10/80% rules) is archived and its flags stay off. Its report and moderation lessons carry into the pilot moderation plan. See [PRODUCT.md](../PRODUCT.md).

Implementation record, 2026-09-23: V23/V24, guarded Share/Stop/recovery,
catalog-versioned snapshot publisher, canonical reader/report, internal audited
suppression, independent maintenance, generated contracts and active-journey web
controls are integrated behind disabled production flags. The feed carries a
server-time sample so clients can expire delivered rows conservatively without
trusting the device wall clock. [Staging evidence](../archive/validation/V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md)
records test coverage, synthetic utility and missing real-environment gates.
This implementation record does not accept the V3 terms for production.

## Context and decision boundary

The user selected a measurable person-level privacy guarantee for traveller-derived public LIVE. ADR 0053 proposes a one-person, one-key, 30-day differentially private pilot; ADR 0054 proposes an irreversible Share input. Four public-protocol P1 findings remain open, including commit/window sealing and the complete observable transcript. V21/V22 and the sampler are disconnected foundations, not an approved public release.

A user-supplied implementation handoff proposes a different product contract: an explicitly opted-in **community-reported traffic summary** selected from a committed PostgreSQL snapshot. This ADR records that option for review. It does not revoke the owner's earlier privacy choice, close ADR 0053/0054 findings, or claim differential privacy, anonymity, a minimum unknown-person count, or protection from participation inference. A 12-account/10-agreement rule is a provisional utility and abuse heuristic, not a privacy proof. ADRs 0051 and 0052 remain rejected under their own stated contracts; this proposal does not retroactively approve them.

The user authorized an end-to-end, versioned V3 implementation and staging evaluation on 2026-09-23, behind a disabled production flag, and directed further person-level research to pause. No V18 `ACTIVE` intent or V22 frozen input may be reinterpreted or migrated as consent to it. V21 claims and existing records keep their original retention and cleanup rules. The selected production privacy contract changes only after the owner explicitly accepts the different disclosure and residual risks, and product/privacy review approves activation. This ADR does not authorize production exposure.

## Proposed product and privacy contract

- Only the existing structured traffic values at reviewed coarse, server-derived road anchors can enter. No free text, media, precise coordinates/endpoints, person markers, individual times, source identifiers, contributor lists, or counts appear in public output. Reports remain unverified observations; agreement is not proof of a road condition or physical presence.
- The owner deliberately opts in one accepted private Quick Signal under a fresh purpose/version. Private consent and an existing receipt are insufficient. Sharing is off by default, with exact owner-bound retry and recovery. An accepted Share means **accepted for consideration**, not selected by a snapshot or published.
- Proposed staging limit: at most one candidate per eligible account per five-minute window across all anchors and values, plus a bounded daily limit provisionally set to 12 successful new submissions per account per UTC day if no approved policy exists. Retries do not debit twice. Account limits are not natural-person limits or privacy budgets. Verification remains an eligibility/abuse prerequisite where operational; no lifelong identity registry or new document collection is proposed for this path.
- The server derives a coarse anchor from an owned active journey, current route context and approved catalog with endpoint exclusions. Readers must have an authenticated active journey and server-derived relevance; they cannot choose arbitrary anchors or coordinates. A block rule for unattributed aggregates requires explicit product/privacy review; no row may claim to exclude a blocked contributor unless that policy is actually enforced and reviewed.
- Five-minute observation windows do not imply exact publication time. Initial staging aim: process shortly after close, ideally within one minute; never serve a result after window end plus ten minutes. These are proposed settings, not proved deadline or utility guarantees. Label the observation period coarsely; do not say “live now.”

## Submission, snapshot and withdrawal

Submission validates owner, exact receipt, current consent version, journey/context/catalog, verification, restriction and rate authority in one database transaction. It derives the canonical window/key server-side and atomically inserts a candidate, exact idempotency identity and successful-new-submission debit under database uniqueness. A delayed commit can miss the publication snapshot; it is never moved to another window. A lost response remains uncertain until exact owner recovery. No automatic alternate submission occurs after reconnect.

The publication transaction's actual MVCC snapshot is the input boundary. A Stop, Ghost Mode, consent withdrawal, journey completion, deletion or restriction **committed before that snapshot** excludes a pending candidate when eligibility is read in that same snapshot. An action committed after the snapshot may be too late for the in-progress summary; it prevents future consideration and clears individual discoverable presence. A failed publication transaction publishes nothing; a bounded retry takes a new snapshot and rechecks authority. The owner must not receive a guaranteed cancellation acknowledgement when publication may already be in progress. Truthful wording is: “Future sharing stopped; a summary already being prepared may still include this report.”

This proposal does not make Share itself irreversible. After publication, the canonical aggregate is not recomputed by subtracting a stopped or deleted contributor. It expires normally or may be suppressed by an authorized audited safety action. Such takedown, publication timing, availability, and repeated windows are observable and can reveal participation when combined with other knowledge. A committed snapshot gives a consistent input set; it does **not** prove an exact wall-clock cut or the earlier whole-transcript person-level DP guarantee. Previously captured copies cannot be recalled.

## Proposed durable publication and output

Use one bounded PostgreSQL job and a canonical terminal decision per configuration version, anchor and window. Acquire publisher ownership before the input snapshot, or prove an equivalent fenced claim; publisher locks must not be required by Share/Stop. PostgreSQL `REPEATABLE READ` takes its snapshot at the first non-transaction-control statement, so an ownership-acquisition statement can itself establish a stale snapshot while waiting. Verify the exact statement order against the deployed version and test it with concurrent publishers. A publication transaction selects candidates and authority from one snapshot, deduplicates by account, decides once, and commits either canonical output or `NO_OUTPUT` with the terminal decision. A unique decision key is the final multi-replica guard. Readers serve only committed, unexpired projections; retry and failover never recompute a terminal decision or resurrect expired/suppressed output. Transaction isolation alone does not supply ownership, uniqueness, or cross-row policy correctness; real PostgreSQL concurrency tests are required. See the [PostgreSQL transaction-isolation rules](https://www.postgresql.org/docs/current/transaction-iso.html).

For staging evaluation only, require at least 12 distinct eligible accounts in the anchor/window and one unique traffic value supported by at least 10 accounts and at least 80% of eligible accounts. Otherwise persist `NO_OUTPUT`. Never lower the rule to make a demo appear busy, invent a condition, or label empty output “road clear.” Public rows contain only a random opaque reference, approved coarse area/anchor label, traffic enum, coarse observation-period label, absolute expiry, source label and schema version. No count, percentage confidence or source-correlated ID is public. Provider alerts remain separately labeled.

Reporting targets only an authorized visible canonical moment. Bounded report intake and audited moderator suppression must not expose contributors; one report does not automatically remove a row. Suppression is an independently audited serving state for a committed result, not a rewrite or redraw of its input snapshot or terminal decision. Start without public CDN cache, realtime fan-out or persistent offline community rows. Clear local memory on account change, logout, Ghost Mode and journey end; reauthorize on reconnect. Source cleanup cannot cascade-delete canonical projections. Bound jobs, query sizes, retries, rate debits and cleanup. Restore/failover must not revive expired decisions.

## Retention and disclosure under review

Proposed staging defaults are source-candidate logical expiry 24 hours after window end and removal of expired projection content within 24 hours; idempotency/debit, report/audit, backup and legal retention need separate justified schedules. These are engineering proposals, not approved deletion terms. No raw identity document, precise route or GPS trail belongs in publication storage. Public output may still be copied indefinitely by observers.

Draft disclosure and owner recovery states belong in [the focused feature spec](../archive/features/live/COMMUNITY_TRAFFIC_SUMMARY_SPEC.md). Product/privacy review must accept the participation-inference risk, after-snapshot withdrawal limit, moderation takedown behavior, block semantics, retention and absence of a person-level guarantee before any user-facing Share or public result is enabled.

## Relationship to the prior blockers

| Prior finding | Treatment in this proposal |
|---|---|
| Exact fixed-time Share/window seal and source-independent deadline | The contract uses the actual publication snapshot and does not promise exact first visibility or a DP bound. Snapshot ownership, late commits and truthful timing still require implementation tests. The old proof is not closed. |
| Stable natural-person reference across deletion | Account limits replace the one-person DP sensitivity premise. Duplicate-account abuse and verification operations remain risks; no person-level privacy claim is made. |
| Irreversible V2 Share disclosure/retention | Fresh V3 consent says accepted for consideration, with withdrawal only until the publication snapshot. The different disclosure and retention require explicit approval. V18/V22 are not reused. |
| Complete source-independent delivery transcript | The proposal accepts residual inference from timing, suppression, collusion and repeated windows, subject to informed product/privacy review. It does not assert that these channels are private. |

## Evidence and decision needed

Before choosing this contract for production: explicit owner approval of the guarantee change; independent product/privacy and security review; versioned end-to-end implementation and real PostgreSQL race/failover tests; authenticated reader/report/recovery tests; real regional catalog and OAuth configuration; rendered device/accessibility QA; retention/backup runbook; and consented density plus accuracy evidence. Simulations must label synthetic inputs and measure coverage, age, depletion, conflicting/malicious accounts and wrong-value rates against real or collected ground truth. If density is insufficient, keep an honest empty state and use the separately gated provider alert pilot.

Implementation and staging evaluation are authorized. Production flag activation and a public LIVE release are not approved by this decision.
