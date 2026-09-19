# Private browser LIVE clients

Status: implemented and independently reviewed, 2026-09-19; private clients only,
no public projection or server flag activation. Private consent UI integration is
specified separately in BROWSER_LIVE_CONSENT_UI_SPEC.md. Verification is recorded
in BUILD_STATUS.md.

## Scope

Implement typed browser clients for the real owner-only consent, route-context and
Quick Signal endpoints documented in BROWSER_CONSENT_API_SPEC,
BROWSER_ROUTE_BINDING_API_SPEC and BROWSER_SIGNAL_API_SPEC. Reuse generated
OpenAPI types. These clients are prerequisites for an explicit consent/contribution
interface; they do not authorize or enable public publication, infer presence or
change server feature flags.

ADR 0045 extends this boundary with separately gated owner choice reads and
mandatory expected-context issuance, specified in BROWSER_SIGNAL_CHOICES_API_SPEC.
The new issuance function never falls back to the legacy anchor-only function.

Validate all input before fetching even CSRF: canonical lowercase non-nil UUIDs,
exact object keys, booleans, closed mode/value enums, bounded coordinates, required
nullable context expectation, integer alternative index 0..2, and exact canonical
decimal strings through 9223372036854775807. Do not coerce, round or regenerate
identity, revision, generation or command values.

Only construct the documented relative same-origin paths. Send the captured
X-Routiqo-Account on resource requests, same-origin cookies, no-store and redirect
error. POSTs require a CSRF token obtained from the existing same-origin CSRF
endpoint using bounded transport. Do not reuse an unbounded JSON reader merely
to share code; a narrow LIVE-private request helper can own its CSRF fetch while
keeping the LIVE operation lifecycle self-contained. Auth transport hardening is
specified separately in ../auth/BROWSER_AUTH_TRANSPORT_SPEC.md. No logs, browser storage, retries,
offline queue, polling or arbitrary URL/header options.

## Bounded operation lifecycle

One operation deadline covers CSRF, fetch and response streaming: 12 seconds for
consent/context reads and signal commands; 30 seconds for route binding (the
existing proxy allows 25 seconds). Support caller AbortSignal; check before every
network leg and before returning data. Aborted or timed-out operations cannot
start a later POST after delayed CSRF resolves. Do not depend on test adapters
honoring abort: reject at the deadline and discard late results safely.

Limit each response to 64 KiB counted as streamed UTF-8 bytes (CSRF 4 KiB), except
the exact ADR 0045 choice GET, which allows256KiB for bounded escaped Unicode
labels. Keep the12-second deadline and all other limits unchanged. Reject
malformed UTF-8/JSON and cancel readers on failure. Do not await an uncooperative
reader cancellation forever. A stalled body and rejected cancellation must not
leak details or leave unhandled rejections. Require successful JSON content type
and the endpoint's exact 200 status; reject redirects even in mocked transports.
Do not read error bodies. Expose only a generic typed error with bounded status
classification; preserve explicit caller cancellation as AbortError. HTTP409 is
conflict, not permission to fetch a newer expectation and silently retry.

## Response invariants

Require exact DTO fields and plain JSON objects. Preserve decimal strings and
original ISO UTC instants (up to nanosecond fractions); validate real calendar
times and chronological ordering without rounding revisions or timestamps.
Consent must match the requested journey; completed consent cannot be sharing.
Route contexts have 1..128 unique canonical anchors. Validate documented sorted
order against the server implementation, not an assumed JavaScript UUID order.
Null context is legitimate; bound must contain a context and empty binding
outcomes must contain null. Provider-bound context lifetimes cannot exceed 15
minutes; signal grant lifetimes cannot exceed 90 seconds.
Newly received expired context/grant data must never be described as usable;
return transport data only, with no implicit eligibility claim.

Choice snapshots contain only the current complete labeled subset, validated for
canonical ordering,1..128 items,1..80-code-point label policy, closed sorted unique
categories and exact context/consent versions. Reject nonfuture/unexpired violations
and lifetimes over24 hours. Expected-path grants must match the captured anchor,
context, revision and consent generation and still be current on receipt. These
checks do not undo a server write or authorize automatic reissuance.

Signal grants must match the requested anchor, use nonempty unique closed
categories, and have ordered issued/expiry instants. Receipts must match the
requested command and one of accepted/withdrawn/superseded; withdrawal cannot
return accepted. Preserve the original received/expiry/retention times. Receipt
replay may arrive after evidence expiry, Ghost or journey completion; do not
reject a legitimate retained receipt merely because evidence is old. Validate
15-minute evidence and 24-hour retention relationships from server constants.

## Recovery contract

Every exported mutation sends exactly once. No automatic issue or bind retry.
After a lost bind response the future caller may explicitly read context, then
make a new user-intent bind. A lost signal acceptance is recovered only by an
explicit retry with the identical command and fingerprint. A consent failure
must not be presented as committed Ghost Mode; future UI must read and reconcile.
Callers cancel/discard state on account/journey changes. Responses do not prove
that the currently displayed account remains authenticated.

## Verification

Targeted mocked-fetch/stream tests exercise real parsing and request construction:
all endpoints and closed values, MAX long precision, malformed input before fetch,
cross-journey/anchor/command mismatch, null and empty outcomes, terminal receipts,
expired-evidence replay, unexpected fields, bounded streams, malformed UTF-8,
stalled CSRF/headers/body, cancellation and late completion, HTTP errors, no
redirects, no retries or persistence. Verify generated-type compatibility, lint,
formatting and affected web build. Backend contracts and default-off gates stay
unchanged; existing backend HTTP tests remain the server verification evidence.
Snapshot and serialize validated request fields, including copied coordinate
arrays, before the first asynchronous CSRF leg. Caller mutation during a delayed
request cannot change the validated submission fingerprint.
