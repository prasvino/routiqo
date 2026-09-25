# Reporting protocol proposal

Status: **approved by the owner on 2026-09-25 (all six recommendations) and implemented for V3 behind the existing default-off flags; see [ADR 0064](../../adr/0064-v1-reporting-protocol.md).** The rest of this section keeps the original proposal text; where implementation refined it, a note says so.

Original status: proposed for owner review. This proposal answers the open questions in [DURABLE_REPORT_INTAKE_PROPOSAL.md](DURABLE_REPORT_INTAKE_PROPOSAL.md) ("Independent review disposition", items 1–4) and the `todo.md` item "Resolve canonical reporting evidence identity, reference authorization, exact retries after revocation and shared transaction lock order". It changes no code, flag or retention setting. Production activation of anything below still needs the ADR 0055 privacy-contract decision.

## Summary

The earlier proposal designed a general private report store that needed a future evidence owner, rotating reporter-bound references and a multi-account lock protocol. Since then, V3 community traffic (ADR 0055/0056, V24/V25) has implemented a working report path for one reportable object: the **canonical traffic projection**. That path already answers most of the open questions in practice.

**Recommendation:** make Routiqo's V1 reporting protocol the V3 model, generalized. Only a canonical published output with an opaque, shared, random reference is reportable. Close the two concrete gaps found in the V3 implementation (exact retry after expiry, and a durable per-reporter quota). Shorten the retention of reporter-linked rows. Retire the separate private store and the `ReportReferenceAuthority` seam until a feature exists that publishes attributable individual content.

## What exists today (facts)

| Concern | Current V3 behaviour | Source |
|---|---|---|
| Reportable object | `community_traffic_projection_v3.ref`: a random UUID per canonical terminal decision, identical for every authorized reader, not correlated with contributors | V24 migration; `JdbcCommunityTrafficV3.feed` |
| Authorization | At report time the reporter must be an eligible reader (enabled account, owned active journey, current route context and sharing consent) and the projection must be visible to that journey's context, unsuppressed and unexpired | `JdbcCommunityTrafficV3.report`, `eligibleReader`, `visible` |
| Deduplication | `UNIQUE (actor_id, ref)`: one report per reporter per projection; a second request for the same projection is a generic conflict | V24 |
| Exact retry | `PRIMARY KEY (actor_id, request_id)`: same request, projection and reason returns success; a changed reason or projection conflicts. **The retry check runs after the visibility check**, so a retry after the projection expires, is suppressed or the journey ends returns "missing" | `JdbcCommunityTrafficV3.report` |
| Reasons | `INACCURATE`, `UNSAFE`, `SPAM` (closed set, no free text) | V24 |
| Abuse limit | 5 per rate window per actor through the shared rate limiter; no durable daily quota | `BrowserCommunityTrafficV3Controller` |
| Moderator view | Reports grouped by `ref` with per-reason counts; no reporter IDs; `EVIDENCE_UNAVAILABLE` once projection content is gone; dismiss needs investigable evidence; suppress needs a current unsuppressed projection and an open group | ADR 0056; `JdbcTrafficReview` |
| Retention | Projection served until `window_start` + 15 min (window end + 10 min), content removed within a further 24 h; report rows, groups and review/suppression audit 30 days (`720 hours`) | V24/V25; `JdbcCommunityTrafficV3Cleanup` |
| Transaction boundary | Intake runs its own transaction (8 s timeout) with no account lock and, unlike the account, pair, block and restriction boundaries, does not reject an ambient transaction | `JdbcCommunityTrafficV3`; compare `JdbcAccountWriteAuthority` |
| Unused domain model | `moderation/domain/StructuredReport` and `ModerationCase` are pure, unpersisted records with a different reason set (`MISLEADING_INFORMATION`, `UNSAFE_CONTENT`, `HARASSMENT`, `SPAM_MANIPULATION`) and an `evidenceRefId` that cannot express both identity roles | `moderation/domain` |
| Lock order | Intake: projection `FOR SHARE` → report insert → group upsert. Moderator: projection `FOR UPDATE` → latest live report `FOR SHARE` → group `FOR UPDATE` → operator grant `FOR UPDATE`. Cleanup deletes a report and its trigger then locks the group. All paths take projection before report before group | `JdbcCommunityTrafficV3`, `JdbcTrafficReview` |

