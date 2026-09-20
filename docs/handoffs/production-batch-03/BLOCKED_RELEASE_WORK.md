# Release work excluded from batch 03

Current references: `todo.md`, `docs/quality/BUILD_STATUS.md`,
`docs/features/live/COHORT_PUBLICATION_DESIGN.md`,
`docs/features/live/ROUTIQO_LIVE_SPEC.md`, and `docs/security/THREAT_MODEL.md`.

| Area                                     | Prerequisite / reason excluded                                                                                                     | Next required work                                                                                                   |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Public LIVE                              | Privacy/publication contract remains unapproved; collusion, query inference and revocation are unresolved.                         | Reviewed measurable contract, ADR and adversarial tests before projection or public reads/list.                      |
| Reports/moderation/safe delivery         | Evidence authority, replay after revocation, retention and lock ordering need settled design; operator controls remain incomplete. | Separate security-reviewed design and implementation; strong admin auth, case scope and operational supervision.     |
| Google sign-in                           | Account-owner OAuth configuration and secrets are required for real verification.                                                  | Configure through secret storage, then staging lifecycle QA.                                                         |
| Regional open-source maps                | Region/data/catalog decisions and controlled Valhalla/Photon/tile services need provisioning and verification.                     | Operator setup plus coverage, egress and attribution tests; no Mapbox token requirement.                             |
| Android                                  | Native transport/session/resource integration is larger engineering work; device environment must be checked.                      | Separate implementation phase and current Android doctor/device evidence. Not all native work is externally blocked. |
| Cross-device authenticated QA            | Controlled local tests cannot establish actual multi-device recovery or real sign-in behavior.                                     | Configured staging identity plus browser/device lifecycle, conflict and network-failure checks.                      |
| Production rollout                       | Infrastructure, secrets, domain/TLS, backups/rollback, monitoring and operated cleanup are external/operational gates.             | Operator provisioning, release-commit CI, staging evidence and gradual pilot approval.                               |
| Later offline navigation/social features | Persistent routes, downloads, rerouting, rooms, push/media/AI are larger scoped phases.                                            | Reviewed feature/privacy/platform designs; do not extend this small UI batch.                                        |

This is a dependency inventory, not permission to enable features or provision services.
See the more detailed [batch 02 release inventory](../production-batch-02/BLOCKED_RELEASE_WORK.md) if present; canonical documents above remain authoritative.
Do not close release TODOs or update canonical status files as part of these delegated tasks.
