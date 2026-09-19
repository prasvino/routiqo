# Browser authentication transport hardening

Status: implemented and independently reviewed, 2026-09-19. Preserve existing public client function signatures,
response normalization, UI behavior and server/proxy contracts.

The browser client must refuse redirects for all auth requests, especially the
Google token exchange. Use only the existing fixed relative paths, same-origin
cookies and no-store. Explicitly reject a redirected response even if an injected
transport ignores redirect:error. Never read error bodies or attach their details
to BrowserAuthError; preserve existing meaningful HTTP status handling (including
session 401 => null, rate 429 and deletion 428).

Replace unbounded response.json() with one internal bounded JSON path. Successful
JSON endpoints require status 200, JSON content type, a present body, strict UTF-8,
valid JSON and at most 16 KiB of streamed bytes. Logout/account-delete require 204;
an unexpected successful status must not be treated as confirmed mutation success.
Keep established field normalization; this slice does not tighten unrelated schema
choices or change timestamp/UUID formats.

A 12-second request deadline covers headers and all body reading. For operations
that internally obtain CSRF (logout, renewal, deletion), one shared deadline must
cover both CSRF and the mutation; no delayed CSRF result may launch a POST after
expiry. Other explicit caller-supplied-CSRF operations retain one request deadline.
Use abort and discard late results, even when an injected fetch/body ignores abort.
Reader cancellation must not hang the operation. Clear timers/listeners, handle
late promise failures and preserve cause-free generic errors. No automatic retry,
credential persistence, logging, endpoint activation or auth bypass.

Verify existing auth tests plus large/chunked/malformed UTF-8/JSON bodies, missing
body/type, redirect/error outcomes, stalled headers/body/CSRF, unexpected success
statuses, exact 204 acknowledgements, no late POST and no retry. Run affected auth
consumers' tests, web types/lint/format and web build during final integration.
