> **Archived 2026-09-25.** The ADR 0054 vs 0055 decision (ADR 0065) was overtaken the same day by the direction brief, which archives the ADR 0055 community summary; ADR 0065's principles carry into PRODUCT.md. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Public LIVE privacy decision: ADR 0054 or ADR 0055

Status: **decided by the owner on 2026-09-25 and recorded in [ADR 0065](../../../adr/0065-public-live-v1-policy.md).** Traveller-derived public LIVE stays disabled in production.

## Decision

| Track | Option | Status |
|---|---|---|
| **Production (V1)** | **C**: official/provider LIVE and private Quick Signals only | Decided. Community-derived public LIVE is off and must fail closed. |
| **Experimental candidate** | **B**: ADR 0055 consented community summary | Closed, explicitly consented, feature-flagged **staging** experiment only. Finishing the code does not authorize production; that needs a separate explicit decision after pilot evidence. |
| **Research** | **A**: ADR 0054 person-level differential privacy | Paused and retained for possible future research. No further implementation effort. |

Option C is the V1 production configuration. It is **not** necessarily Routiqo's permanent architecture for community LIVE; ADR 0055 is the primary candidate for that.

ADR 0055 aggregation reduces exposure but **is not anonymity**. Participation can be inferred through known participants, collusion, small groups, multiple accounts or correlated outside observations. No copy, document or comment may say otherwise. A published summary can't be un-published for one person: Stop, Ghost Mode and deletion apply before the publication snapshot and to future summaries.

The pilot targets, the identity constraint (no additional identity collection for Sybil resistance) and the fail-closed flag requirement are set out in ADR 0065. The analysis below is the background the decision was made on.

## Implementation review against the decision (2026-09-25)

Documented, implemented and tested behaviour were compared before any change. Findings:

| Area | Found | Action |
|---|---|---|
| Flag parsing | Spring `havingValue = "true"` ignores case, so `TRUE` enabled backend beans while the web proxy (exact `'true'`) stayed off. The security chain bound the V3 and public-intent flags as `boolean`, which accepts `1/yes/on`, opening the V3 paths in the filter chain while no controller existed | **Fixed.** `FeatureFlags` and `@ConditionalOnExactlyTrue` now gate all 13 community, public-intent and V3 admin beans; the security chains parse strictly. Tests cover missing, empty, mixed-case, padded, numeric and word values, plus an ArchUnit guard against lenient conditions returning |
| Web proxy | Already strict (`=== 'true'`) | Test added proving ambiguous values return 404 without contacting the core API |
| Share recovery | The server marks a handle past its 24-hour candidate lifetime `expired` until cleanup, but the contract enum and browser parser rejected it, so one expired handle made owner recovery unreadable | **Fixed:** contract, generated client and parser accept `expired` |
| Internal records | `Candidate.toString` was unredacted (account, anchor, value, times); nothing logged it | **Fixed:** redacted, with a test |
| API field sets | Already contributor-free | Exact field-set tests for feed, owner, report and moderator responses |
| Logs and telemetry | Constant-message job logs only; no metrics, tracing, MDC or actuator | Source-scan test fails on any parameterised log call or console output in `publiclive`/`moderation` |
| Withdrawal | Before-snapshot tested for Stop, Ghost, completion, restriction and deletion; after-snapshot only for Stop; verification revocation untested | Tests added: verification revocation before the snapshot; Ghost, completion, restriction, deletion and revocation after it (summary unchanged, never redrawn) |
| Threshold | Only partial boundaries | 11 all agreeing, 9 of 12, 78.6% and exactly 80% boundaries tested |
| Quota and isolation | Sequential tests only | Concurrent Shares at the daily limit, concurrent Shares for one window, and the UTC day boundary tested in PostgreSQL |
| Sybil | Verification is required at Share and snapshot; one active verified account per person (`person_ref` unique index) | Tests: 20 unverified agreeing accounts produce nothing; a second active verification for the same person is refused by the database |
| Truthful copy | Share disclosure already says participation may be inferred and prepared summaries may include the report; no UI claims anonymity | No change. `docs/product/ROUTIQO_MASTER_CONTEXT.md` lists "Anonymous aggregate speeds" as a product idea; flagged for the owner, not edited |

Known risks that remain (not fixable by tests):
- **Coordinated verified accounts.** Ten colluding verified people in a 12-account window can still publish a wrong summary. Verification, quotas, reporting and moderation limit this; the threshold alone does not.
- **The daily quota is per account, not per person.** A person who deletes and is re-verified gets a new account with a fresh daily quota. Re-verification is dual human review, which bounds this in practice.
- **After-snapshot withdrawal.** Removing a contribution from a published summary is impossible by design, and the copy says so.


## Background: the question

Routiqo had to choose the privacy contract for **traveller-derived public LIVE**: what other travellers see, built from people's Quick Signals. Official provider alerts (ADR 0050) and private Quick Signals are not affected by this choice.

There are three options:

