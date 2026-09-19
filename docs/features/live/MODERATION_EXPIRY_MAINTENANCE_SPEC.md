# Moderation expiry maintenance

Status: implemented and independently reviewed, 2026-09-19; disabled by default.
Extends ADR 0041's
callable retention boundary without changing its retention or authorization policy.

## Scope

Schedule existing `ContributionRestrictionAuditCleanup` methods only. Delete
expired command audit receipts and expired operator action debit slots. Never
delete grants, restrictions, blocks, live evidence or unexpired audit. The tested
adapter owns its transaction, clock cutoff, exact-row rechecks and leaf-only
`FOR UPDATE SKIP LOCKED` locking. No new database queries, migration, operator
endpoint, grant administration or account authority are introduced.

Require the persistence profile and a separate explicit default-false
`routiqo.moderation.expiry-maintenance-enabled` property, exposed as
`ROUTIQO_MODERATION_EXPIRY_MAINTENANCE_ENABLED`. Enabling LIVE expiry alone must
not enable moderation cleanup. Implementation does not activate either flag.
This application job is unrelated to Codex task timers.

## Scheduling and failure isolation

After a 60-second initial delay, run with 60-second fixed delay after completion.
Each tick attempts one batch of at most 100 audit receipts, then one batch of at
most 100 debit slots. No draining loop, retry, external call or surrounding
transaction. A synchronous guard prevents overlapping ticks within the process.
Each category must still run if the other fails; a failed tick must permit the
next tick. Log constant category labels only, never exceptions or private data.

Use a dedicated single-thread moderation scheduler, independent of LIVE expiry
and authentication maintenance. Move the existing named authentication scheduler
to identity-owned configuration so it is available under persistence plus either
web-auth or native-auth regardless of either cleanup flag. Preserve its existing
name and isolation; test all relevant combinations for collisions or accidental
selection of a cleanup scheduler for authentication work.

Multiple replicas may run independently using the adapter's row locks. Batch
limits are per replica, not a cluster-wide throughput guarantee. No distributed
leader or lease is needed for bounded expired-row deletion. Existing 720-hour
audit expiry and one-hour debit horizons remain unchanged; logical expiry and
rate enforcement do not depend on the scheduler running.

## Verification and rollout

Test absent/false flags and absent persistence, explicit enabled wiring, missing
cleanup authority failure, and coexistence of authentication/LIVE/moderation
schedulers. Test bounded calls even when a batch is full, failure isolation,
recovery, constant-only logging, non-overlap and fixed scheduling metadata.
Preserve existing PostgreSQL cleanup/rate/race tests and run core check/bootJar.

Staging activation, backlog and capacity checks, alert routing, backup retention
and operator access remain external release work. Disabling the flag and
restarting stops future deletion; it cannot restore deleted expired rows.
Database deletion is not proof of physical media or backup erasure. Grant
administration, operator authentication and reporting remain separate gates.
