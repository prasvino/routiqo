# V3 operator grants implementation plan

Goal: implement ADR 0057 and the [feature contract](../docs/features/live/V3_OPERATOR_GRANT_ADMIN_SPEC.md) as a complete default-off staging slice.

Existing boundary: ADR 0056 has a separate admin origin/session and finite moderator grants, but the grants are provisioned manually. V16/V25 hold the grant table and permissions. Admin proxy and UI are already allowlisted.

Work: V26 grant-admin permission/audit migration; locked issue/revoke/read service and bounded cleanup; independently flagged HTTP, OpenAPI and generated types; allowlisted admin proxy/client and grant panel; PostgreSQL/HTTP/proxy/browser tests; runbook and pending ledger. Keep changes within V3. No new account directory, private verified-contributor case system, production flags, or person-level privacy work.

Security: exact administrator session and current finite grant on every request, no self-grant or administrator-target grant, account-before-grant order, post-lock time check, 30-day minimized audit, bounded rates/body/queries, no client-held privileged identity. Review concurrent revocation and moderator actions, exact replay, deletion and database failure. No external API call in a transaction.

Rollout: apply V26 and enable admin plus grant flags only in an isolated staging environment with configured admin OAuth/MFA and a named out-of-band root grant. Rollback disables grant routes first; issued moderator grants require explicit revocation or expiry, while cleanup continues for the reviewed retention window. Production activation remains a separate decision.
