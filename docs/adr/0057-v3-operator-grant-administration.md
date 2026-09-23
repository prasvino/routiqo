# ADR 0057 — Controlled V3 operator grants in isolated staging

Status: implemented behind disabled flags for isolated staging evaluation on 2026-09-23. Real staging and production activation remain unapproved.

## Context

ADR 0056 authenticates a V3 moderator separately and checks finite database permissions, but grant issuance and revocation still require manual operator database work. Real staging needs a repeatable, audited operation without exposing role management to a consumer account or letting a moderator elevate itself.

## Decision

Add a separately flagged grant-administrator capability inside the existing admin origin and session boundary. A `traffic_grant_admin` database permission is a root capability installed or revoked only through a named out-of-band security procedure. The browser/API can issue or revoke only finite `traffic_review` and `traffic_suppress` grants for exact pre-existing enabled account IDs. Self-targeting and granting to another active grant administrator are denied. No account search, role bootstrap or administrator-role mutation is exposed.

Each mutation serializes the actor and target account locks, current authority, affected grant and request audit in PostgreSQL. Successful exact retry cannot renew a grant. A live grant needs explicit revocation before reissue. Both the admin and grant flags remain false by default, and the consumer application has no path to the grant boundary. Audit is minimized and expires independently; a separate cleanup job removes old audit and expired grants in bounded batches.

## Consequences and remaining gates

The staging operator can grant and remove V3 moderator access without routine SQL edits after the root capability is installed. One compromised grant administrator can still authorize another account within the limited permissions and duration; real MFA, named administrator assignment, supervision, incident alerts, backup retention and a production dual-control decision remain open. This ADR does not approve the V3 privacy contract or connect the paused person-level research.
