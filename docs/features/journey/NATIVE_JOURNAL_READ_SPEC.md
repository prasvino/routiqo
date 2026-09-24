# Native trip journal read

Status: implemented, 2026-09-24. This is a private read-only transport slice behind the existing opt-in `native-auth` profile. It does not add a journal screen or editing. Verification and remaining integration work are recorded in `docs/validation/NATIVE_JOURNAL_PENDING.md`.

## Contract and authority

`GET /api/v1/native/journeys/{id}/journal` accepts one canonical lowercase UUID path ID, one native bearer and exactly one matching `X-Routiqo-Account`. The native guard denies all query strings, cookies, Origin and Sec-Fetch-Site headers. Its existing database-backed peer and account limits apply. Only this exact GET route is added to the server, JavaScript and Kotlin allowlists. The response is no-store with no cookie, redirect or CORS grant. Errors have empty bodies: 400 invalid ID, 401 missing or mismatched identity, 403 prohibited transport/method/path, 404 absent or foreign journey, 409 active trip or commute, 429 rate limit.

The controller delegates to the existing `JournalService.get`, which checks owner access and completed TRIP eligibility, then returns the existing private `TripJournal` shape. A 36-character malformed UUID that reaches the controller receives 400; paths outside the guard's fixed 36-character shape receive 403. An absent annotation has empty title/notes, version 0 and null `updatedAt`. No migration, cache or new persistence is needed. Browser journal policy and editing remain unchanged.

## Client boundary

The reader accepts canonical non-nil account and journey IDs, invokes only the fixed HTTPS native GET with no body, and requires exact HTTP 200 and at most 32 KiB of response bytes. The Kotlin bridge validates JSON content type and strictly decodes UTF-8, retaining its 12-second call timeout and no-cookie/no-redirect policy. A caller abort or 12-second JavaScript deadline settles the read promptly even if the native promise is late. Cancellation prevents dispatch after a pending credential read; an already-started native call may continue within its own timeout, with its result discarded. The response must have exactly the known outer, journey and annotation keys, pass the shared journal validator, and match the requested journey ID. A late response after cancellation, timeout or account generation change is discarded. This slice does not retry, store, merge into snapshots, activate a journey or display UI.

## Acceptance

- Real HTTP tests prove owner/completed-trip success, empty annotation, unauthenticated and foreign denial, ineligible journey denial, invalid ID/input/method/default-deny, no-store and no browser transport leakage.
- Client tests prove malformed response and wrong-ID rejection, exact method/path, error status, byte cap, timeout, cancellation and no late success.
- OpenAPI generated TypeScript contract reflects the endpoint. Focused Java and TypeScript checks pass. Human OAuth, staging and physical-device checks remain in the native ledgers.
