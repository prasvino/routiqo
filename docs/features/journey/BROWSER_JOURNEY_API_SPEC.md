# Authenticated browser journey lifecycle

Expose the existing lifecycle under the opt-in web-auth profile. Default preview and persistence-only profiles continue denying access. Every action derives the owner from the validated HttpOnly session; body/query owner IDs never select an actor. Existing browser Origin, CSRF, peer rate limits, no-store and 20 KiB body limits apply. No bearer/native transport is introduced.

POST /api/v1/journeys accepts stable UUID id and kind trip/commute. Return 200 for both first creation and retry with the original lifecycle timestamps. Same ID with changed kind or another active journey returns generic 409. GET /api/v1/journeys/{id} and POST /api/v1/journeys/{id}/complete return only the owner's record; absent and foreign records return identical 404. Repeated completion returns the original result. UUIDs must use canonical full-length syntax.

GET /api/v1/journeys uses limit 1–50 (default 20), optional paired beforeStartedAt/beforeId cursor, and returns journeys plus nullable next cursor. Cursor timestamps must have microsecond precision and a bounded representable date. Owner remains derived from the session even for arbitrary supplied cursors. Responses exclude owner IDs, route names, coordinates and presence.

An account deleted between authentication and start must not create orphan data; the FK enforces this and the adapter returns authentication failure. In-flight actions authorized before logout may complete; subsequent requests require a valid session. Never hold a session/account lock across an external provider call.

Tests use HTTP plus disposable PostgreSQL and synthetic Google identities: unauthenticated and revoked sessions, CSRF/origin, forged actor input, cross-owner get/complete, duplicate start/completion, conflict, bounded pagination and malformed IDs/cursors. Existing concurrency tests cover database arbitration. Generate contracts before client transport. Local plans remain drafts; no automatic upload or user-facing sync yet.
`nAccount-context guard: every journey request requires X-Routiqo-Account equal to the authenticated account. Missing, duplicate or mismatched headers return401. It never selects an owner. The web proxy preserves this header; account-switch HTTP tests cover it.
