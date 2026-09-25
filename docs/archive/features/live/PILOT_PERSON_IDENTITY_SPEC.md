> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Stable person identity for the public LIVE pilot

Status: required design and operational gate; not implemented. V17 authenticates a currently reviewed account and returns an opaque `person_ref`, but its database uniqueness constraint applies only to active rows. Account deletion cascades those rows. V21 preserves a pilot claim for a reference, not the fact that two references cannot belong to one natural person. The ADR 0053 person-level sensitivity bound cannot be claimed until this gap is closed.

## Authority contract

- An independently authenticated, dual-reviewed operator workflow assigns one canonical opaque person reference to one natural person across accounts, deletion, recovery and re-verification for the whole pilot and approved retention period. The same person may never obtain a fresh reference merely by using a new Google account, document, device or reviewer. Google subject or client location is not person proof.
- Identity evidence and any lookup token stay in a separately access-controlled operator system. The core LIVE signal, route, frozen-input, claim and projection tables receive only the opaque reference and revision. Do not put document numbers, images, names, biometric templates or raw matching keys in those tables or logs.
- Before any V2 Share, the core authority must verify an active account-to-canonical-person binding and reject an unknown, disputed, expired or concurrently changing binding. A reviewer action must serialize with admission or cause admission to fail closed. The pilot-wide `(pilot_id, person_ref)` claim is then nonrefundable across account deletion.
- A mistaken duplicate reference is a privacy incident. Do not silently merge two already claimed references and continue publication under the one-person bound; stop traveller output, record the affected pilot and seek independent analysis. Correction before any Share requires dual review and an immutable audit trail.
- Account deletion removes ordinary account and private journey/signal data. The minimal canonical-person budget record remains only for the explicitly disclosed pilot/retention exception; the operator system must retain enough restricted linkage to prevent re-entry with a new reference until that exception ends. The exact retention, appeal and deletion policy needs product/legal approval before enrollment.

## Operator and technical acceptance

1. Strong, separate administrator authentication and case-scoped grants; no consumer can provision, merge or inspect references. Two different authorized reviewers confirm assignment and correction; reviewers cannot review themselves.
2. A person re-enrolling after account deletion receives the same canonical reference, or remains ineligible. Concurrent enrollments on separate accounts cannot create two active independent pilot identities. A contested identity remains ineligible until resolved.
3. V2 Share tests cover same person on two accounts, deletion and re-entry, expired/revoked review, concurrent correction versus Share, reviewer error, rollback, appeal and unavailable operator authority. The pilot claim survives account deletion and cannot be refunded by a correction.
4. Operators have bounded, redacted audit and appeal access, approved evidence retention, capacity/failure alerts and a documented incident shutdown. No raw identity evidence is copied into the public LIVE domain.

Until this is operated and independently reviewed, the project may test internal V2 Share with synthetic fixed person references but must not claim a natural-person privacy guarantee or enable traveller publication.
