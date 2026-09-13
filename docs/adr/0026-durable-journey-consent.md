# ADR 0026: Durable journey consent

Status: accepted and implemented for internal persistence. No public presence or
Live signal endpoint is enabled.

## Decision

Store one latest `presence_consent` row per account in PostgreSQL. The row binds
the actor to one owned journey and contains only generation, sharing and
journey-active state. The actor primary key and composite owned-journey foreign
key enforce ownership and cascade deletion. There is no consent history,
coordinate, timestamp or public projection in this table.

`PresenceConsentService` enters the Journey-owned write authority before every
read or change. The privacy JDBC participant then locks the actor's consent row
inside the existing account -> journey transaction. Missing or differently bound
rows read as generation-zero opt-out for the requested journey without mutation.
An explicit change on a new active journey may replace the prior latest row.
Expected generation is checked even for same-value writes; actual state changes
use the existing `PresenceConsent` monotonic transitions.

Journey completion is orchestrated only by `JourneyService`: it enters account
and journey authority, performs the repository update, then invokes synchronous
Journey-owned completion participants before commit. Privacy implements that
participant and revokes a matching consent row in the same transaction. Missing
or differently bound rows are unchanged, completed retries skip participants,
and participant or generation-overflow failures roll back journey and consent.
Raw repository completion is a trusted transaction participant, not an authority
entry point.

## Security and privacy properties

Independent adapters serialize through PostgreSQL rows rather than process-local
state. Stale generations conflict with a generic cause-free error. Account
deletion cascades the minimal latest row. Reads do not opt in, create rows or
replace a newer journey's state. The privacy participant rejects calls without
an active transaction, and persistence failures remain covered by ADR 0025's
redacted account-boundary availability behavior.

A same-state opt-out preserves its generation by domain contract. Therefore an
initial explicit off write at generation zero does not fence a delayed enable
that also expects generation zero. Only an actual transition, such as sharing on
to Ghost Mode off, advances the generation and rejects that stale enable. Any
future public consent command API needs durable command ordering or mutation
identity before it can claim that all reordered opt-out intentions win.

## Limits and follow-up

This state does not issue presence leases, revoke already delivered caches,
authorize signal storage or make public aggregation safe. Durable route context,
command grants, receipts, contribution slots, cleanup, quotas, block enforcement
and publication invalidation remain required. No HTTP, Redis, timer or background
cleanup path is added by this decision.