| | A. ADR 0054: person-level differential privacy | B. ADR 0055: consented community summary (V3) | C. Neither for V1 |
|---|---|---|---|
| What travellers see | A traffic condition at a coarse road anchor per 5-minute window, only when noise-protected support is very high | A traffic condition at a coarse road anchor per 5-minute window when enough accounts agree | Official alerts only; own private signals |
| Privacy claim | Mathematical: `(ε = ln 2, δ ≈ 3.2 × 10⁻⁷)` for the whole 30-day pilot, **if** every assumption holds | None mathematical. Aggregation with residual inference risk, stated honestly | Nothing new is published |
| Built? | Research only: sampler, pilot claim and frozen Share foundation, all disconnected | Implemented end to end behind off flags, with moderation and reporting (ADR 0056/0057/0064) | Provider pilot implemented behind its own flag |
| Open blockers | Four P1 review findings, including one called a blocking protocol flaw | Real staging, consented density/accuracy data, and your explicit acceptance of the weaker contract | Provider staging verification |
| Useful output? | Structurally very rare (see below) | Synthetic model: 0% at 8 accounts/window, 89% at 12 honest accounts; real data unknown | Only when authorities issue alerts |
| Can users stop sharing? | **No** after Share commits: Stop, Ghost Mode and deletion cannot remove it | Yes until the publication snapshot; afterwards the summary may still include it until it expires (10 min after its window) | N/A |
| Reversible for Routiqo? | Consumed pilot slots and published outputs are permanent for the pilot | Flags off stops serving; each summary expires within 15 minutes of its window starting; outside copies remain | Fully |

## Option A: ADR 0054 (person-level differential privacy)

**Strengths**
- It is the strongest claim available: a published summary barely changes whether any one person took part.
- It is the direction you originally chose for public LIVE.

**Why it cannot ship in V1**
1. **A blocking protocol flaw with no known fix.** Review found that one person's in-flight Share can delay the window seal past the publication deadline. That changes what *everyone* sees, whether the summary is late or missing, in a way noise does not cover. The ADR itself calls this "a blocking protocol finding, not merely an unimplemented optimization". Timeouts, `NOWAIT`, `SKIP LOCKED` or snapshots do not close it.
2. **It needs a stable person identity that doesn't exist.** The one-report-per-person bound has to survive account deletion and re-verification. That requires an independently operated person-reference authority, which Routiqo does not have.
3. **Irreversible sharing conflicts with product rules.** After Share, Stop, Ghost Mode and account deletion cannot remove the input. `AGENTS.md` requires Ghost Mode to stop publishing and remove discoverable presence everywhere. Existing UI copy promises this, so every screen would need to change.
4. **Its usefulness is close to zero by design.** Each verified person gets **one** public report for the entire 30-day pilot. A condition appears only if roughly 22 people each spend their single monthly report on the same value, at the same anchor, in the same 5-minute window. Commuters who report daily could contribute once a month. The ADR expects "little output in sparse windows"; the structure suggests little output in almost any window.
5. **It also needs** independent mathematical review of the sampler and the full observable transcript, legal review of the retention exception, and real density data.

**Cost to finish:** unknown. Item 1 needs a research result, not engineering time.

## Option B: ADR 0055 (consented community summary, V3)

**What it does:** a traveller deliberately shares one accepted private Quick Signal for this purpose. It is accepted for consideration only. A publisher takes one PostgreSQL snapshot per anchor and window. It publishes one coarse condition when at least **12** eligible accounts are present and one value has at least **10** supporters and **80%** agreement; otherwise the result is `NO_OUTPUT`. There are no counts, names, positions or times. Each account can submit once per window and at most 12 times per day.

**Strengths**
- **Built and tested:** Share, Stop and recovery, the publisher, the reader, reporting, moderator queue and grants, cleanup, contracts and the web UI. Every flag is off.
- **Withdrawal works in the way people expect,** up to a clearly defined point. Stop, Ghost Mode, completion, deletion or restriction before the snapshot excludes the report; afterwards it affects only future summaries.
- **Short-lived output:** a summary serves until 10 minutes after its window. Source data expires within 24 hours; reporter data after 7 days (ADR 0064).
- **Moderation exists:** reports, a UNSAFE-first queue and audited suppression.

**Risks you would be accepting**
1. **Participation can be inferred.** Someone who knows 11 of the 12 contributors, controls colluding accounts, or watches the same windows repeatedly can infer that a particular person took part. The 12/10/80% rule is a quality and abuse rule, not a privacy guarantee.
2. **Coordinated false reports.** In the synthetic model, 10 coordinated false accounts among 12 produced wrong summaries every time. Mitigations are verification, quotas, reporting and moderation, not mathematics.
3. **Withdrawal can be too late.** A Stop after the snapshot can't remove a summary already being prepared, and copies others saved can't be recalled.
4. **Density is unknown.** Below about 12 honest accounts per anchor and window, nothing appears. Chennai/OMR density is unmeasured.

**Remaining gates** (from `docs/validation/V3_PRODUCTION_DECISION_CHECKLIST.md`):
- your explicit acceptance of the disclosure;
- product/privacy and security wording review;
- a real authenticated staging run;
- failover and restore tests;
- device QA;
- consented density and accuracy data;
- operations retention approval.

## Option C: neither for V1

Launch V1 with utility that doesn't depend on crowd publication: planning, active journeys, journals, commute summaries, **official provider alerts** and **private** Quick Signals. Traveller-derived public LIVE stays off.

- **Strength:** no new privacy risk. It matches "utility before participation and community", and nothing is irreversible.
- **Cost:** no community traffic at launch. The LIVE list shows only official alerts, which are district-wide rather than per road.

## Recommendation (accepted with changes)

The recommendation was C for launch, B as a staging pilot and A paused. The owner accepted it and strengthened the pilot bars. The binding versions are in ADR 0065:
- correctness of at least 95% (up from 90%);
- 0% dangerous false reassurance, measured separately from false congestion;
- freshness of at least 95%;
- a 100% passing withdrawal suite;
- user understanding of at least 90%;
- an adversarial and Sybil suite;
- API and telemetry leakage checks;
- performance measurements.

The owner also made two things explicit: Sybil resistance must not come from collecting more identity data, and C is the V1 configuration, not the permanent community LIVE design.
