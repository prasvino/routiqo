# Authenticated internal journey route binding

Status: implementation acceptance; no public binding HTTP/UI or signal issuer.

Implement a synchronous internal service accepting independently authenticated
actor ID, owned journey ID, RouteRequest, alternative index and optional expected
current context ID. Never accept client geometry, anchor sets or resolver results.
Compose the configured RouteAnchorResolver with journey/consent/context application
interfaces. Reuse the default-off anchor resolver configuration; no new activation.

## Two transactions and a durable attempt

Before routing, enter enabled account -> owned journey -> consent -> context ->
binding-attempt locks. Require ACTIVE journey, current sharing/active consent,
valid request/alternative, and exact current-context expectation (none only when
no logically current context). Validate before a provider request. Reserve a
database-backed account budget through AuthRateGate, category route-binding-account,
limit 10/minute, spanning sessions/journeys and charging admitted attempts even if
the subsequent provider fails. Rate store failures sanitize to generic availability.

Persist a fresh server UUID attempt, journey, consent generation, catalog version,
optional expected context ID, server issuedAt/deadline and PENDING state. Deadline
is 90 seconds; times use database microsecond precision. One latest row per account,
upsert supersedes every previous attempt, even when the newer request later fails.
Keep the latest row through expiry to avoid history/cleanup-based resurrection;
only replacement or account/journey cascade removes it. No geometry, endpoints,
provider instructions, per-attempt history or results are persisted in this row.
Migration V11 has owned-journey/account cascading FKs, nonnil UUIDs, nonnegative
generation, finite bounded times and closed PENDING/CONSUMED/INVALIDATED state.

Commit before calling the resolver. It must observe no ambient transaction; do
not hold locks or call another authority entry inside a transaction. Provider
failure leaves the attempt pending but never restores the prior attempt/context.
No automatic retry, renewal or provider fallback.

After resolution, reenter the same ordered authority. Recheck enabled account,
owned ACTIVE journey, sharing and identical consent generation, current context
expectation, exact latest PENDING attempt identity/journey/catalog and result
catalog version. Sample server time after relevant row locks and deny future or
expired attempts. No stale result may overwrite current state. All checks and
mutation share one transaction. Consume the attempt BEFORE invoking context replace;
then persist only resolved anchor IDs plus their immutable catalog version through
the existing context participant, with a fresh context UUID and a fixed 15-minute
context lifetime. Context write
failure rolls back consumption. Do not accept caller-selected lifetime or new UUID.

Return private typed BOUND/NO_ROUTE/NO_ELIGIBLE_ANCHORS outcomes. Empty route/match
outcomes consume the attempt after the same authority checks without changing the
prior context. BOUND includes the stored context; other outcomes have none. These
outcomes are not public capabilities or publication permissions.

## Invalidation across all context writers

Every successful existing context replacement must invalidate any PENDING attempt
for the same account atomically, after the context write, under context -> attempt
order. The new binding path consumes its own attempt before replacement, so this
invalidation cannot cancel its completed write. Completion invalidates PENDING
attempts for only the completed owned journey even when no context row exists.
This closes the case where another writer creates a context and expiry cleanup
removes it before an older provider response returns. Context expiry maintenance
remains leaf-only and acquires no attempt/parent locks. No separate attempt cleanup
or scheduler is added. Failures roll back context/journey and invalidation together.

Route Update infrastructure owns attempt SQL. Use an intentional participant/store
interface; don't introduce cross-domain repository queries. Trusted store methods
require the existing transaction. Inner and outer account authority sanitizers
must cover SQL/transaction errors before rollback logs. Records/errors redact.

## Verification and limits

Use disposable PostgreSQL and real consent/context/journey services. Test configured
wiring and real resolver with a bounded local Valhalla fixture; missing ownership,
disabled/deleted account, opt-out and invalid input make zero provider calls.
Observe no active transaction inside provider transport. Cover successful binding,
old grants denied after replacement, exact-context mismatch, no-route/no-match,
quota across adapters/journeys and failed provider charging, provider failures,
newest attempt wins in both response orders, newer failed attempt still fences old,
Ghost/off-on/completion/delete during provider work, context replacement then purge,
expiry after lock wait, future time, microsecond bounds, and rollback after consume.
Use latches/observed DB waits for meaningful races; no sleep-only race proofs.
Prove all context writers/completion invalidate pending attempts and cleanup remains
leaf-only. Preserve existing tests and update them for deliberate invalidation.

Create ADR0032 and reconcile current Live/context/roadmap/security/threat records.
Run focused then full relevant Java check/bootJar and secret/diff checks; obtain
independent review. Leave preview port3000 and its pre-existing next-env.d.ts diff
untouched; no frontend build, user database migration, flag activation or timer.

Real regional data/catalog rollout consistency across replicas, public authenticated
binding transport, current category validation at grant issuance, moderation,
revocation delivery and cohort-safe publication remain gates. Matching and binding
do not prove physical presence; no public LIVE output is enabled by this phase.
