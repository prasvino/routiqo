# Browser journal transport deadlines

Status: implemented and independently reviewed, 2026-09-19. This is a transport correction for the existing
private journal feature, not a change to its storage or editing contract.

The existing reader bounds response bytes but awaits stream reads and final
cancellation directly. Abort-ignoring adapters or a non-settling cancellation can
leave the editor waiting indefinitely. Correct that behavior while preserving
the public read/save exports, shared journal validators, exact mutation identity,
optimistic version checks, owner/journey matching and existing error statuses.

## Required behavior

- Keep the fixed same-origin journal path, account header, cookie credentials,
  no-store, redirect refusal and CSRF requirements. Explicitly reject a response
  whose redirected flag is true, even if an injected adapter ignores the option.
- Successful responses require exact status 200, JSON content type and a body.
  Bound streamed UTF-8 bytes to 32 KiB and decode strictly before shared validation.
- One 18-second operation deadline covers internal CSRF acquisition, headers,
  body reading and validation. Race pending operations against abort and timeout;
  elapsed-time checks must also stop an immediate empty-chunk stream that can
  starve timer callbacks. Caller cancellation returns a generic AbortError.
- The bounded auth CSRF helper may be reused. Cancelling the journal operation
  must return promptly and never start a later POST; the independent read-only
  CSRF request may finish within its own auth deadline. Do not widen auth exports
  or redesign shared transport merely for this correction.
- Attach handlers before reacting to synchronous cancellation from an injected
  fetch/read. Handle late rejections and discard/cancel late response bodies.
- Cancel unused error bodies and incomplete readers best-effort, without reading
  error details or awaiting cancellation. Dispose timers/listeners and release
  stream locks when possible. Cleanup failures cannot replace the original error.
- Serialize the validated edit before any asynchronous CSRF leg. Preserve its
  mutation ID, title, notes and expected version. Never retry, queue, store, log or
  mark an uncertain save as successful in transport.
- Generic failure copy must describe an attempted account save as unconfirmed,
  rather than claiming the server did not save it; a response can be lost after
  commit. Device-draft guarantees belong to the editor/storage layer.
- Preserve existing identity/schema compatibility in this correction; avoid
  unrelated shared-validator changes. The server remains authorization authority.

## Verification

Retain existing owner/acknowledgement/conflict tests. Add stalled headers and
body, delayed CSRF with no late POST, immediate caller cancellation, late failures,
hanging/rejected cancellation, invalid UTF-8/JSON, byte limit, unexpected success
status/content type, redirects and redacted error-body tests. Verify the exact
request body and no implicit retry. Run journal lifecycle/storage consumers,
types/lint/format, final TypeScript integration and web build. Real authenticated
browser and cross-device journal recovery remain release QA requirements.
