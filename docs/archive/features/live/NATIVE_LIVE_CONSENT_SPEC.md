> **Archived 2026-09-25.** Per-journey consent and private LIVE contribution flows, replaced by one Spot-passage opt-in, simple Ghost Mode and public Spot signals. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Native private LIVE consent controls

Scope: the next complete native LIVE prerequisite after journal editing. Implement
owner-only consent GET/POST, strict Android transport and explicit check/allow/stop
controls inside a confirmed active journey. Reuse the current privacy application
service and revocation-precedence protocol. This is not a public LIVE list,
discoverable presence, global Ghost Mode, route preparation or Quick Signals.
Those follow separately; no existing public-release gate is changed.

## Authority and API

- Add GET/POST `/api/v1/native/journeys/{id}/consent` under `native-auth`, separately
  gated by `ROUTIQO_NATIVE_LIVE_CONSENT_API_ENABLED=false`. Controller and security
  allowlist must both respect the gate. Default and browser-only deployments deny
  the native path. Unsupported methods, ambient browser headers and all query
  strings remain denied by the native guard.
- Use its verified bearer account and exactly one matching X-Routiqo-Account.
  Use PresenceConsentService.read/submitIntent; never legacy change. The service
  continues to enforce enabled account, ownership, completion and concurrency.
- Reuse the strict existing consent intent parser (or extract a package-local
  shared parser without relaxing browser behavior): exactly expectedGeneration
  and sharing, canonical decimal STRING 0..9223372036854775807 and JSON boolean.
  Reject duplicate/unknown fields, coercion, trailing JSON, invalid/nil/noncanonical
  path UUIDs and oversized bodies. Generic bodyless errors; no request/state logs.
- Response is exactly journeyId, generation, sharing, journeyActive. Sharing cannot
  be true for a completed journey. Preserve the decimal string without Number
  conversion. Return 200 only on validated success, no-store on all outcomes.
- Share the existing PostgreSQL account quotas with browser consent:
  consent-read-account 60/minute, consent-enable-account 10/minute and
  consent-disable-account 20/minute. Rate-store/persistence failures return
  sanitized 503; limited writes return 429/Retry-After 60. Stop still depends on
  transport/peer availability; a failed request never confirms stopped state.
- Update OpenAPI/generated types and false-valued configuration examples. No new
  server tables, provider, deployment or feature activation is required.

## Native transport

Add only the exact consent GET/POST path to JS/Kotlin/server allowlists. Require
JSON, valid UTF-8, exact status 200 and a 64 KiB response cap. Apply one 12-second
operation deadline including credential access and bridge response, honor caller
cancellation and reject late results even when the bridge ignores cancellation.
Validate canonical non-nil 36-character identities and exact request/response
fields before dispatch/return; snapshot input before awaits. Preserve generation
strings and fence account revision changes before every leg and after completion.
Validate mutation acknowledgements against the submitted intent: enable must return
active/sharing with generation exactly expected + 1; active stop must return off
with a greater generation, except saturation at MAX. Completed stop returns the
inactive/off view and may have a synthetic generation independent of the request.
No retries, storage, outbox, background delivery or fallback to browser cookies.

Transport module interface for integration:
`apps/mobile/src/features/live/native-consent.ts` exports NativeLiveConsent
{journeyId, generation, sharing, journeyActive}, readNativeConsent(identity,
accountId, journeyId, signal?) and submitNativeConsent(identity, accountId,
journeyId, {expectedGeneration, sharing}, signal?). Errors reuse NativeHttpStatus
and NativeSessionRequired where appropriate, otherwise sanitized Error/AbortError.

## Native interaction and lifecycle

- Build-time `EXPO_PUBLIC_ROUTIQO_NATIVE_LIVE_CONSENT_ENABLED=false` controls UI
  availability only, never server authority. Mount within Trips for the verified
  account's confirmed active journey, not a queued start. Preserve the four tabs.
  Hide/disable contribution controls during pending/blocked lifecycle work,
  refresh, restoration or deletion; keep same-scope uncertainty while temporarily
  unavailable. Account/journey changes clear private state.
- Start with unknown state and explicit Check LIVE settings. Mount, reconnect,
  foreground/focus and session renewal must not issue requests. Stop private
  contributions is permitted while unknown, using last generation or `0`.
- Allow private contributions requires a confirmed active/off snapshot, no
  uncertain mutation, and generation below MAX. Send the exact observed string
  once. Never silently refresh/rebase/retry or treat a read as a write fence.
- Set mutation uncertainty before dispatch; after failure or cancellation preserve
  it across same-account/journey checks, offline/foreground changes, refresh and
  verified session renewal. Only a validated explicit Stop response clears it.
  A successful Allow may establish its confirmed state; an earlier uncertain
  operation cannot be overwritten by a delayed response. A completed response is
  terminal for that mounted identity and disables enabling.
- On Android AppState background/inactive and Android focus blur, cancel requests
  and clear confirmation. Restore visibility without auto-fetch. Also invalidate
  when Trips loses navigation focus. Do not key the controller by session epoch or
  busy state; uncertainty must survive temporary eligibility changes.
- Use a synchronous single-flight guard and operation revision. Guard current
  account/journey/eligibility before dispatch and result acceptance. Authentication
  denial clears provider identity/private UI. Cancellation does not undo a write.
  No persisted consent authority; after process death a check is only last-observed
  state, never a claim of global privacy protection.
- Explain briefly that private contribution preparation does not publish location,
  make the traveller discoverable or delete retained receipts. Public LIVE remains
  unavailable. Use “Last checked” states, not a durable privacy guarantee. Distinct
  offline, unknown, pending, conflict, unavailable, rate-limited and session states.
- Shared tokens, native accessible buttons with disabled states, at least 48 dp
  touch targets, readable announcements/wrapping and no new animation. No IDs or
  generations in product copy. No prompts while driving/background sensing.

## Verification and completion

Real HTTP/PostgreSQL tests cover owner isolation, disabled route/security gate,
strict input, exact MAX generations, stale enable and repeated stop fencing,
completion, rate separation/shared budgets, and sanitized failures. Native tests
cover exact bounds/status/identity/deadline/cancellation, immutable request input,
late session changes, controller uncertainty, terminal responses, no auto requests,
single-flight and same-scope invalidation. Exercise the mounted eligibility path,
native view actions, background/offline/focus and narrow large-text rendering.
Clearly label temporary emulator fixtures and remove them before final build.
Run relevant full regressions, types/lint/format/contracts/secrets and Android build,
install/startup logs/screenshot. Independent security/privacy review precedes the
feature commit. Record real OAuth/staging and physical-device checks in
`docs/validation/NATIVE_LIVE_PENDING.md`. No production-readiness claim.
