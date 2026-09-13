# ADR 0032: Two-transaction route binding

Status: accepted and implemented as an internal, default-off composition. No
public binding endpoint, signal issuer or Live publication is enabled.

## Decision

Route binding uses two short account-and-owned-journey transactions with provider
resolution between them. The first transaction requires an active owned journey,
current sharing consent and an exact current-context expectation. It reserves the
shared database account budget (`route-binding-account`, ten per minute) and
replaces the account's single durable binding-attempt fence with a fresh server
UUID, consent generation, catalog version and 90-second server deadline. The
transaction commits before fresh region-guarded Valhalla work begins. Admitted
provider failures remain charged and their newest pending attempt still fences
older responses.

The second transaction reacquires account, journey, consent, context and attempt
locks in that order. It rechecks active ownership, consent generation, exact
context state, catalog identity, latest pending attempt identity and the deadline
using time sampled after the attempt lock. It consumes the attempt before writing
a fresh 15-minute context. Context failure rolls consumption back. No-route and
no-eligible-anchor results consume the attempt without changing the prior context.
Only opaque curated anchor UUIDs are persisted; route requests, endpoints,
geometry, instructions and provider results are not stored.

Every JDBC context replacement invalidates a pending attempt after its context
write, in the same transaction. Journey completion invalidates pending attempts
for that journey after consent revocation and context deletion, including when no
context exists. Context expiry cleanup remains leaf-only and does not delete the
latest attempt, closing replacement-plus-cleanup resurrection. Account and journey
deletion cascade attempt data. The attempt participant requires the existing
authority transaction and contains all Route Update attempt SQL.

## Limits

The internal outcome is private state, not a capability or proof of physical
presence. The resolver and catalog remain default off. Regional catalog rollout,
replica-consistent catalog versions, public authenticated binding transport,
category revalidation during grant issuance, moderation, revocation delivery and
cohort-safe publication remain release gates.
