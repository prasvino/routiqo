# Release work excluded from batch 04

Canonical status remains `todo.md` and `docs/quality/BUILD_STATUS.md`. This is a dependency inventory, not approval to activate features.

| Area                                  | Required next step                                                                                                                                                                                         |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Public LIVE                           | Approve measurable publication privacy, collusion/repeated-query assumptions, contribution eligibility and revocation protocol; record/review ADR and adversarial tests before projection or public reads. |
| Reporting/moderation                  | Resolve evidence authority, exact replay, retention and transaction order; complete separately reviewed operator auth, grant/case scope, supervision and safe delivery.                                    |
| Actual LIVE list                      | Depends on approved publication and safety gates; private controls are not a public feed.                                                                                                                  |
| Google OAuth                          | Owner configuration/secrets and real staging sign-in/session/deletion verification.                                                                                                                        |
| Open-source regional maps             | Reviewed pilot dataset/catalog plus controlled Valhalla/Photon/tile provisioning, coverage, attribution and egress verification.                                                                           |
| Android                               | Separate native transport/session/resource integration, current tooling checks and real device QA. Some native engineering is unblocked but too coupled for this batch.                                    |
| Authenticated multi-device/history QA | Real configured identity/staging/device access; controlled tests do not establish those release behaviors.                                                                                                 |
| Operations and rollout                | Production infrastructure/secrets/domain/TLS, backup/rollback, cleanup capacity, monitoring, release-commit CI and gradual pilot evidence.                                                                 |
| Later navigation/social features      | Separate designs for persisted routes, offline downloads/rerouting, rooms, push/media and AI.                                                                                                              |

Consult `docs/features/live/COHORT_PUBLICATION_DESIGN.md`,
`docs/features/live/ROUTIQO_LIVE_SPEC.md`, `docs/security/THREAT_MODEL.md`
and the earlier [release dependency inventory](../production-batch-03/BLOCKED_RELEASE_WORK.md)
for context. Preserve all closed gates; do not request credentials or provision services for this batch.
