> **Archived 2026-09-25.** Per-journey consent and private LIVE contribution flows, replaced by one Spot-passage opt-in, simple Ghost Mode and public Spot signals. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Explicit journey consent intents

Status: implemented and tested internally. This application path prepares future
public consent commands; it adds no HTTP endpoint, offline queue or publication.

## Ordering decision

Keep the existing trusted `change` operation and its ADR 0026 state-transition
semantics for compatibility. Add a clearly named `submitIntent` service operation
and a privacy-owned participant method for explicit user intent. Future public
consent mutation adapters must use this intent path, never the legacy `change`.
Actor identity is independently authenticated; all reads and writes enter the
existing account -> owned journey -> consent transaction and row locks.

For an ACTIVE owned journey:

- Require a nonnegative expected generation no greater than the current one.
- Enabling requires exact current generation. Every accepted enable intent,
  including reaffirming on, advances generation; never automatically retry a
  conflicted enable using a freshly read generation.
- Disabling accepts a stale or current expected generation, and advances the
  generation even when already off. Thus off-before-delayed-enable fences the
  enable; enable-before-stale-off ends off. Revocation takes precedence within
  the same journey, rather than trusting client timestamps or delivery order.
- A delayed off can suppress a more recent enable. This conservative behavior is
  intentional. A new deliberate enable after reading current state may opt in.
- Responses describe the current committed state. There are no immutable command
  receipts or exactly-once mutation claims. Repeating off may advance generation
  again, but cannot enable sharing or renew a presence lease. Repeating an old
  enable conflicts; the client must reconcile, not silently re-enable.

Reads stay mutation-free. One latest consent row per account is retained; no
command history, coordinates, timestamps or new table is needed. Replacing the
binding on an active new journey starts from that journey's generation-zero state.
A command on an old completed journey must never overwrite a newer binding.
Completed journeys reject enable and return inactive off for disable without
changing any row or generation.

## Generation exhaustion and terminal revocation

At Long.MAX_VALUE, enabling fails without mutation. Explicit disable must still
turn sharing off, saturating the generation rather than overflowing or wrapping.
No subsequent enable can succeed on that journey. Journey completion must also
remain possible at the maximum generation, atomically setting off/inactive at the
same maximum. This intentionally supersedes ADR 0026's completion-overflow failure
behavior. Other legacy change semantics remain unchanged. Old grants at the
maximum cannot authorize writes once sharing is off; test that composition.

## Verification and limits

Use real configured services and disposable PostgreSQL. Cover same-state off at
zero and nonzero generations, both delivery orders, exact enable and stale/future
denial, concurrent replicas, repeat behavior, wrong/disabled/deleted account,
journey replacement/completion isolation, maximum-generation disable and completion,
and rollback on completion-participant failure. Deterministic races must observe
database lock blocking; do not substitute sleeps or process-local authority maps.
Exercise the intent path with actual signal grant acceptance: revocation winning
the lock denies acceptance without receipt, budget charge or grant consumption.
Keep private errors and records redacted; verify the existing architecture/build
checks and update focused tests for the intentional completion-overflow change.

This resolves internal intent ordering, not delivery of revocation to caches,
public request limits, browser/native consent UI, command transport, moderation or
cohort publication. No offline enable queue or automatic enable reconciliation is
permitted by this slice. Public consent exposure remains gated on those applicable
transport, abuse, UI and revocation requirements.