## Answers to the four open questions

### 1. Canonical evidence identity versus authorization reference

**Proposal:** the reportable identity *is* the canonical published output's opaque reference. There is no separate reporter-bound capability.

- A reference is reportable only if it names an immutable canonical output that every authorized reader receives unchanged: V3 projections today, and approved Live Moments later if they follow the same canonical-output pattern.
- Authorization is a **current check**, not a capability: at submission the reporter must currently be allowed to see that exact output. The reference itself grants nothing, so rotating reporter-bound references (and their fingerprint storage) are unnecessary. Guessing another valid reference gains only a report against an output the reporter cannot see, which the visibility check refuses with the same generic "missing" answer.
- Deduplication is **per reporter per canonical output** (`reporter, ref`), never global. It lasts as long as the reporter-linked row (see question 4); after purge a new report is possible, and the proposal says so rather than promising permanent deduplication.
- Raw Quick Signals, candidates, receipts, contributors, accounts and locations are **never** reportable references.

Why this suits Routiqo: V1 publishes no individual content. Its public outputs are aggregates (community traffic, official alerts), and V1 excludes DMs, permanent groups and public profiles. A capability-rotation scheme solves a problem that V1 does not have.

### 2. Exact retry after the output expires or access is revoked

**Proposal:** adopt the earlier "minimized owner receipt" candidate, and fix the order in the V3 intake.

1. Authenticate the reporter (enabled account, current session).
2. Look up `(reporter, request_id)` **first**. If it exists with the same reference and reason, return the original receipt (`received`, plus the original submission and receipt-expiry times), whether or not the output, the journey or the reporter's read access still exists. A mismatch is a generic conflict.
3. Only for a new request: require current eligibility and visibility, then insert.

The receipt says only "your earlier report was received". It returns no content, projection data, case state or outcome; it renews no access and never creates a second report. It disappears when the reporter-linked row is purged or the account is deleted. A disabled account cannot read it.

This closes a real gap: today a phone that lost the response to a report, then retried after the 10-minute serving window ended, gets "missing" and cannot tell whether the report was received.

### 3. Shared transaction lock order

**Proposal:** for reports on aggregate canonical outputs, no *other* account is locked, and the existing order becomes the documented rule.

