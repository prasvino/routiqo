# Browser route-context API

Status: implemented and tested. Private owner-only transport, default off.

> **Direction brief (2026-09-25):** Kept as an owner-only transport, but web is secondary to Android and the archived route-binding UI is not rebuilt; Spots ahead should load automatically on journey start. "No offline queue" is replaced by the PRODUCT.md offline rule for posts, signals and answers. See [PRODUCT.md](../../PRODUCT.md).

## Scope and contract

Add GET and POST /api/v1/journeys/{id}/route-context behind the independent
ROUTIQO_LIVE_ROUTE_BINDING_API_ENABLED=false flag and explicit web-auth, routing,
persistence profiles. Enabling requires the existing real configured binder;
missing catalog/resolver dependencies fail startup rather than supplying a fallback.
Keep security method/path allowlisting conditional on the flag. No signal endpoint,
UI, catalog activation, offline queue, timer or publication in this phase.

GET reads the current owned context using existing LiveRouteContextService.read
and returns {context: null | Context}. It does not renew, enable sharing or bind.
Current private context may be read after Ghost; no discoverability follows.
Expired/completed context is null. Missing/foreign journey is the same 404.

POST accepts exactly mode, origin, destination, alternativeIndex, expectedContextId.
mode is driving/walking/cycling; origin/destination each exactly two finite JSON
numbers [longitude,latitude] within existing RouteRequest bounds. alternativeIndex
is a JSON integer 0..2 (no floating/string coercion). expectedContextId is required,
null or canonical lowercase nonnil UUID. Reject unknown/missing/duplicate fields,
trailing content, malformed encoding, invalid types, query strings and geometry,
anchor lists, actor IDs, catalog versions or lifetime overrides. Route data stays
in memory. Use bounded strict parser and redacted DTO/error strings.

Invoke RouteBindingService.bind once, preserving its two transactions, durable
attempt fencing, consent/context checks and account budget. Do not retry internally.
Return {status: bound|no_route|no_eligible_anchors, context: Context|null}; only bound
has context. Empty outcomes preserve previous context; callers can GET to recover.
Context exposes only contextId, revision (canonical decimal string), anchorIds
(sorted opaque IDs), issuedAt and expiresAt. No actor ID, catalog version, geometry,
endpoints or other-user information. All output is private no-store and not a
physical presence proof, admission grant, or permission to publish.

A lost POST response is recovered by GET, followed by an explicit user-intent bind
with the observed expectedContextId if wanted. Never replay automatically using a
fresh expectation; GET must not create a write. No automatic/offline bind queue.

## Security and failure behavior

Reuse cookie sessions, exact single X-Routiqo-Account match, Origin/Fetch-Site,
CSRF, JSON content type, 20KiB body bound, and peer rate gate. No bearer fallback.
Authenticate before provider work and preserve authority rechecks after it.
GET uses shared database account rate category route-context-read-account 60/min;
POST keeps existing route-binding-account 10/min (do not double-charge). Controller
must not call trusted context replace or low-level signal storage; enforce the
no-raw-context-replace API boundary with ArchUnit or equivalent meaningful rule.

Bodyless no-store 400 invalid, 401 session/account mismatch, 403 browser/security
rejection, 404 missing/foreign journey, 409 stale/disabled-consent/completed/binding
conflict, 413 oversized, 415 media, 429 budget with Retry-After60, 503 provider or
persistence unavailable. Use existing binder exceptions; do not leak nested causes,
input values or database/provider messages. No outside-coverage promise if binder
already sanitizes provider failures as 503. GET infrastructure/session/rate failures
also sanitize. Unsupported methods/subpaths remain denied.

## Verification and documentation

Real HTTP security + disposable PostgreSQL and bounded loopback Valhalla fixture,
actual production services (no mocked authority). Cover default-off and enabled
composition, authenticated bind/read/recovery, strict input/no-provider on rejection,
owner/account isolation, CSRF/origin/header/cookie failures, expiry/completion,
no-route/no-anchor preserving context, stale expectation/Ghost denial, quotas and
provider/unavailable sanitization. Existing core binding race tests remain; add one
HTTP request with provider blocked and consent revoked to prove no stale bind.
Assert DTO minimization/no-store and no output mutation on invalid input.

Add exact GET/POST auth-proxy allowlist entries/tests (no broad prefix). Define
OpenAPI and regenerate TypeScript transport models with string revisions. Run
focused Java/TS then full core check/bootJar, contract drift, relevant workspace
format/types/lint/tests, secret/diff checks; independent security review. Preserve
preview port3000 and existing next-env.d.ts generated diff; no dependency reinstall
or production frontend build required for this transport-only change.

Create ADR0034 and update current Live/context/binding/roadmap/security/threat docs
and restore checkpoint. Real OAuth, regional provider/catalog operations, signal
transport, moderation, revocation delivery and cohort-safe LIVE remain gates.
