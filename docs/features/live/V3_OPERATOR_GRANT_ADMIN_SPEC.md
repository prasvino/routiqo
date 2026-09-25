# V3 operator grant administration — isolated staging feature contract

Status: implemented for isolated staging behind a separate disabled flag. Real staging is pending. This does not approve V3 production activation, its privacy terms, or person-level public LIVE research.

> **Direction brief (2026-09-25):** The V3 target and its `traffic_review`/`traffic_suppress` grants are retired. The grant-administration boundary (separate admin login, short-lived audited grants) is kept and simplified for the pilot moderator rota; see [PILOT_MODERATION_RUNBOOK.md](../../development/PILOT_MODERATION_RUNBOOK.md). See [PRODUCT.md](../../PRODUCT.md).

## Purpose and scope

A designated grant administrator can issue and revoke short-lived `traffic_review` and `traffic_suppress` permissions for an already enabled Routiqo account. This closes the manual day-to-day grant operation gap in the V3 moderator trial. It does not create accounts, discover operators, administer the private verified-contributor program, or make a report decision. The initial `traffic_grant_admin` trust root is provisioned out of band by a named database/security operator and is never issuable by this API.

## Authority and data

- Reuse the separate admin Google audience, origin, challenge, short session and cookie/CSRF boundary in ADR 0056. The grant API and UI have an independent `ROUTIQO_V3_GRANT_ADMIN_ENABLED` flag, false by default, and require the V3 admin flag too.
- Every read and write requires a currently enabled actor, current admin session and finite `traffic_grant_admin` row. Check the grant again after locks, including exact retries. No consumer session, client-provided actor, email/domain role or bare account UUID is authority.
- The target is an exact account UUID supplied through the controlled operator roster. There is no search, directory, list or Google-subject lookup endpoint. The target must already exist and be enabled. The actor cannot target self or an account holding a current grant-administrator permission; grant-admin roles cannot be created or extended through HTTP.
- New V3 grants last 15 minutes to four hours from server time, below the existing database's 24-hour maximum. A live grant cannot be silently renewed: revoke it explicitly before a new issuance. Revocation takes effect for the next permission check. No target identity details, route data or report contents enter the grant audit.
- Issue and revoke take immutable request UUIDs and closed reasons. A successful action and minimized 30-day audit record commit atomically. Exact retained retries return the first result without extending access; a different fingerprint for the same actor/request UUID conflicts. Audit capacity and request rates fail closed. Physical expiry cleanup is bounded and independently gated by `ROUTIQO_V3_GRANT_ADMIN_MAINTENANCE_ENABLED`.
- Lock distinct actor/target accounts in UUID order, then current administrator and target grant rows, and sample server time after blocking locks. Grant revocation and moderator action must serialize through the target grant row so an action cannot commit after a revocation that acquired it first. Reject ambient transactions and database uncertainty without claiming success.

## Admin experience

The admin site offers a grant administration panel only when the separate flag is enabled and current server authority permits it. The operator enters an exact target account UUID, reviews the current two V3 permissions, chooses a permission, duration and closed reason, confirms issue or revoke, and sees the committed expiry or removal. Unknown/disabled targets have the same generic response. Loading, no grant, access denied, expired session, conflict, uncertain write, offline and exact retry states are explicit. Do not persist operator roster or grant details in browser storage; retain only a bounded in-memory pending request for an exact retry while the page is open. Sign-out/account switch clears it.

## Acceptance criteria

1. All new routes and UI are absent with either flag off. Consumer credentials, a moderator-only grant, missing/expired administrator grant, disabled account, self-target and grant-admin target cannot issue or revoke.
2. PostgreSQL tests cover issue, revoke, expiry, concurrent issue/revoke and moderator-action ordering, conflicting/exact retries, rollback, account deletion and bounded cleanup. HTTP/proxy tests cover origin/CSRF, body/rate limits, default-off behavior and no target enumeration.
3. OpenAPI and generated types describe only the bounded exact-target contract. The admin UI handles the states above, keyboard and narrow layouts, and never claims real OAuth/MFA testing from fixtures.
4. The staging runbook records the out-of-band trust-root bootstrap, named access owner, grant expiry/revocation exercise, rollback and the remaining real OAuth/MFA, supervision, retention and production reviews.
