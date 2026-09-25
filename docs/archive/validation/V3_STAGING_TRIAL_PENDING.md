> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# V3 real staging trial — remaining work

Status: implementation and isolated staging evaluation authorized; production activation and ADR 0055's privacy contract are not approved. All production V3 and moderator flags remain disabled. This is a handoff for a real trial, separate from the synthetic and simulated-transport evidence in [the implementation record](V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md).

| Dependency | Engineering work | External input or review |
|---|---|---|
| Isolated staging deployment | Apply migrations to the verified staging database, configure and check separate API/publisher/maintenance/web flags, restore/rollback path and backlog monitoring | A staging target and authorized deployment/database access |
| Real identity | Verify separate consumer and moderator Google audiences, origins, sessions and two-account browser/device flows | Google project owner configures OAuth clients, approved origins and admin MFA policy; consenting test accounts/devices |
| Regional relevance | Import and validate a labeled versioned anchor catalog with reviewed Valhalla/Photon region coverage | Regional dataset/service hosting, provenance, licensing and catalog review |
| Moderation | Deploy and exercise the implemented default-off admin queue and grant console, audited review/suppression, unavailable-evidence state, exact retries and finite issue/revoke with real accounts; establish appeals, supervision and cleanup verification in staging | Named operator and grant administrator, out-of-band administrator trust-root bootstrap, admin OAuth/MFA policy, approved roster, supervision and retention review |
| Reliability | Run real backup/restore and failover; verify expiry, suppression and terminal decisions never resurrect; measure publisher/cleanup latency and capacity | Staging operations access, backup policy and alert owners |
| Utility | Measure output coverage, age, incorrect/false-reassuring summaries, repeat-commuter cap, collusion and truthful empty states | Consented Chennai/OMR participants and independent road-condition observations |
| Production decision | Assemble evidence and product/privacy/security reviews | Separate explicit owner acceptance of V3 inference and after-snapshot withdrawal terms; production activation is withheld until then |

The moderator workflow and finite grant administration are implemented for isolated staging behind disabled flags under [ADR 0056](../adr/0056-v3-moderator-staging-boundary.md) and [ADR 0057](../adr/0057-v3-operator-grant-administration.md). Real moderator authentication, named administrator assignment and out-of-band trust-root bootstrap, supervised grant operation, appeals, retention review and operator trial remain open. No earlier V18/V22 consent is migrated; person-level research stays preserved and disconnected.
