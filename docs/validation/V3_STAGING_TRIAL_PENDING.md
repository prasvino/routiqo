# V3 real staging trial — remaining work

Status: implementation and isolated staging evaluation authorized; production activation and ADR 0055's privacy contract are not approved. All production V3 and moderator flags remain disabled. This is a handoff for a real trial, separate from the synthetic and simulated-transport evidence in [the implementation record](V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md).

| Dependency | Engineering work | External input or review |
|---|---|---|
| Isolated staging deployment | Apply migrations to the verified staging database, configure and check separate API/publisher/maintenance/web flags, restore/rollback path and backlog monitoring | A staging target and authorized deployment/database access |
| Real identity | Verify separate consumer and moderator Google audiences, origins, sessions and two-account browser/device flows | Google project owner configures OAuth clients, approved origins and admin MFA policy; consenting test accounts/devices |
| Regional relevance | Import and validate a labeled versioned anchor catalog with reviewed Valhalla/Photon region coverage | Regional dataset/service hosting, provenance, licensing and catalog review |
| Moderation | Deploy and exercise the implemented default-off admin queue, audited review/suppression, unavailable-evidence state and exact retry with real accounts; establish an appeals path and verify cleanup in staging | Named operator/grant administrator, admin OAuth and MFA policy, controlled finite grants, supervision and retention review |
| Reliability | Run real backup/restore and failover; verify expiry, suppression and terminal decisions never resurrect; measure publisher/cleanup latency and capacity | Staging operations access, backup policy and alert owners |
| Utility | Measure output coverage, age, incorrect/false-reassuring summaries, repeat-commuter cap, collusion and truthful empty states | Consented Chennai/OMR participants and independent road-condition observations |
| Production decision | Assemble evidence and product/privacy/security reviews | Separate explicit owner acceptance of V3 inference and after-snapshot withdrawal terms; production activation is withheld until then |

The operator workflow is implemented for isolated staging behind disabled flags under its [feature contract](../features/live/V3_MODERATOR_WORKFLOW_SPEC.md) and [ADR](../adr/0056-v3-moderator-staging-boundary.md). Real moderator authentication, grant administration, appeals, retention review and operator trial remain open. No earlier V18/V22 consent is migrated; person-level research stays preserved and disconnected.