> Implementation note: the approved durable quota (R4) needs one reporter's concurrent new reports to be serialized, so intake first takes the reporter's **own** account row (`FOR NO KEY UPDATE`, which does not block other tables' foreign-key checks). That matches the account-first order every other account-bound write uses. The implemented order is: reporter account → projection → report → group; moderation remains projection → report → group → grant.

```
projection (FOR SHARE by intake, FOR UPDATE by moderation)
  → report row(s)
    → report group
      → operator grant (moderation only)
```

- A report writes only reporter-owned rows and a reporter-free group counter. It does not change any other account's state, so there is no participant set to discover and no pair or cohort authority to take.
- **Reporter revocation** (Ghost Mode, consent withdrawal, journey completion) is not serialized with intake. A report committed concurrently with the reporter's own withdrawal publishes nothing and grants nothing, so the race is harmless; the next new request is refused.
- **Account deletion** cascades reporter rows through the foreign key; a concurrent intake either commits first and is then deleted, or fails its insert.
- **Output suppression or expiry** is serialized through the projection lock: intake re-checks visibility after acquiring it, so it cannot report an output that moderation has just suppressed.
- **Blocking** does not apply: aggregates carry no contributor attribution, so there is no reporter/subject pair to check. ADR 0055 already requires the UI not to claim blocked users' reports were filtered.

The earlier constraint (discover participants, lock accounts in UUID order, then lock evidence/block/report state, abort on change) is kept as the **required rule for any future attributable target** (for example, temporary journey conversations). It is not needed, and should not be built, for V1.

### 4. Investigation after source evidence expires

**Proposal:** keep V3's explicit `EVIDENCE_UNAVAILABLE` state, and make its closure explicit and automatic.

- A group whose projection content is gone shows `EVIDENCE_UNAVAILABLE` with no road or value detail. It can neither be dismissed as unfounded nor suppressed (current behaviour).
- Add a terminal disposition `CLOSED_EVIDENCE_UNAVAILABLE`, applied automatically when the projection content is purged and the group has no decision. It is distinct from `DISMISSED` and never rewrites an earlier `SUPPRESSED`/actioned outcome. It needs no operator action and serializes on the group lock like any disposition.
- Reporter-free aggregate counts are retained until the group expires, so repeated reports against the same area/period pattern remain available for catalog or abuse review in the database. That is the only investigation possible without an evidence copy, and the proposal says so plainly.

> Implementation note: closed groups leave the operator queue; their counts remain in storage for the 30-day retention. Deleting a reporter's account still removes that reporter's contribution from the counts; only the 7-day retention purge leaves counts unchanged.
- No source content is copied, no lifetime is extended, and report count alone never justifies a restriction.

## Recommendations that suit Routiqo

| # | Recommendation | Why for Routiqo | Needs your decision? |
|---|---|---|---|
| R1 | Adopt the V3 canonical-output model as the only V1 reporting protocol; retire the separate private report store and `ReportReferenceAuthority` | V1 publishes only aggregates; one path is simpler to operate, test and explain | Yes: approve |
| R2 | Check exact retries before current authorization and return a minimized receipt | Routiqo is offline-first; lost responses on mobile networks are normal, and the 10-minute serving window makes late retries likely | Yes: approve the receipt contents |
| R3 | Split retention: reporter-linked rows **7 days**; reporter-free group counts and moderator audit **30 days** | Reporter identity is only needed for retry, dedup and per-reporter abuse limits; aggregate counts and audit serve review and trend analysis without identifying anyone | Yes: approve both numbers |
| R4 | Add a durable per-reporter quota: **10 new reports per rolling 24 h**, in the same transaction as the insert, alongside the existing rate limit; exact retries never debit | The shared rate limiter is a short-window transport control, not a durable daily bound, so it does not cap a sustained campaign by one account | Yes: approve the number |
| R5 | Automatic `CLOSED_EVIDENCE_UNAVAILABLE` disposition, distinct from dismissal | Keeps the operator queue honest and short without pretending an expired output was reviewed | Yes: approve the state |
| R6 | Prioritize `UNSAFE` groups in the operator queue; never auto-suppress on any count | Safety reports need the fastest human look; automatic takedown invites brigading of accurate reports | Yes: approve ordering |
| R7 | Keep official provider alerts out of this report path; offer "this alert looks wrong" as provider feedback later | Routiqo cannot moderate or suppress a public authority's alert; mixing the two would mislead reporters | No, engineering default |
| R8a | Retire or align the unused `StructuredReport`/`ModerationCase` records so there is one report vocabulary (the V3 closed reasons) and no second, untested case model | Avoids a future contributor wiring the wrong model to storage | No, engineering default |
| R8 | Require the same model (immutable canonical output, shared random reference, current visibility check) before any future output type becomes reportable; attributable content (journey conversation) gets its own reviewed protocol with the multi-account lock rule | Prevents re-opening this design for every feature, and keeps the hard problem where it actually exists | No, engineering default |

Recommended reason set stays `INACCURATE`, `UNSAFE`, `SPAM` for traffic. Do not add free text in V1.

## Implementation outline (after approval)

Small, behind the existing default-off V3 flags:

1. Reorder `JdbcCommunityTrafficV3.report` so the `(actor, request_id)` lookup happens first, and make it reject an ambient transaction like the other write boundaries; return the stored `created_at`/`expires_at` in the receipt; add a contract version for the receipt body.
2. New migration: per-reporter rolling-24 h debit (or a bounded count over retained rows), and a split retention (reporter rows 7 days; groups/audit 30 days). Existing rows keep their original expiry.
3. `CLOSED_EVIDENCE_UNAVAILABLE` disposition written by the existing cleanup job, under the group lock.
4. Queue ordering by `UNSAFE` first.
5. Tests in real PostgreSQL: exact retry after expiry/suppression/journey end/consent withdrawal; conflict on changed reason; quota at the boundary and no debit on retry; concurrent report/suppress/cleanup with the documented lock order; account deletion during intake; closure never overriding a suppression. Update OpenAPI, generated client and the web report control's copy ("Report received" on a recovered retry).

## Out of scope

- Reporting individual people, messages or media (none are public in V1).
- Appeals, operator alerts and supervision (separate `todo.md` items).
- Production retention, backup and legal holds (separate release decision).
- Any change to publication, the V3 privacy contract or ADR 0053/0054.
