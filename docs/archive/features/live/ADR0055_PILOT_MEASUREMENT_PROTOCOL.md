> **Archived 2026-09-25.** Measurement protocol for the ADR 0055 community-summary pilot, which the direction brief archives. Its correctness and dangerous-false-reassurance ideas may inform Spot signal quality measurement later. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# ADR 0055 pilot measurement protocol

Status: **proposed for owner approval, 2026-09-25.** It defines how the ADR 0065 pilot targets are measured before any consenting tester joins. Nothing here enables the pilot. The community LIVE flags stay off outside the closed staging experiment, and production still needs a separate explicit decision.

## Principles

- **Measure without new tracking.** Metrics come from the aggregate tables (decisions and projections) plus an independent reference. No per-person display log, location trail or contributor link is created for measurement. Reference observers record conditions per anchor and window, never per traveller.
- **Sparse output beats wrong output.** Coverage is reported, but it never justifies lowering the 12/10/80% rule.
- **Separate the harms.** Dangerous false reassurance and false congestion are counted separately. Neither is averaged into overall correctness.

## Reference traffic state

Each pilot anchor and 5-minute window gets one reference class from an independent source that does not use Routiqo reports:

| Option | Notes |
|---|---|
| **Trained observers at pilot anchors (recommended primary)** | Anchor/window granularity only; no traveller identities. Needs a schedule and two observers for a calibration sample. |
| **Licensed road-speed feed (recommended cross-check)** | An external dependency with cost and terms. Needs an ADR and your approval before any integration. |

**Reference classes**, using speed as a share of free-flow speed at the anchor:

| Class | Free-flow share |
|---|---|
| MOVING | at least 70% |
| SLOW | 40–70% |
| VERY_SLOW | 15–40% |
| STOPPED | below 15%, or a queue not advancing for at least 60 s |

Observers without speed data use the written visual definitions of each class. A 10% calibration sample is double-observed, and agreement below 90% pauses measurement until the definitions are fixed.

## Metrics

Shown value `S` is the published summary for an anchor/window. Reference `R` is that window's class. The ordering is MOVING < SLOW < VERY_SLOW < STOPPED.

| Metric | Definition | Target (ADR 0065) |
|---|---|---|
| **Coverage** | Eligible peak windows with a published summary ÷ eligible peak windows. *Eligible*: a pilot anchor, inside agreed peak hours (proposed 07:30–10:30 and 17:00–20:30 IST, weekdays), with a reference available | at least 20% |
| **Correctness** | Published summaries with `S = R` ÷ published summaries with a reference. Exact match only; one-class errors are counted as incorrect | at least 95% |
| **Dangerous false reassurance (DFR)** | Published summaries where `S` is **two or more classes lighter** than `R`. For example, MOVING while VERY_SLOW or STOPPED, or SLOW while STOPPED | 0% |
| **False congestion (FC)** | Published summaries where `S` is two or more classes heavier than `R`. Reported separately | Reported; no target yet |
| **Freshness** | Published summaries decided within 60 s after their window ends ÷ published summaries. Serving already stops 10 minutes after the window by construction (server filter plus client monotonic deadline, both tested); this measures how fresh a summary is when it first appears | at least 95% |
| **Latency** (P2) | Share request p50/p95; decision latency after window end; feed read p95; Stop and Ghost propagation to the next snapshot; cleanup backlog | Measured, with targets set after the first week |

**Comprehension** is measured with a short survey after participants' first week. It has six statements matching ADR 0065: sharing is optional; Stop and Ghost semantics; summaries are aggregated; no public identity; no absolute anonymity; published summaries may not be reversible. The target is at least 90% correct per statement.

## Evidence needed to support a production proposal

A point estimate on a small sample can't show these targets. The minimum evidence proposed:

| Target | Minimum evidence |
|---|---|
| Correctness at least 95% | At least **300** published summaries with a reference, with at least **293** correct (Wilson 95% lower bound at least 95%) |
| DFR 0% | **Zero** DFR events in at least **300** published summaries. That shows the true rate is at most about 1% (rule of three); a smaller sample can't support a claim near 0% |
| Comprehension at least 90% | At least **100** participants, with at least **96** correct per statement for a 95% lower bound of at least 90%. With 50 participants the bound needs 50 of 50 |
| Coverage at least 20% | The point estimate over the full pilot, reported with its interval |

If density keeps the sample below these sizes, the pilot reports "insufficient evidence", not success.

## Stop conditions during the pilot

- **Any DFR event:** turn off the publisher flag for the pilot, review the window (inputs are gone after 24 hours, so review promptly), then decide whether to resume.
- **Correctness below 90%** over any rolling 100 summaries: pause and review.
- **Any sign of coordinated reporting** (for example, repeated synchronized reports from the same small set of accounts across windows): pause and review. The per-person bounds are in the adversarial suite below.

## Adversarial evidence already in place (real PostgreSQL, not a staging red team)

| Attack | Result | Test |
|---|---|---|
| Unverified accounts flood a window (20 agreeing) | Nothing published | `unverifiedAccountsCannotManufactureASummaryHoweverManyAgree` |
| Colluding verified accounts | At least 10 agreeing at 80% or more are required; 3 honest dissenters block 10 colluders; unverified sock puppets don't count | `colludingVerifiedAccountsNeedTheFullThresholdAndAreBlockedByHonestDissent` |
| One person with two accounts after re-verification | At most one vote per person in a window (one active verification per person, checked at the snapshot) | `reVerificationChurnGivesOnePersonAtMostOneVoteInAWindow`, `onePersonCannotHoldTwoActiveVerifiedAccounts` |
| Late or dissenting reports after publication | Can't reopen or flip a published window | `aLateDissentingCandidateCannotReopenOrFlipAPublishedWindow` |
| Window-boundary timing | The next window's reports never count toward the closing window; the database rejects a report timed outside its window | `reportsFromTheNextWindowNeverCountTowardTheClosingWindow`, `theDatabaseRejectsACandidateTimedOutsideItsWindow` |
| Concurrent Shares, quota bypass, one account per window | Exactly one admitted at each limit | candidate-store concurrency tests |

**Still open:**
- **Re-verification churn resets the daily quota.** The quota is keyed by account. A person who deletes their account and is re-verified gets a new account with a fresh 12-per-day quota. Keying the daily debit by the existing `person_ref` would close this without collecting any new identity data. That's a schema change, proposed for your approval rather than made here.
- **A staging red-team run** with real accounts and devices.

## Decisions needed

1. Approve the reference approach: observers as primary, with a licensed speed feed as cross-check only after its own ADR.
2. Approve the class bands, the DFR/FC definitions and the peak hours.
3. Approve the minimum-evidence rule above, or set a different one.
4. Decide whether to key the daily quota by person (`person_ref`) to close re-verification churn.
