# ADR 0056 — Separate moderator boundary for V3 staging

Status: implemented behind disabled staging flags on 2026-09-23; real staging evaluation remains pending. Production activation and ADR 0055's privacy contract remain unapproved.

## Context

ADR 0055 permits a canonical community traffic summary to receive bounded reports and an audited safety suppression. At the time of this decision, V3 stored report metadata and had an internal suppression command, while the admin application was a shell. A consumer session or a supplied operator identifier cannot safely invoke moderation. Reports may outlive their source projection, so their mere existence cannot prove a summary is still investigable.

## Decision

Use a separate admin browser origin, Google OAuth audience, nonce-bound challenge, session storage and cookie names. The admin session resolves only an existing enabled Routiqo account. Each read and action checks a current finite database permission; Google identity, email or domain alone never grants moderation authority. A controlled operator grants process remains necessary before use. The admin proxy forwards only allowlisted admin paths and admin cookies, with exact origin, CSRF, no-store, size and deadline controls. Consumer routes and sessions are not an alternative path.

The operator queue groups V3 reports by canonical reference and returns only bounded reason metadata and the retained coarse projection. It never returns reporter identities, private candidates or contributor evidence. Missing/expired projection content is explicitly unavailable. A dismiss decision requires investigable evidence; suppression requires a current unsuppressed projection and an open report group, and atomically records the serving effect and audit without changing the terminal publication decision. A later report can reopen a dismissed group. Reads and writes are separately rate limited and audited.

The admin API and app are default off. Staging still requires an operator-managed Google client and MFA policy, finite grant provisioning, real OAuth and device QA, monitoring and approved retention. This ADR does not authorize publication, grant seeding, a consumer-accessible moderator route, a private report workflow, or a production flag change.

## Consequences

The moderator workflow can be exercised in isolated staging without granting public users administrative powers or extending source evidence lifetime. Current finite grants and strong external identity configuration are operational dependencies. Saved outside copies of a suppressed V3 moment cannot be recalled; suppression is a serving decision, not a privacy guarantee. The production decision checklist remains the release gate.
