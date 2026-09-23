# Verified contributor authority prerequisite

Status: internal authority implemented; production operator provisioning and public publication remain closed.

## Acceptance and boundary

- A separately provisioned case binds one enabled account to one opaque random person reference and an expiry. No name, document, location, review note, or source evidence enters these tables.
- Two distinct enabled reviewers need current case-scoped `review` grants. The first approval creates a pending record; the second activates it. The subject cannot review their own case. A current case-scoped `revoke` grant can revoke an active record.
- Every command requires the exact current revision and an operator-scoped request ID. An exact retained retry returns its original receipt only while the current grant remains valid. A changed retry is denied. The ordered account transaction, case lock, row lock and partial unique person index enforce multi-replica agreement and one active account per person reference.
- `VerifiedContributorReader.current(accountId, now)` returns only opaque person reference, revision and expiry; it is a plain JDBC read usable within the journey write transaction. Missing, disabled, revoked, expired, malformed or unavailable state returns empty. Publication must also check suspension, journey/route authority, Ghost Mode, blocking and the separate public privacy protocol.
- Account deletion cascades cases, grants, current authority and audit. Audit receipts have 90-day logical expiry and a bounded, callable physical cleanup batch of 100. New actions are bounded to 20 per reviewer per hour and 1,000 retained receipts; capacity fails closed.

## Operational boundary

There is no consumer or administrative HTTP route, self-service verification, seed grant, document store or grant-management endpoint. Provisioning cases and grants requires the separate strong-authentication operator workflow described by ADR 0049 before production issuance. The cleanup batch has no default scheduler. Its scheduling, retention approval, backups, appeal access and tamper-resistant external audit are operations prerequisites.
