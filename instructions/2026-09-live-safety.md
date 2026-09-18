# Autonomous Live safety execution

Date: 2026-09-18. User authorized autonomous independent engineering work, excluding
credentials, provisioning, deployment and real-device verification.

## Baseline and scope

Baseline 0611499: private consent/context/signal APIs and cleanup default off;
public projection, moderation, reporting and blocking absent. Preserve existing
authentication, authority ordering, receipts and client offline behavior.

1. Record publication threat decision (ADR 0038), including threshold/collusion
   and withdrawal attacks. Do not equate a decided no-go boundary with completion
   of the unresolved public protocol.
2. Implement ADR 0037 rolling-hour/category abuse budgets and bounded retention
   in routeupdate persistence. Sol owns code/tests; root owns docs/integration.
3. Implement reviewed private moderation domain state transitions for assessment,
   suspension, directed blocks/bilateral exclusion and report-case idempotency.
4. Extend durable safety authority/integration only after these phases are
   verified and remaining capacity permits a coherent tested slice. No permissive
   fake authority, exposed operator endpoint or partial public projection.

## Verification

Use focused domain/config/database tests during implementation; final core check
and bootJar once integrated. Independently inspect expiry/lock/replay boundaries,
rolling limits, redaction, overflow/revocation precedence and exact retries.
Use independent adversarial review for publication design and persistence races.
Keep migrations on disposable PostgreSQL; do not migrate user data.

## Failure, rollout and recovery

Unavailable authority denies; database errors remain redacted. Existing account
locks serialize writes and new cleanup uses leaf-only locks with bounded batches.
No migration/feature activation is part of this run. Later rollout needs staging
migration/retention/backlog checks and a cutover plan for historical writes that
predate new budgets. Update build status/checkpoint/todo with verified results,
distinguishing private domain tests from operational enforcement.

## External and design gates

External: real OAuth, regional catalog/providers, operator hosting/secrets and
Android devices. Design: privacy claim against bounded collusion, independent
evidence authority and block/withdrawal-safe public projection. Continue all
independent prerequisites; never activate public output to bypass a gate.
