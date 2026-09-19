# Browser routing transport bounds

Status: implemented, tested and reviewed, 2026-09-19. Harden the existing private place
search and calculation client without changing routing providers or UI semantics.

## Contract

- Keep `calculateBrowserRoute`, `searchBrowserPlaces`, `BrowserRoutingError` and
  the coverage message. Preserve existing validators and caller abort behavior.
- Validate account and input before networking. Copy and serialize the validated
  request before the first await, including both coordinate tuples, so mutable
  caller data cannot alter an operation while CSRF is pending.
- Fixed same-origin paths, same-origin credentials, no-store, account partition
  and CSRF headers, redirect refusal. Reject an explicitly redirected response.
- One 18-second deadline covers CSRF, route/search response headers, streamed
  body and validation. CSRF may finish its own read-only operation after an outer
  cancellation, but must never launch a later routing/search POST.
- Race asynchronous steps against caller abort and the shared deadline, including
  adapters that ignore abort. Recheck elapsed wall time around stream reads so
  empty resolved chunks cannot starve the timeout indefinitely.
- Require exact 200, JSON content type, a body, valid UTF-8 and JSON, then existing
  shared validators. Retain 1 MiB route and 256 KiB search streamed byte limits.
  Reject excessive declared content length early; it is not a substitute for
  counting actual bytes. Do not add stricter response-field policy in this slice.
- Observe late rejections and cancel late, unused and error bodies without
  reading them. Cancellation is best effort and must never extend the operation;
  release stream locks when possible and remove timers/listeners after completion.
- Keep useful HTTP status classifications and generic safe messages. Caller
  cancellation remains AbortError; malformed/redirected/unexpected success,
  deadline and local transport failures become the generic 503 routing error.
  Never display/log raw response, JSON errors, coordinates, query or credentials.
- No automatic requests/retries, persistence, network changes or feature flags.
  Existing planner retains successful route estimates during failed/cancelled
  recalculation and connection loss.

## Verification

Targeted tests exercise immutable route input during slow CSRF, search input,
caller abort before and during each asynchronous stage, no late POST, a shared
deadline after slow CSRF, stalled headers/body, abort-ignoring transport, late
body disposal, rejected/non-settling cancellation, streamed multibyte overflow,
content-length/type/status/redirect rejection, malformed UTF-8/JSON, elapsed
empty-chunk streams, safe status mapping and existing valid route/search parsing.
Run affected UI regressions, full TypeScript checks and web build. This is not
live provider verification and requires no backend modification.
