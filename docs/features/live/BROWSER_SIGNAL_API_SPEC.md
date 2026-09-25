# Private browser Quick Signal API

Status: implemented and tested as an owner-only, separately default-off private
transport under ADR 0035. No UI or public Live projection is enabled.

> **Direction brief (2026-09-25):** Kept as a default-off transport, but web is secondary to Android. Private-only signals become public signals on Spots under per-room aliases; the 15-minute evidence lifetime becomes per-type lifetimes with "Still true?" renewal; consent coupling and the disabled-consent errors are retired. See [PRODUCT.md](../../PRODUCT.md).

## Scope and wire contract

Add ROUTIQO_LIVE_SIGNAL_API_ENABLED=false, explicit web-auth/routing/persistence
profiles, and required CatalogSignalService from the real configured catalog.
No permissive service fallback or order-sensitive ConditionalOnBean. Conditional
security allowlisting permits only these POST paths when enabled:

- /api/v1/journeys/{id}/signal-commands: exact body {anchorId}.
- /api/v1/journeys/{id}/signals/{commandId}: exact body {anchorId, value,
  contextId, routeRevision, consentGeneration}.
- /api/v1/journeys/{id}/signals/{commandId}/withdraw: exact empty object {}.

IDs in path/body are canonical lowercase nonnil UUIDs. Revisions/generations are
canonical nonnegative decimal strings up to Long.MAX_VALUE, never JSON numbers.
value is the exact lowercase snake_case spelling of the closed 17-value enum.
Categories use lowercase snake_case of the existing five-category enum. No free
text, client actor/journey override, geometry, timestamps, lifetime, category set,
client-generated grant, or extra/duplicate/missing fields. Strict bounded JSON
rejects trailing tokens, invalid encoding and coercion. Query strings rejected.

Issue calls CatalogSignalService.issue with authenticated actor/path journey and
anchor only, deriving category permissions in its existing transaction. Response:
{commandId, anchorId, contextId, routeRevision, consentGeneration, categories,
issuedAt, expiresAt}. Categories sorted; all fields private. No actor, catalog,
geometry, endpoints or other-user information. Lost issuance response is not an
idempotent retry: a deliberate later issue consumes another grant budget. No
implicit renewal or retry client is added.

Accept builds the exact existing fingerprint from path journey + strict body and
calls CatalogSignalService.accept once. Server constants are 15-minute evidence
and 24-hour receipt retention, matching the archived `../../archive/features/live/ROUTIQO_LIVE_SPEC.md`; caller cannot override.
Existing 90-second grant and 15-minute bound-context limits remain. Response:
{commandId, status, receivedAt, expiresAt, retainUntil}, where status is accepted,
withdrawn or superseded (maps private receipt lifecycle, never publication status).
Return 200 for a new acceptance or retained exact replay. Retained replay must
remain first under authority, including after Ghost, completion, context changes,
grant cleanup and evidence expiry; no timestamp renewal. Changed fingerprint
conflicts. Expired retention denies. No public evidence read/list endpoint.

Withdraw calls the existing owner-scoped terminal transition and returns the same
minimal receipt DTO. It remains callable after Ghost/completion while receipt is
retained. It is idempotent, never renews or physically erases evidence, and preserves
an already superseded terminal outcome. Missing/foreign command gives generic
conflict, without revealing another account's receipt.

## Security, rate limits and lifecycle

Reuse browser session cookie, exact single X-Routiqo-Account, Origin/Fetch-Site,
CSRF, JSON content type, 20KiB body guard, no-store and durable peer limits. No
bearer fallback. API depends only on catalog-aware facade, never raw signal store
or context replacement. Keep current architecture rules.

Add separate durable account request budgets using AuthRateGate: signal-issue-request
30/min, signal-accept-request60/min, signal-withdraw-request30/min. These cover
malformed semantic attempts and retained retries without charging new evidence.
Authenticate then parse/validate, reserve request budget, invoke facade; budget
failures sanitize to 503. Existing storage budgets remain exactly 10 successful
grants and 5 new acceptances per actor/minute, transactionally charged. Retained
replay/withdrawal do not charge those storage budgets. Separate withdrawal request
quota prevents new-submission exhaustion from using its account quota. Shared peer
budget remains. Do not nest authority transactions or move catalog/consent checks
out of storage. No provider request is made by these endpoints.

Bodyless no-store 400 invalid input, 401 session/account mismatch, 403 browser/security
rejection, 404 absent/foreign journey, 409 signal denial/conflict/expired or missing
command, 413 oversized, 415 media, 429 quota with Retry-After60, 503 authority/session/
rate infrastructure unavailable. No raw persistence/provider diagnostics or record
strings in output/logs. Unknown methods/subpaths remain denied. Explicitly gated
pending public hourly/per-category abuse limits, moderation, revocation delivery,
real catalog/provider configuration and cohort-safe publication. Private acceptance
is not approval to enable social output.

No offline report queue, automatic retry, UI, signal publication, scheduler, new
migration, flag activation, user database operation, push or deployment. Same exact
command/fingerprint recovery cannot manufacture a new signal ID or renew evidence.

## Verification and docs

Real HTTP with disposable PostgreSQL and actual journey/consent/binding/catalog/
signal services; use bounded loopback Valhalla only to establish validatedcontext.
Synthetic identity verifier is allowed at external Google boundary, not mocked
ownership/consent/catalog. Test default-off denial and required enabled composition,
issue/accept/replay/withdraw, minimization/string precision/closed enum mapping,
strict parsing, security/account isolation, foreign/missing command normalization,
request+storage quotas and no charge on retained replay, changed fingerprint,
Ghost/completion replay versus new acceptance, expiry/grant cleanup/retention denial,
and concurrent duplicate HTTP acceptance with one persisted receipt/budget charge.
Cover session/request-rate/authority failure sanitization and zero partialwrites.
Retain existing catalog/domain/race tests; avoid duplicate giant test harnesses when
small test helpers can safely be reused without refactoring unrelated suites.

Add exact proxy allowlists for the three POST leaves, 8s/64KiB standard limits,
no redirects/retries; focused negative proxy tests. OpenAPI and regenerated TS
models reflect required fields, nullable-free receipt, enums and decimal strings.
Run focused Java/TS then full core check/bootJar, contracts/format/types/lint/workspace
tests, secrets/diff; independent review. Preserve preview and preexisting next-env
change; no dependency reinstall or production frontend build. ADR0035 and current
Live/catalog/storage/roadmap/security/threat/setup/status/checkpoint updates must
clearly separate disabled private ingestion from public LIVE release readiness.
