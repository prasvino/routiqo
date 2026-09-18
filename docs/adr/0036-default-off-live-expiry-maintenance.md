# ADR 0036: Default-off bounded Live expiry maintenance

Date: 2026-09-18
Status: implemented and tested, disabled by default; production operation pending

## Context

Private Live storage has tested bounded cleanup adapters but no operating caller.
Logical expiry prevents authorization after expiry, but does not remove retained
rows. A scheduler must not turn bounded deletion into an unbounded transaction,
block authentication maintenance, leak private diagnostics, or erase durable
ordering fences and recreate replay authority.

## Decision

Add an explicitly enabled persistence-only core-api maintenance component using
the existing routeupdate application interfaces. Run one 100-row batch per context,
grant and receipt category per tick, with a fixed 60-second initial/fixed delay,
separate short transactions and a dedicated bounded scheduler. Isolate failures
and suppress sensitive exception details. The feature defaults off.

Keep execution beside the domain-owned persistence composition for this slice;
do not duplicate repositories or schema ownership in the workers application.
Future extraction requires an explicit service/maintenance boundary. No network
maintenance endpoint or new scheduler infrastructure is introduced.

## Consequences

Existing SKIP LOCKED and exact expiry checks support concurrent replicas without
a global coordinator. Throughput scales with enabled replicas and must be measured
before rollout. Cleanup lag does not extend evidence or replay authority. Removing
rows does not prove physical disk or backup erasure. Public Live publication,
moderation and revocation delivery remain separately gated.

See `docs/features/live/LIVE_EXPIRY_MAINTENANCE_SPEC.md` for acceptance, failure,
rollout and rollback requirements.
