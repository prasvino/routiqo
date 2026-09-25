> **Archived 2026-09-25.** Per-journey consent and private LIVE contribution flows, replaced by one Spot-passage opt-in, simple Ghost Mode and public Spot signals. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Browser journey consent transport

Status: implemented and verified behind a default-off flag. Expose only the authenticated owner's private
consent state behind an explicit default-off server flag. This does not enable
signal ingestion, presence publication, LIVE UI or offline consent dispatch.

## Contract and authority

Under the existing web-auth security chain add GET and POST at
`/api/v1/journeys/{id}/consent`, enabled only when
`ROUTIQO_LIVE_CONSENT_API_ENABLED=true`. Both controller registration and the
security allowlist must respect the flag; absence/false leaves the route denied.
Default preview and native-only deployments continue to deny this browser path.
Reuse current cookie authentication, exact single X-Routiqo-Account header,
Origin/Fetch-Site checks, CSRF and body bounds. No bearer fallback or actor field.

GET calls PresenceConsentService.read; POST calls submitIntent exclusively, never
legacy change. Current enabled-account and owned-journey authority remains inside
the application transaction. Response contains only journeyId, generation,
sharing, journeyActive; redact response/request record diagnostics.

Generation is a canonical decimal STRING on the wire, in both responses and
POST expectedGeneration: 0 or a nonzero digit followed by digits, at most
9223372036854775807. Preserve exact long values beyond JavaScript safe integers.
POST requires exactly expectedGeneration and sharing (a JSON boolean). Reject
unknown/duplicate/missing/null fields, numeric or coerced generation values,
leading zeroes/signs/whitespace/exponents, overflow, arrays and trailing JSON.
Validate UUID paths strictly, reject nil IDs, and use cause-free generic errors.
Use the established bounded JSON request guard, never unbounded body reads.

Map owner/missing journey to generic 404, invalid input to 400, session/account
context denial to 401, consent conflict to 409, transient persistence/rate-store
failure to bodyless 503. Preserve security 403/413/415 responses. Every success
and failure is no-store. Unsupported methods and disabled routes stay denied.
Do not leak consent state in conflicts, exception logs, or response errors.

## Abuse, retries and scope

Retain the existing database-backed browser peer budget and add account budgets
through AuthRateGate: consent-read-account 60/minute, consent-enable-account
10/minute, consent-disable-account 20/minute. Budgets span journeys and sessions;
enables cannot consume the separate disable account budget. Exhaustion returns
429 with Retry-After:60. Database failures deny with sanitized 503, including the
peer guard before controller execution; no process-local or permissive fallback.
This does not promise that off bypasses transport/peer throttling. Callers must
confirm the committed state, and may never display failed revocation as success.

POST returns current committed intent state. Repeating off can advance generation;
repeating old on conflicts. Never automatically refresh a generation and retry on.
No command receipt, background dispatch, offline queue, cache invalidation or claim
of public Ghost enforcement is added. Production activation remains gated on real
OAuth, UI consent/reconciliation and applicable delivery-revocation readiness.

## Integration and verification

Update OpenAPI and regenerate TypeScript schema; use string generations end to
end. Extend the existing same-origin proxy's narrow allowlist for this exact path
and GET/POST only; retain its cookie/header/body/origin/error behavior. No UI is
mounted. Update configuration examples with false as the default, ADR0030, current
roadmap/build/security/threat documents and restore-point notes.

Test real configured HTTP/cookies/CSRF plus PostgreSQL consent authority, with only
the existing test-only identity verifier pattern. Cover default-off security,
enabled owner roundtrip, same-state off fencing and stale enable conflict,
completed/other/newer journey isolation, account-context duplication/switch,
missing/expired/revoked sessions, origin/CSRF, strict input and exact MAX generation,
body bounds, unsupported methods, distributed account budgets across sessions and
journeys, separate disable budget, persistence/peer-rate failures and no-store
redacted errors. Exercise actual submitIntent, not a mocked consent service.
Add focused proxy tests and contract generation/drift checks; run relevant Java,
TypeScript/lint/test/build checks. Use disposable PostgreSQL only. No credentials,
user database migrations, flag activation, timer, deployment or push.
