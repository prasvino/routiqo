# Public LIVE privacy protocol — implementation contract

Status: design and implementation in progress; public publication remains closed. This specification narrows the next engineering step under ADR 0038. It does not promote the current private signal receipt to public evidence.

ADR 0051's fixed-window 12-person/10-agreement candidate was independently reviewed and rejected for public release on 2026-09-23: contributor-dependent suppression after a known revocation remains a participation oracle. Durable verification and per-receipt public-purpose intent may advance privately, but they do not authorize public output.

## Acceptance criteria

1. A release configuration cannot enable traveller-derived output without a reviewed protocol version, independently human-verified contributor authority, current consent/journey/context/catalog/safety authority, and operational moderation/reporting.
2. One verified person can supply at most one effective contribution per anchor, category and fixed window across all accounts and journeys. An unassessed, expired, suspended, deleted or duplicate-person account contributes nothing. A Google account or route match alone is never verification.
3. All readers receive the same canonical situation projection for a fixed catalog anchor and window. There are no arbitrary areas, coordinates, route slices, member lists, counts, exact timestamps or viewer-specific evidence subtraction.
4. A terminal withdrawal, Ghost Mode, journey completion, account deletion, moderator restriction or source revocation makes affected evidence ineligible for future output. A published window is suppressed as a whole and cannot be republished from the remaining contributors. Previously delivered client data cannot be recalled; short expiry and explicit stale behavior bound that exposure.
5. Missing authority, version disagreement, stale caches, failed reads or maintenance lag deny output. Reconnect never resubmits a signal. Multi-replica decisions use durable shared state, not process-local counters.
6. Adversarial tests cover threshold-minus-one collusion, duplicate accounts per person, overlapping/adjacent anchors and windows, repeated reads, block changes, withdrawals, Ghost Mode, expiry, race conditions and restart/replica disagreement. Independent review must challenge the entire observation transcript before activation.

## Human verification boundary

Human review must establish a single pseudonymous person reference across accounts without copying identity documents into Routiqo's route or signal tables. Its authenticated operator workflow, evidence-retention policy, audit, appeals and revocation rules require a separately reviewed design. A unique person reference prevents duplicate active memberships but does not prove that distinct people are non-colluding or physically present. Unknown verification fails closed. The existing `ContributorAssessment.ASSESSED` is an in-memory value; its current durable restriction table stores only suspension and cannot be treated as verification.

## Candidate publication mechanics

- Use versioned, immutable operator-curated anchors and nonoverlapping server-defined windows. A query takes an owned active journey and returns only approved relevant anchors; callers cannot request arbitrary anchor IDs or bounds.
- Form a canonical per-anchor/category/window candidate from server-received, nonexpired structured evidence after the window closes. A person contributes once to a candidate regardless of accounts, replacement commands or journeys. Do not use raw GPS, account IDs, receipt IDs, report counts or exact event times in the public response.
- Require the approved independent-person minimum and an explicit bounded-adversary assumption before a candidate can be released. The numeric threshold, window length, read budget and permitted inference are not approved by this document.
- Publish one immutable coarse condition/freshness result per window. Never update its condition in response to a new individual report. Any later source ineligibility permanently suppresses the entire candidate; do not subtract a source and publish a distinguishable replacement.
- Authorize every read against current account and journey state, current relevant catalog, block policy, policy version and expiry. Deny when any authority is unavailable. A block edge must not create a per-viewer subtraction oracle; the block-safe delivery rule requires separate review before public reads.
- Public cache keys include protocol version, anchor and window. Suppression must be durable and invalidate all cache/realtime channels. An offline client can display only already delivered rows as stale in memory until their short expiry; local Ghost, account change and journey completion clear them immediately.

## Threat and utility limit

Human verification narrows Sybil attacks but does not make a deterministic threshold anonymous against a coalition that knows all other reports. A threshold-minus-one existence oracle remains possible with enough verified colluders or external knowledge. No implementation may claim universal non-association. The release ADR must state a bounded coalition, what the adversary knows, the observable read transcript, permitted group-level inference and residual risk. If the product requires protection against all-but-one verified collusion, traveller-derived deterministic public output stays closed.

## Dependencies before public output

- Reviewed, durable human verification and authenticated operator authority with one active person mapping, expiry, revocation, audit and no raw identity documents in this data path.
- Approved bounded-adversary privacy contract and independent adversarial review.
- Durable reporting, block-safe delivery, revocation propagation, read budgets and operator workflows as tracked in `todo.md` and `BUILD_STATUS.md`.
- Real catalog/provider/OAuth configuration and staging plus device acceptance. These do not substitute for the privacy gate.

The implementation sequence is: verification authority and negative tests; fixed-window candidate/suppression storage; current-authority publication reader; API/client once the release ADR and independent review pass. Do not add a public endpoint or enable an existing default-off flag before these gates close.
