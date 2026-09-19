# Live expiry maintenance

Status: implemented and tested, disabled by default; production operation pending.
Decision: ADR 0036.

## Scope and boundaries

Operate the existing domain-owned expiry interfaces for private Live route
contexts, command grants and receipts. Retention rules remain ADRs 0027/0028/0035:
logical expiry denies use immediately, while physical row removal may lag.
ADR 0037 adds a fourth independent batch for expired rolling acceptance-ledger
charges. Each charge logically expires one hour after acceptance.
No HTTP endpoint, public projection, consent change, client queue, migration or
new evidence policy is introduced. Pending binding-attempt fences and durable
rate buckets are not cleanup targets; removing them could weaken ordering or
budgets. Account deletion continues to use its established cascades.

## Execution

Require the persistence profile and the explicit, default-false
`routiqo.live.expiry-maintenance-enabled` property, configurable through
`ROUTIQO_LIVE_EXPIRY_MAINTENANCE_ENABLED`. No operator activation is part of
implementation. This application maintenance job is unrelated to Codex timers.

After a 60-second initial delay, run with a 60-second delay after each completed
tick. Each tick attempts exactly one batch of at most 100 contexts, 100 grants
and 100 receipts, plus up to 100 acceptance-ledger charges, independently.
Do not drain a backlog in a loop. Serialize
ticks within a process; concurrent duplicate entry must not start extra work.
Use a dedicated bounded scheduler so maintenance does not delay authentication
maintenance. Each existing adapter retains its own five-second transaction/query
budget, server-clock expiry cutoff checked in SQL and leaf-only
`FOR UPDATE SKIP LOCKED` locks.
Do not wrap the tick in a transaction or acquire account/journey authority locks.

Authentication's named scheduler is owned by identity configuration under the
persistence plus web-auth/native-auth profiles, independently of this flag.
Moderation expiry has a separate flag and scheduler under
`MODERATION_EXPIRY_MAINTENANCE_SPEC.md`; enabling this LIVE job does not activate it.

Multiple replicas may run the job: row locking and exact expiry/identity rechecks
provide correctness. Per-replica batch limits are not a global throughput limit;
deployment must size the enabled replica count and database pool deliberately.
No leader election or distributed lease is necessary for these idempotent deletes.

## Failure and privacy

A failed category must not prevent attempts for the other categories or future
ticks. Never retry within a tick. Report only constant category/outcome labels;
never log thrown objects, exception messages, SQL, IDs, coordinates or private
rows. Failure must not mutate grant consumption, extend receipt retention or
revive expired evidence. Logical expiry remains authoritative during downtime.

Applicable threats: T12 log leakage, T13 replay/races, T14 unbounded maintenance,
T19 delayed deletion and T20 multi-replica correctness. Preserve existing cleanup
tests for locked rows, concurrent replacement, retained replay after grant purge
and denial after receipt purge with a still-consumed grant.

## Acceptance and operations

- Configuration tests prove absent/false flag and absent persistence profile
  create no job, and explicit enablement wires real maintenance interfaces.
- Tests prove bounded calls, failure isolation, recovery, sanitized diagnostics
  and non-overlap. Existing PostgreSQL tests remain required regression evidence.
- Rollout requires staging retention/backlog checks, alert routing and capacity
  verification. A full batch means a possible backlog, not proof of one.
- Roll back by disabling the flag and restarting. This stops future deletion;
  deleted expired rows cannot be restored by toggling the flag.
- Offline clients still follow existing retry rules. No deleted receipt or grant
  becomes a new command automatically.
- Physical database page erasure, backup expiry, moderation/legal retention,
  projection invalidation and operated production cleanup remain release gates.
