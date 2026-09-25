> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# V3 community traffic utility exploration — 2026-09-23

Status: **synthetic staging model only**. No Routiqo production or consented pilot reports, observed traffic ground truth, actual publisher latency, or regional density were used. Reproduce with `node scripts/v3-community-utility.mjs` from the repository root (fixed seed `0x5eed1234`, 10,000 independent synthetic windows per scenario). This explores the proposed ADR 0055 12 eligible accounts / unique 10 agreeing / at least 80% rule; it is not a privacy analysis or a release validation.

The model fixes the true structured traffic value to index 0. Each honest account independently reports it with the stated probability; otherwise it uniformly picks one of three wrong values. Malicious accounts, where present, all report the same wrong value. Every account contributes at most one candidate per window. It assumes every candidate survives consent/authority and the publication snapshot. Those simplifying assumptions make the results unsuitable as predicted Routiqo coverage or accuracy.

| Synthetic scenario | Accounts/window | Honest correctness | Coordinated wrong accounts | Visible windows | Wrong among visible |
|---|---:|---:|---:|---:|---:|
| Low density | 8 | 90% | 0 | 0% | N/A |
| Medium density | 12 | 90% | 0 | 89.00% | 0% |
| High density, mixed reports | 25 | 65% | 0 | 8.49% | 0% |
| High density, consistent reports | 25 | 90% | 0 | 96.71% | 0% |
| High density with five coordinated wrong accounts | 25 | 90% among other 20 | 5 | 12.68% | 0% |
| Twelve accounts with ten coordinated wrong reports | 12 | 100% among other 2 | 10 | 100% | 100% |

The final row demonstrates that agreement is not factual accuracy. Five coordinated wrong accounts mainly suppress output in this particular model; other coordination and distribution choices may be worse. Zero wrong results in the honest synthetic rows is a consequence of the model and strict selector, not proof of real-world accuracy. Repeated windows and colluding accounts can still permit participation inference even where the output is correct.

For a separate deterministic daily-cap exercise, 15 commuting accounts each try to share in 24 consecutive windows on one UTC day. A 12-success/account/day cap permits all 15 in the first 12 windows and zero in the last 12. Even if their reports agree, at most 12 windows can reach the proposed 12-account floor. Real retry, loss, eligibility and timing effects would reduce this further.

Output age and latency are **not measured** by this model. The staging target of processing shortly after window close and never serving after close plus ten minutes needs an instrumented PostgreSQL/browser run. Production utility needs consented Chennai/OMR density, repeated-commuter and cap behavior, independent road-condition ground truth, malicious/conflicting-report analysis, coverage, wrong-value rate, output age, and user understanding of empty results. Do not lower the rule or claim a public moment is safe because a synthetic scenario looks sparse or accurate.
