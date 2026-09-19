# Browser journey transport bounds

Status: implemented and independently reviewed, 2026-09-19. Correct the existing durable-delivery and recovery
transport without changing outbox, reconciliation, shared validators or UI behavior.

`browser-journeys.ts` currently reads whole response text before checking its
length; its mutation also constructs the body after awaiting CSRF. Replace those
gaps with bounded streaming and an immutable pre-await command snapshot.

## Required behavior

- Preserve the existing send/read/recent-history exports and delivery outcomes.
  No caller cancellation parameter, dispatch redesign or shared-schema changes
  are needed in this slice.
- Validate identities and command kind as before, then capture path, expected
  acknowledgement identity/kind/status and serialized mutation before CSRF.
- Use fixed same-origin paths, account partition headers, same-origin cookies,
  no-store and redirect refusal. Explicitly reject a redirected response.
- One 12-second operation deadline covers CSRF plus resource headers, streaming
  and validation. The existing bounded auth CSRF helper may finish its read-only
  request after the outer deadline, but no later mutation may start.
- Bound streamed UTF-8 bytes to 4 KiB for a journey and 32 KiB for recent history.
  Require exact 200, JSON content type, body and strict UTF-8/JSON before the
  existing validators. Retain response field normalization and max-20/unique-ID
  history checks. Do not add strict extra-field rejection in this correction.
- Race headers/reads against the deadline even when injected transports ignore
  abort; elapsed checks also stop empty-chunk streams that starve timers. Observe
  late failures, cancel late/unused/error bodies without reading them, and never
  await cancellation indefinitely. Dispose timers and release locks when possible.
- Send keeps existing mappings: 401/403 authentication, 409 conflict,
  408/429/5xx transient, other non-200 rejected. Redirected or malformed success,
  network failure, oversized body or timeout is transient and must not acknowledge
  the durable command. Existing auth-bootstrap status mapping is retained.
- Owner lookup retains 404 => null; that does not authorize dropping queued work.
  Preserve useful read HTTP classifications and bounded generic local errors;
  never expose transport/JSON errors containing private response data.
- No automatic retry, persistence, logging, endpoint changes or feature activation.

## Verification

Keep delivery classification, owner matching, exact retry identity and history
normalization tests. Add streamed/multibyte overflow, malformed UTF-8/JSON, wrong
success status/type, redirected responses, stalled headers/body, non-settling or
rejected cancellation, elapsed empty-stream bounds, shared slow-CSRF deadline,
no late POST and command mutation during CSRF. Confirm transient outcomes preserve
the queue through existing dispatch/storage consumer tests. Verify types/lint,
format, final TypeScript integration and web build; do not rerun backend tests for
this client-only change.
