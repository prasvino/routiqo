# V3 operator grants — isolated staging runbook

Status: implemented behind disabled flags under ADR 0057; real isolated staging operation is pending. V3 publication, moderator access and grant administration remain disabled in production pending the [production decision checklist](../validation/V3_PRODUCTION_DECISION_CHECKLIST.md).

## Trust root and configuration

Use the separate admin origin, Google OAuth audience and MFA policy in the [moderator runbook](V3_MODERATOR_STAGING_RUNBOOK.md). Apply migrations through V26 to a verified isolated staging database. Set `ROUTIQO_V3_ADMIN_ENABLED=true` and `ROUTIQO_V3_GRANT_ADMIN_ENABLED=true` separately in the backend and admin app only for that staging target. The backend's `ROUTIQO_V3_GRANT_ADMIN_MAINTENANCE_ENABLED` independently starts bounded audit and expired-grant cleanup. Review all effective configurations before testing; repository examples remain false.

A named security/database operator establishes the first `traffic_grant_admin` row out of band. Identify an existing enabled account through the private operator roster, lock that account before writing its finite grant, and record who authorized the bootstrap, account UUID, reason, issued time and expiry in the approved external operator audit. Do not seed a root grant in a migration, application startup, fixture, API or client bundle. The root grant lasts no longer than the database's 24-hour ceiling and must be reauthorized through the same out-of-band process when it expires. The web API cannot create or renew a root grant.

## Grant procedure

1. The grant administrator signs in using the separate admin Google client and confirms that the server reports grant administration access. Obtain the intended moderator account UUID through the controlled operator roster; the admin UI deliberately has no account directory or Google-subject search.
2. Look up that exact UUID. Confirm the operator assignment independently, select `traffic_review` for queue/dismissal or `traffic_suppress` for takedown authority, choose a bounded duration and a closed reason, then confirm. Record the resulting expiry in the operator handoff. A current grant requires explicit revocation before a new issue; an uncertain response is recovered only by retrying the same request identity.
3. Revoke a permission when the assignment ends, access is questioned, or an error is corrected. Check that a subsequent queue/action request is denied and that a concurrent action either committed before revocation or was denied after it. Revoke the admin session separately if the device or account access is in doubt.
4. Inspect minimized grant audit and expired-grant cleanup through approved operator-only channels. No Google subject, route, report body, candidate or contributor identity belongs in that audit. Verify backups and retention independently before any broader trial.

## Trial and rollback

Verify both flags off, then one flag at a time: grant routes and panel must remain absent until both are enabled. Test moderator-only, expired administrator, disabled target, self-target and grant-admin target denials; exact/conflicting retries; grant expiry; and UI loading, missing, offline and uncertain-result states. Real OAuth/MFA and named operator accounts are required for this trial; fixture and PostgreSQL tests are engineering evidence only.

For rollback, disable the grant administration flag first, then revoke any issued moderator grants through the controlled database procedure or wait for their short expiry. Keep the existing V3 audit/expiry maintenance running for its reviewed period. Disable the moderator flag and V3 publication flags according to their own runbooks. An issued grant is not undone merely by hiding the grant panel. Production use still needs supervision, incident alerts, approved audit/backup retention and a decision on dual control, in addition to explicit V3 privacy acceptance.
