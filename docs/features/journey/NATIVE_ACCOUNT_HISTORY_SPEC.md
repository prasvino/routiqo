# Native account journey history

Status: implemented behind the opt-in `native-auth` profile, 2026-09-24. This is an authenticated, owner-only read for the Android Trips screen. The existing `GET /api/v1/native/journeys` latest-50 recovery read, SQLite snapshots and durable outbox retain their current roles.

## Contract and authority

`POST /api/v1/native/journeys/history` accepts exactly `{}` for the latest page or `{"before":{"startedAt":"YYYY-MM-DDTHH:mm:ss.ffffffZ","id":"canonical-lowercase-uuid"}}` for the next page. Duplicate/unknown fields, partial cursors, noncanonical values and query strings are rejected. The server fixes the page size at 20; there is no client limit. It authenticates the native bearer, requires one matching `X-Routiqo-Account`, and selects only rows owned by that verified account, ordered by `(started_at,id)` descending. It returns `{journeys,next}`, where `next` is null at the end or the last returned key when more rows exist. Existing native peer/account rate limits, 20 KiB request guard, no-store response and minimal errors apply. No owner, location, route or presence field is returned.

The client uses the fixed HTTPS native transport with no redirects/cookies, a 12-second deadline and bounded response bytes. It validates all lifecycle records, unique IDs, descending keys, older-than-requested keys, and a `next` cursor matching the last item of a full 20-item page before displaying anything. Any malformed page fails as a whole.

## Trips behavior

Account history loads only after an explicit tap. Latest resets the page and Earlier follows `next`; no page is automatically drained. Keep only the displayed page and its request cursor in component memory. A failed read retains the page and offers a retry of the requested cursor; authentication failure clears it and asks for sign-in. An offline page remains visible with an explicit retained/offline label, and page controls cannot fetch offline. Sign-out, account switch, account deletion and unmount clear or fence private results, including late successes and errors. This page never merges into the durable journey snapshots or acknowledges outbox commands. Dates, kind and status are visible; IDs are not user copy. Native journal browsing is outside this slice.

## Verification and rollout

Test native allowlists, strict request body/cursor, unauthenticated and cross-owner isolation, keyset tie/terminal behavior, response validation, manual pagination, offline/failure retention and stale account results. Generate the TypeScript OpenAPI contract. No migration or new external service is required. The endpoint remains under the opt-in `native-auth` profile; device and real OAuth verification remain release gates.
