# Native private route preparation and recovery

> **Direction brief (2026-09-25):** The explicit "check / prepare private route" ceremony is archived; on Android the Journey map should show Spots ahead automatically on journey start. The two-transaction binding transport it reuses is kept, while the consent rechecks and native consent prerequisite are retired. See [PRODUCT.md](../../PRODUCT.md).

Scope: complete the next private LIVE prerequisite after native consent and route
planning. Explicitly check/prepare a selected route for the verified active journey.
Reuse existing two-transaction binding authority; no new public output, GPS,
presence grant, signal issuance, provider/catalog deployment or persistence model.
Browser contracts in BROWSER_ROUTE_BINDING_API_SPEC.md and
`../../archive/features/live/BROWSER_ROUTE_BINDING_UI_SPEC.md` (archived) remain the protocol reference.

## Backend boundary

- GET/POST `/api/v1/native/journeys/{id}/route-context`, gated by independent
  `ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_API_ENABLED=false` with
  native-auth/routing/persistence. Exact method/path guard and security grants,
  native bearer and single exact account header; no browser ambient auth/query.
- Require canonical lowercase nonnil journey/context UUIDs. POST strict 20 KiB
  UTF-8 JSON exactly mode/origin/destination/alternativeIndex/expectedContextId,
  existing RouteRequest bounds, integer alternative 0..2, required expected null
  or canonical ID; reject duplicate/extra/missing/coerced/trailing data.
- Read through LiveRouteContextReader; bind exactly once through RouteBindingService.
  Keep post-provider current consent/context/newest-attempt checks and no DB
  transaction across provider I/O. No trusted raw context replace from API.
- Read quota shares `route-context-read-account` 60/min; POST uses existing binder
  `route-binding-account` 10/min only once. Native peer/account budgets also apply.
  Same minimized string-revision Context/read/binding DTOs as browser, redacted
  diagnostics and no-store/bodyless sanitized errors, Retry-After60 on 429.
- Widen existing resolver composition to native-auth+routing without bypassing
  its default-off resolver flag or configured catalog/provider validation. Missing
  binder dependency when exposure enabled must fail startup, never fake readiness.
  Default-off test must include configured routing/resolver dependencies.
- GET never binds/renews and remains recovery of last observed private context;
  completion/expiry yields null and missing/foreign ownership stays indistinguishable.
  Empty bind outcomes preserve old server context; abort does not undo a bind.

## Native transport

Add only this exact leaf GET/POST to JS/Kotlin allowlists. Exact200 JSON/strict
UTF-8/64KiB responses; 12-second read and 30-second bind deadlines include credential
access and validation. Kotlin bind call/read bounds accommodate the existing bounded
two-transaction provider operation while retaining a fixed overall call timeout.
Use portable AbortError, no browser-only globals. Snapshot validated coordinates,
alternative and expected ID before any await. Current-account/revision and caller
cancellation checks prevent late dispatch/results; no automatic retry/outbox.

Strict response shapes: Context has contextId, canonical decimal string revision
0..Long.MAX_VALUE, 1..128 sorted unique nonnil anchor UUIDs, canonical UTC instants
with 1..9 fractional digits permitted, positive lifetime <=15 minutes. Only bound
has a nonnull context. GET has exactly {context}. Do not display internal IDs or
coordinates. Retain nanos-safe time validation and recheck current expiry in UI.

## Synchronous UI authority and selected input

- Separate default-off EXPO_PUBLIC_ROUTIQO_NATIVE_LIVE_ROUTE_BINDING_ENABLED.
  Eligibility requires current verified account, confirmed active journey, no
  queued lifecycle action, no restoration/deletion/busy work, online and foreground
  focused Trips. Route planning remains independently usable without consent.
- Consent panel/controller publishes a memory-only account/journey/generation
  confirmation only after active/on is validated with no busy/uncertain/terminal
  state. Invalidate synchronously at every operation start (including reads),
  cancellation, renewal, lifecycle loss and unmount, before React effects. Pending
  Stop must invalidate an in-flight bind immediately. Preserve consent uncertainty.
- Planner publishes copied selected route input from a successful calculation,
  mode/endpoints/alternative plus a monotonically changing epoch and live getter.
  Clear private preparation synchronously on edits, mode/swap, calculation start,
  selected-alternative change, result replacement, account/lifecycle loss. Retained
  ordinary directions do not silently reauthorize private preparation on reconnect.
- Parent/coordinator holds current synchronous getters and monotonic invalidation
  epochs with stable callbacks. Do not build effect loops or depend on delayed
  reactive props for dispatch/result acceptance. Compare captured account/journey,
  exact consent generation/epoch, route-selection epoch, session epoch and lifecycle.
  Keep state private to the scoped identity; account loss hides it synchronously.

## Explicit interaction

Initially unknown. Check private route performs one GET and records observed exact
ID or explicit null; missing observation differs from observed null. It never claims
the displayed route was prepared, because GET has no route-input fingerprint and
an earlier lost POST could still commit. Prepare private route sends one POST with
the observed expectation and copied selected route. Explain that it sends endpoints
again for a fresh calculation which may differ from the displayed estimate.

A validated current bound response acknowledges only that request until expiry.
No route/no eligible anchors, failure/conflict/cancellation clear local readiness
and expectation and require explicit Check again; never claim old context removed.
Every read also clears prior acknowledgement. Reconnect/focus/renewal/timers issue
no requests. A bounded expiry timer only clears local observation/readiness; recheck
issuedAt<=now<expiresAt before write and acceptance. Timer never renews or fetches.

Use accessible native controls/shared tokens, >=48dp targets, wrapping status,
loading/unknown/empty/offline/conflict/rate/session/expired states. Place private
preparation next to consent after core travel utility. No modal prompt, animation,
driving interaction, opaque IDs, route-membership/public safety claim or global Ghost
claim. Explicit route preparation is not consent enablement or signal acceptance.

## Verification

Real HTTP/PostgreSQL/provider-fixture integration tests: enabled/default-off native
composition, auth/owner/account isolation, strict input, recovery/bound/empty outcomes,
exact expectation, shared quotas, sanitized failures, completion/expiry, and provider
blocked while consent is revoked (no stale bind). Preserve existing binding races
and API architectural rules. Independent security review before commit.

Native tests: immutable inputs, string/timestamp/shape bounds, deadline/cancellation,
absent DOMException, single-flight, unknown-vs-null, exact expectation, consent read
and Stop invalidation before effects, route edits/alternative/recalculation changes,
late results/session changes, expiry and no implicit requests. Mounted emulator
fixtures exercise actual composition/recovery/offline/background/account and small
large-text UI; remove fixtures before final APK/build/startup inspection. Run full
TS/core checks, format/types/lint/contracts/secrets and update `../../archive/validation/NATIVE_LIVE_PENDING.md` (archived).
Real OAuth, regional catalog/provider and physical-device validation stay pending.
