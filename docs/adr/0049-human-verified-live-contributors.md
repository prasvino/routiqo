# ADR 0049: Human-verified contributor authority for public LIVE

Date: 2026-09-23
Status: accepted eligibility direction; public publication remains closed

> **Status update (2026-09-25):** Superseded by the direction brief. Human-verified person authority existed for the public-LIVE/DP protocol, which is archived. The open question of whether Spot passage requires sign-in may later want a lighter version. See [PRODUCT.md](../PRODUCT.md).

## Decision

Traveller evidence is eligible for a future public LIVE protocol only after a human review has established one active pseudonymous person reference for the contributor. Verification is an explicit, revocable and expiring authority, separate from Google login, journey ownership, route matching, private consent, and the current durable suspension state. No missing or stale verification state defaults to eligible.

Routiqo's route, signal and publication tables must not contain identity documents or a raw personal identifier. The verification workflow owns any identity-review material under a separate bounded retention and access policy. The LIVE boundary receives only an opaque person reference, verification revision, state and expiry. One person reference may be active for at most one account; account reassignment requires revocation of the old authority first. Account deletion and verification revocation invalidate future publication. Human review does not prove physical presence or non-collusion.

## Authority and operations

A future mutation path requires strong operator authentication, a finite verification-specific grant, case scope, dual-control or equivalent independent review for issuance, exact revision checks, bounded action quota, minimal audit, appeals and expiry maintenance. The existing internal moderation grant covers only restriction/restore and cannot authorize identity verification. A trusted internal method without this operator boundary is not sufficient for production activation.

The current `ContributorAssessment.ASSESSED` domain value is not durable. `live_contribution_restriction` persists only suspension, so it must never be interpreted as a verified-person registry. Verification and suspension are separate: a verified but suspended person is ineligible. Disabling, expiry or deletion never restores eligibility by replaying an older assessment.

## Publication limit

This decision narrows account farming; it does not close ADR 0038's threshold-minus-one attack when distinct verified people collude or an observer knows the other reports. Publication needs a separately approved bounded-adversary protocol, fixed query surface and windows, block/withdrawal semantics, current-authority checks and entire-output adversarial review. Until those decisions and the operator workflow exist, no traveller-derived public LIVE endpoint or feature flag may be enabled.

## Acceptance evidence

Prove one active account per person under concurrent issuance and reassignment, fail-closed reads for missing/expired/revoked authority, suspension dominance, exact revision and audit behavior, account deletion, concurrent acceptance/revocation, multi-replica agreement, and no sensitive identity material in LIVE data, logs or public DTOs. Separately test verified-person collusion against the proposed publication protocol. These tests do not substitute for independent privacy review or operational verification.
