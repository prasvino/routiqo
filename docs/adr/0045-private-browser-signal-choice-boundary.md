# ADR 0045: Private browser signal choice boundary

Date: 2026-09-19

Status: accepted; implementation tested and independently reviewed.

## Decision

Expose the ADR 0043 minimized owner choice snapshot and ADR 0044 exact-context
issuance through distinct guarded leaves under a new default-off choice flag,
requiring the existing signal flag as well. Preserve legacy anchor-only issuance
without optional preconditions. New clients never downgrade to the legacy path.

Reuse existing owned-journey authority, catalog, consent, restriction and browser
security boundaries. Reads use a separate durable account request quota;
expected issuance shares the existing legacy issuance request and grant quotas.
Return only labels/categories and exact context/version/timing data to the owner,
with no-store and bounded Unicode-capable responses. No read issues a grant.

The exact paths, DTOs, flags, quotas, error policy and adversarial acceptance
criteria are in `../features/live/BROWSER_SIGNAL_CHOICES_API_SPEC.md`.

## Consequences

This permits private contribution controls to be built against a coherent
expected-context contract. A snapshot remains an observation, not authorization;
issuance and acceptance recheck current authority. A matching grant does not
prove physical presence, independent evidence or public eligibility. Private
contribution controls, public publication and rollout remain separate work.
