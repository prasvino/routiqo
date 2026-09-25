> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# ADR 0054 adversarial design review — 2026-09-23

Disposition: **not approved for implementation as a public release protocol or for public activation**. An independent engineering review challenged ADR 0054 against ADRs 0051–0053, the current migrations/services, and the full proposed public transcript. The isolated sparse-histogram arithmetic was not rejected; it is conditional on protocol properties that do not yet hold. The reviewer did not inspect a public publisher or reader because neither exists. Specialist mathematical, product/privacy, legal and final implementation reviews remain separate gates.

| Severity | Finding | Required evidence to close |
|---|---|---|
| P1 | A transaction can check time before a five-minute close and commit after it. A seal or worker deadline can depend on another person's lock wait or workload, changing more than that person's contribution. A generic `NO_RELEASE` on a missed deadline can itself disclose input. | Define one durable linearization rule for Share admission, commit, seal and decision. Prove a neighboring person's action cannot alter another person's inclusion or observable deadline outcome; verify with multi-replica and stalled-transaction tests. |
| P1 | V17 has one active account per `person_ref`, but account deletion cascades verification. A later review can assign the same natural person a new reference and defeat V21's one-person pilot limit. | Operate and audit stable person assignment, correction/merge, deletion and re-verification through the pilot retention horizon; prove the claim survives those transitions. |
| P1 | Irreversible Share conflicts with current private V18 ACTIVE/STOPPED intent, browser Stop copy and operative Ghost/deletion wording. | Obtain product/privacy and retention approval, introduce versioned Share and owner recovery, and test explicit informed consent and old-client denial. Never migrate an old `ACTIVE` intent into a frozen input. |
| P1 | Block, report, rate-limit and emergency modes are not a fully specified delivery mechanism. A whole-pilot shutdown triggered by one report is still source-dependent. | Define exact transition and trigger rules and analyze their joint status, body and timing transcript; test source-dependent failure attempts and captured offline output. |
| P2, corrected in draft | Winner selection after thresholding was ambiguous. Reading original counts would invalidate post-processing. | ADR 0054 and the protocol spec now allow a condition only when exactly one value survives, using only that survivor set and frozen manifest. Verify the eventual implementation. |
| P2, corrected in draft | Fixed expiry stops serving but cannot erase an observer's captured output. | ADR 0054 now says captured output persists and may compose with later pilots. Later-pilot privacy budgeting remains a separate decision. |

Utility is also unproven. A verified person spends their sole 30-day public slot on one key; density analysis must model that depletion and the risk of wrong traffic reports. The documented rates for 10 and 12 agreeing reports are far too low to assume the pilot will provide a useful Live Moment list.

An additional independent feasibility analysis constructed a barrier counterexample: a target Share held across the release deadline can turn a likely row from many other reports into a late row or generic `NO_RELEASE`. This violates the proposed whole-transcript one-person bound even if the histogram sampler is correct. PostgreSQL's logical snapshot functions could freeze a private experimental input set, but the snapshot's acquisition is an actual cut that can move with workload; it is not proof of a fixed wall-clock public release. Transaction timeout and timestamp checks do not close this finding. See [PostgreSQL time functions](https://www.postgresql.org/docs/current/functions-datetime.html) and [snapshot functions](https://www.postgresql.org/docs/current/functions-info.html#FUNCTIONS-PG-SNAPSHOT). No publisher should be built on this candidate until a defensible operational model or a different mechanism resolves the channel.

No public output, feature flag or Share semantics were activated by this review. ADR 0054 remains a candidate; item 1 in `todo.md` remains unchecked.

## Internal V2 foundation review

The later V22 implementation pass added a disconnected internal Share store and service. A separate independent code review found no transport or public activation. It initially requested changes before approving even this foundation:

- **P1, closed for the foundation:** the initial `verification.current` read did not lock its verification row. A reviewer account deletion could cascade-delete that row and commit before Share while Share used stale authority. `currentForFrozenShare` now holds a PostgreSQL `FOR SHARE` lock through the owned-journey transaction. Real account/journey/verification/storage tests cover Share-first and deletion-first ordering.
- **P2, closed for the foundation:** pilot times were not constrained to five-minute boundaries, potentially enlarging the fixed key universe. V22 now rejects unaligned manifest provisioning; the service checks that the whole receipt window lies inside the immutable pilot interval. Tests cover unaligned provisioning and final included/excluded windows.
- **Remaining test gap:** the full service has not yet been raced against every Stop, Ghost Mode, completion and moderation ordering. Those are required before an active V2 Share action.

The independent code reviewer accepted the corrected disconnected foundation and found no new P1/P2. These code findings are separate from the four unresolved public-protocol P1 findings above. Public publication remains prohibited.

ADR 0055 was documented later as a proposed consented community-summary alternative. This review did not assess or approve that different privacy contract, its PostgreSQL snapshot implementation, or its public output.
