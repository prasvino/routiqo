# Public LIVE privacy decision: ADR 0054 or ADR 0055

Status: **decision document for the owner, 2026-09-25. Nothing here is approved.** It changes no code, flag or ADR status. Traveller-derived public LIVE stays disabled in production until the owner records a decision below.

## The decision

Routiqo must choose the privacy contract for **traveller-derived public LIVE**: what other travellers see, built from people's Quick Signals. Official provider alerts (ADR 0050) and private Quick Signals are not affected by this choice.

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

## Recommendation

**Choose C for the V1 launch. Run B as a closed, consented staging pilot to gather evidence. Pause A.**

1. **C now.** V1 isn't blocked by either privacy contract, so the launch shouldn't wait on this decision. Ship with provider alerts and private signals.
2. **B as a staging pilot, not production.** Recruit consenting testers on the Chennai/OMR corridor and measure real density, coverage, wrong-value and false-reassurance rates, and output age. Use the existing V3 flags in staging only. Then decide on production with evidence, against go/no-go criteria you set in advance. Proposed starting points, for you to adjust:

   | Criterion | Proposed bar |
   |---|---|
   | Coverage | A summary appears in at least 20% of peak-hour windows on pilot anchors |
   | Accuracy | At most 10% of shown summaries disagree with independent ground truth |
   | False reassurance | Zero summaries showing "moving" while ground truth shows stopped traffic |
   | Abuse | A red-team test with coordinated accounts fails to publish a wrong summary without triggering review |
   | Understanding | Pilot users correctly explain in a short survey that participation may be inferable |

   If B fails the bars, keep public LIVE off. Do not lower the 12/10/80% rule to make it look busy.
3. **Pause A, don't reject it.** Keep the research code and documents, disconnected as they are now. Revisit only if the window-seal flaw gets a published solution and a person-identity authority becomes available.

Why not choose B for production now: its contract is honest and it's built, but accepting participation-inference risk before anyone knows whether it produces useful, accurate output would take on privacy risk without proven benefit. The pilot answers that cheaply and reversibly.

## Decisions I need from you

1. **Choose:** C for launch, B as a staging pilot, A paused, or another combination.
2. **If B is piloted:** approve or adjust the five go/no-go bars above.
3. **If B is piloted:** confirm the pilot disclosure wording in `COMMUNITY_TRAFFIC_SUMMARY_SPEC.md` ("Someone with other information may still infer that you contributed…") is acceptable for consenting testers.
4. **Confirm:** whether A is paused (kept, disconnected) or formally rejected.

Once you decide, I'll record it as an ADR, update ADR 0054/0055 statuses, `todo.md` and the decision checklist, and plan the staging pilot if B is chosen.
