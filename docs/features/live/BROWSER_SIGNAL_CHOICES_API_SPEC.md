# Private browser signal choices and expected-context issuance

Status: implemented, tested and independently reviewed, 2026-09-19. No public
LIVE output or UI is enabled; both server flags remain default off.

> **Direction brief (2026-09-25):** Kept as a default-off transport for the Spot signal picker; web is secondary to Android. On-consent requirements and consent-version matching are retired (one Spot-passage opt-in plus Ghost Mode); the "private" and "no public output" framing no longer applies to Spot signals. See [PRODUCT.md](../../PRODUCT.md).

## Scope and compatibility

Expose ADR 0043's minimized owner choice reader and ADR 0044's mandatory
expected-context issuance through two new leaves:

- GET `/api/v1/journeys/{id}/signal-choices`
- POST `/api/v1/journeys/{id}/signal-commands/expected-context`

Both require `ROUTIQO_LIVE_CHOICE_API_ENABLED=true` AND the existing
`ROUTIQO_LIVE_SIGNAL_API_ENABLED=true`, with web-auth/routing/persistence profiles
and real configured catalog/authority dependencies. Absent/false either flag
denies both leaves. Add the new flag as false in the example only. Never activate
it locally or add test identity/provider fallbacks to production configuration.
Use explicit gated configuration for the reader with required real catalog and
participant beans; no order-sensitive ConditionalOnBean or permissive fallback.
The security matcher and controller must both enforce the flag conjunction.

Legacy `/signal-commands` remains strict anchor-only with current-authority
semantics. It must not accept optional expected fields or serve as a fallback.
Existing acceptance, retained replay, withdrawal and their flags stay unchanged.
No new persistence, provider requests, public projection, maintenance or UI.

## Read contract

Return exactly `{contextId, routeRevision, consentGeneration, issuedAt, expiresAt,
choices}`. `choices` contains 1..128 canonical UUID-string-ordered objects, each
exactly `{anchorId, displayLabel, categories}`. Categories are sorted unique
members of the existing closed category enum. Revisions/generations are canonical
nonnegative decimal strings bounded by Long.MAX_VALUE; never JSON numbers.
Times are UTC ISO instants, retain precision, with positive lifetime <=24 hours.

Reuse PrivateAnchorChoiceService inside its one owned-journey callback. Deny the
whole snapshot when consent, journey, restriction, time, provenance or any label
is unavailable; no partial result, empty-list fallback or raw catalog access.
Return generic bodyless409 for unavailable choices and404 for missing/foreign
journey. Do not return actor/journey IDs, catalog version, coordinates, endpoints,
geometry, contribution counts, trust claims or actual values in error responses.
DTOs/diagnostics are redacted. Labels remain plain text, not HTML/links/queries.

Reads reserve a separate durable `signal-choice-read-request` account budget of
30/minute. They do not issue grants or consume contribution budgets. Preserve
the existing shared peer guard. Authentication/account checks precede domain work.

## Expected issuance contract

POST accepts exactly `{anchorId, contextId, routeRevision, consentGeneration}`;
all mandatory. Canonical lowercase nonnil UUIDs and exact decimal strings,
strict UTF-8 JSON, duplicate/extra/missing/null/type/coercion/trailing rejection.
Pass authenticated actor, path journey and validated expectation to
`CatalogSignalService.issueExpectedContext` once. Never call legacy issuance or
pre-read choices to supply missing expected values. Return the existing minimal
grant DTO. Mismatch is generic bodyless409 with no current tuple disclosed.

Share the EXISTING `signal-issue-request` 30/minute account budget with legacy
issuance so the extra route cannot double request allowance. Successful grants
also retain the same transactional storage budget; no new contribution quota.
Lost issuance is non-idempotent and must not cause automatic retry.

## Browser security and errors

Reuse session cookies, exact single X-Routiqo-Account, Origin/Fetch-Site, POST
CSRF/JSON/20KiB guards and no-store on successes and failures. No bearer fallback.
Strict method/path allowlists on backend and proxy; reject queries/extra suffixes.
Private read requires session/account; POST additionally requires existing CSRF.
Use existing bodyless400/401/403/404/409/413/415/429/503 meanings, Retry-After 60
for rate limits, and redacted session/rate/authority infrastructure failures.

The proxy allows only exact new method/path combinations. Choice responses have
a bounded 256 KiB cap (128 potentially escaped Unicode labels); other LIVE limits
remain unchanged. Preserve timeout/redirect/no-store/account forwarding behavior.
No public configuration data, new cookies, unbounded buffer or external request.

## Client boundary

Generate OpenAPI types. Add explicit read and expected-issuance functions using
existing bounded LIVE transport, one 12-second deadline covering CSRF/headers/
stream/validation, cancellation and redacted errors. Apply the 256 KiB response cap
only to the choice GET, retaining 64 KiB elsewhere. Copy/validate inputs before
any await; do not store snapshots or automatically fetch, retry, refresh or queue.

Validate exact object fields, IDs, string-long precision, unique canonical
choice/category order, 1..128 choices, label policy, valid nonfuture/unexpired
snapshot times and 24-hour lifetime at receipt. Match returned grants to captured
anchor AND expected context/revision/generation; do not accept a newer tuple as
equivalent. Existing grant category/lifetime checks remain. Require expected-path grants
to be nonfuture and unexpired at client validation time as well as <=90 seconds
in lifetime. Reject without automatic retry; the server may already have charged
issuance. Cancellation cannot undo issuance; no claim otherwise. UI integration
is a later reviewed slice.

## Required evidence

Complete ADR 0044's expected-path consent-winning race and insertion-failure
rollback tests before exposure. Use real HTTP/PostgreSQL/current authority for
the new controller: synthetic Google verifier/loopback provider allowed only at
external test boundaries. Test default/absent/partial flags, real composition,
owner/account isolation, minimization/no-store, missing labels/provenance,
suspension/completion/expiry, strict JSON and exact long values, CSRF/origin,
rate sharing, no grants from reads, stale expected tuple no storage mutation,
generic errors and legacy compatibility. Test largest Unicode-label response.

Proxy/client tests cover exact leaves/methods, queries/suffix denial, response
bounds, copied input, returned-tuple mismatch, malformed/expired snapshots,
cancellation/deadline and zero legacy fallback/retry. Run generated-contract
checks, relevant workspace gates, Java check/bootJar and independent review.
No live OAuth/provider/production/device verification is claimed by test fixtures.

## Verified evidence

Full Java check/bootJar: 416 tests/57 suites, zero failures/errors/skips. Full
workspace check: 400 TypeScript tests/46 files, types/lint/format/contracts passed;
web production build and secret scan passed. Independent backend/client/contract
review approved after strict UTF-8 decoding and quoted YAML descriptions were
corrected. 54 targeted backend and 62 targeted client/proxy tests passed first.

Maximum Unicode coverage combines actual DTO/Jackson serialization and escaped
client/proxy response tests, not a largest-catalog HTTP fixture. Existing reader
domain tests cover suspension/missing labels; HTTP tests exercise real composition.
Client freshness checks do not establish synchronized device/server clocks.
