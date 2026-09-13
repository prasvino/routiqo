# ADR 0028: Transactional Quick Signal storage

Status: accepted and implemented for internal persistence. No public ingestion,
provider anchor registration, moderation or projection is enabled.

## Decision

Route Update owns server-issued command grants, private receipts and fixed-minute
actor budgets. `SignalStorageService` enters the existing account-and-owned-journey
authority and composes the privacy consent and current route-context participants.
No adapter reads another domain's table.

Issuance requires an active owned journey, sharing consent, a current stored route
context and a registered anchor. It creates an unpredictable command UUID and a
complete admission expiring within 90 seconds and no later than its context. A
grant is stored independently in UNUSED or terminal CONSUMED state.

Acceptance first resolves retained actor-scoped receipts. Exact retained retries
return their immutable private outcome without requiring the old grant, consent or
context; changed fingerprints conflict. New acceptance locks and rechecks consent,
context, grant and the actor/anchor/category ACTIVE receipt slot. It atomically
reserves budget, supersedes the prior slot, consumes the grant and inserts the new
receipt. Rollback restores every step. Owner withdrawal is a monotonic retained
receipt transition and does not renew or erase evidence.

The database partial unique index makes receipt lifecycle state and the latest
contribution slot one row. The slot spans journeys. Receipts snapshot context and
consent values without foreign keys to grants or context, so cleanup cannot erase
retained command outcomes. Original durations are checked before microsecond
flooring; evidence is positive and at most 15 minutes, retention is at least the
original evidence duration and at most 24 hours.

## Budgets and cleanup

PostgreSQL stores one current minute bucket per actor and action. Issuance permits
at most ten grants and acceptance at most five new signals per minute across
journeys, contexts and replicas. A clock earlier than the stored bucket fails
closed. Retained retries consume no budget, and failed transactions consume none.

Callable grant and receipt expiry operations delete 1–100 exact expired rows with
`FOR UPDATE SKIP LOCKED` under five-second transactions. They are leaf-only and do
not acquire parent authority locks. Grant cleanup cannot cascade receipts; receipt
cleanup cannot reset a consumed grant. Logical expiry remains authoritative while
physical cleanup is delayed. No scheduler is mounted.

## Limits

These private records do not authorize publication. Public HTTP, peer limits,
anti-Sybil controls, provider-backed anchor registration, moderation, block/Ghost
cache invalidation, cohort suppression and an operated cleanup job remain release
gates. The durable consent same-state opt-out ordering limitation also remains.
