> **Archived 2026-09-25.** Threshold, cohort, differential-privacy or V3 community-summary material. The pilot publishes no aggregate traveller output; revisit only if aggregate counts return after a separate privacy review. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# V3 moderator workflow — isolated staging runbook

Status: implemented for isolated staging evaluation under [ADR 0056](../adr/0056-v3-moderator-staging-boundary.md). V3 production publication and moderator access remain disabled pending the [decision checklist](../validation/V3_PRODUCTION_DECISION_CHECKLIST.md).

## Prerequisites

Use a separate HTTPS admin origin and Google OAuth client audience from the consumer site. The Google project owner must authorize that origin and enforce the reviewed operator MFA policy. The operator must already have an enabled Routiqo account under the same Google subject; admin sign-in never creates one. Configure the backend `persistence`, `web-auth` and `google-auth` profiles and apply migrations through V26 to the verified isolated staging database. Keep consumer and admin sessions on different origins and cookie namespaces.

The backend requires `ROUTIQO_V3_ADMIN_ENABLED=true`, `ROUTIQO_ADMIN_ORIGIN` and `ROUTIQO_ADMIN_GOOGLE_CLIENT_ID`. The admin Next.js process separately requires those three values plus `ROUTIQO_ADMIN_API_ORIGIN`, a fixed backend origin. Review these values before enabling; `.env.example` files keep the flag false. The V3 consumer API, publisher, maintenance and web flags remain independent as described in [local setup](LOCAL_SETUP.md#v3-community-traffic-staging-evaluation-adr-0055). No flag is changed by this runbook.

## Finite operator grants

The [grant administration runbook](V3_OPERATOR_GRANTS_STAGING_RUNBOOK.md) covers the separately flagged V3 grant console, its out-of-band `traffic_grant_admin` trust root, issue/revoke checks and audit. Issue `traffic_review` for queue reads/dismissal and `traffic_suppress` only to operators authorized for takedown. No grant is seeded in source code, a migration or application startup. Real OAuth/MFA, named operators and supervised grant operation remain staging prerequisites.

## Trial checks

1. With the admin flag off, verify admin routes expose no records. With it on, verify consumer cookies or the consumer Google audience cannot sign in; a valid admin identity without a current grant is denied.
2. Sign in through the admin site, read the bounded queue, and confirm it shows only coarse canonical projection details and grouped reasons. A report whose projection expired or was cleaned is `EVIDENCE_UNAVAILABLE`; it cannot be dismissed as reviewed or suppressed.
3. Dismiss a still-investigable group, then submit a later authorized report and verify the group reopens. Suppress a different still-serving summary and verify subsequent consumer reads omit it while its terminal publication decision stays unchanged. Exercise exact retry after an uncertain response and conflicting retry.
4. Revoke the grant and session, then verify queue and action denial. Run retention cleanup and a backup/restore exercise that does not resurrect suppressed or expired summaries. Inspect only redacted operational metrics and bounded audit records.

## Rollback and remaining gates

Disable the admin app and backend admin flag, revoke active grants/sessions, and leave the V3 maintenance job running for its reviewed retention period. Suppression decisions already committed remain effective; rollback never redraws a terminal decision. A real trial still needs OAuth/project access, regional Valhalla/Photon and catalog data, consenting accounts/devices, operator assignment, retention/backup review, and independent traffic ground truth. These are tracked in [the staging handoff](../validation/V3_STAGING_TRIAL_PENDING.md). Separate production acceptance of V3's privacy and withdrawal terms remains required.
