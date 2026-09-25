# ADR 0064: V1 reporting protocol

Status: accepted and implemented behind the existing default-off V3 flags, 2026-09-25. Production activation still follows the ADR 0055 decision.

> **Status update (2026-09-25):** Accepted — role note by the direction brief. The protocol is reused for pilot moderation: Spot posts, voice notes, chat messages and signals are published outputs with opaque references, so the receipt-first retry, retention split, per-reporter quota and severity-first queue apply to them. Re-targeting from V3 summaries to Spot content is Phase 2 work; production activation now follows the pilot phase gates, not the ADR 0055 decision. For Spot posts at Pongal, [ADR 0068](0068-report-collapse-pending-review.md) amends the "reports never hide automatically" rule. See [PRODUCT.md](../PRODUCT.md).

## Context

The durable report intake proposal left four questions open: evidence identity, exact retry after revocation, shared lock order and investigation after evidence expires. V3 community traffic already had a working report path for canonical projections. [REPORTING_PROTOCOL_PROPOSAL.md](../features/live/REPORTING_PROTOCOL_PROPOSAL.md) proposed generalizing that path. The owner approved all six recommendations on 2026-09-25.

## Decision

1. **Only canonical published outputs are reportable in V1.** The reportable identity is the output's opaque, shared, random reference. Authorization is a current visibility check, and duplicates are removed per reporter per output. The separate private report store and the `ReportReferenceAuthority` seam are retired for V1, as are the unused `StructuredReport`/`ModerationCase` domain records.
2. **Exact retries are answered first.** Under an enabled account, the reporter's own retained `(reporter, request)` row is checked before any current authorization. It returns a minimized receipt: status and the original received/receipt-expiry times, with no content, case state or outcome. A changed retry conflicts. A logically expired row cannot establish a replay.
3. **Retention is split.** Reporter-linked rows are kept for 7 days for new writes; existing rows keep their 30 days. Reporter-free group counts, review/suppression audit and dispositions are kept for 30 days. A retention purge leaves counts unchanged. Deleting a reporter's account still removes that reporter's contribution.
4. **Durable quota.** Each reporter can make 10 new reports in any rolling 24 hours, checked in the intake transaction alongside the transport rate limit. Exact retries never count.
5. **Automatic closure.** When projection content is purged, a still-open group gets the system disposition `CLOSED_EVIDENCE_UNAVAILABLE`, distinct from dismissal. It never replaces a suppression or a current dismissal.
6. **Safety first, never automatic.** The operator queue lists groups with an `UNSAFE` report first, then newest. No count suppresses output automatically.

Lock order:
- Intake: reporter account (`FOR NO KEY UPDATE`, own row only) → projection (`FOR SHARE`) → report → group.
- Moderation: projection (`FOR UPDATE`) → live report (`FOR SHARE`) → group (`FOR UPDATE`) → grant.
- Cleanup: purged projections (`FOR UPDATE`) → disposition.

Intake rejects an ambient transaction. Attributable content, such as future journey conversation, needs its own reviewed protocol with multi-account locking in UUID order.

## Consequences

- A lost report response is recoverable after the 10-minute serving window.
- One account cannot run a sustained reporting campaign.
- Operators see safety reports first and are not shown groups that can no longer be investigated.
- Reporter identity is held for a quarter of the previous time.
- The queue cursor format changed (version 2); old cursors are rejected, and the admin client restarts from the first page.
- Real staging operation, backup retention and production activation remain separate gates.
