# ADR 0029: Explicit consent intent ordering

Status: accepted and implemented for the internal application boundary. No public
consent endpoint, UI, offline queue or presence publication is enabled.

> **Status update (2026-09-25):** Superseded by the direction brief. Consent intent ordering is retired with per-journey consent (one Spot-passage opt-in plus Ghost Mode). Its revocation-precedence ideas may be reused for the Spot-passage opt-in. See [PRODUCT.md](../PRODUCT.md).

## Decision

Keep ADR 0026's trusted `change` operation and same-state transition behavior for
existing internal callers. Add `PresenceConsentService.submitIntent` for future
user-directed consent mutations. Future public adapters must use this path and
must supply actor identity from independent authentication. The service enters the
existing enabled-account and owned-journey transaction; the privacy participant
locks the single latest consent row before applying the intent.

For an active journey, enable requires the exact current generation. Every
accepted enable, including reaffirmation, advances generation. Disable accepts a
stale or current generation and always advances generation, including when already
off. A future generation fails closed. This makes revocation conservative under
reordered delivery: an off delivered after an enable turns sharing off, and an off
delivered before an old enable fences that enable. Callers must never automatically
retry a conflicted enable with a newly read generation.

Completed journeys reject enable. Disable returns the inactive opt-out view without
mutation, even when a newer journey owns the account's latest consent row or the
old command's expected generation exceeds the synthetic completed view. Reads
remain nonmutating and no command receipt or history is added.

## Exhaustion and composition

At `Long.MAX_VALUE`, enable fails without mutation. Explicit disable and journey
completion saturate generation at that maximum while setting sharing off; completion
also makes the state inactive. This intentionally replaces ADR 0026's completion
overflow rollback rule. Existing grants bound to the maximum generation still fail
current-consent checks after sharing is disabled.

PostgreSQL account, journey and consent locks provide replica ordering. Tests
observe real lock waits for both enable/off orders and for consent revocation racing
signal acceptance. When revocation wins, acceptance creates no receipt, spends no
acceptance budget and leaves its grant unused.

## Limits

This path has no HTTP transport, immutable command receipt, automatic enable
reconciliation, cache revocation, lease issuer or public output. It does not make
offline enable safe. Public consent exposure still requires authenticated bounded
transport, abuse controls, UI semantics and reliable revocation across delivery
and cache paths.
