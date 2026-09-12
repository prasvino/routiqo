# Private completed-trip journal — first slice

Scope: derive a private journal view from an owned completed TRIP journey, with an optional traveller-written title and plain-text notes. Daily COMMUTE journeys remain separate and do not produce rich journals. No invented distance, route, photos, stops or AI summary: the current lifecycle only provides start/completion timestamps. Media, sharing and automatic enrichment are future phases.

The default journal is derived on read, so every eligible completed trip has a journal without background writes. Annotation version0 means no saved annotation. Title and notes default to empty strings, letting the UI use a neutral trip heading. Saving both empty strings clears the personal annotation; lifecycle metadata remains part of the journey. Account deletion cascades annotations; no other public read, listing or sharing endpoint is introduced.

Clearing annotation content retains the row's version, latest mutation identifier and timestamp. Do not reset it to version0: retained concurrency metadata prevents an old first-save retry from resurrecting cleared content. Only a never-written annotation uses version0.

## Contract and concurrency

JSON types are strict at the HTTP boundary: title, notes and mutationId must be strings. expectedVersion must be a JSON number with an exact mathematical integer value in0..9007199254740990. Integral decimal/exponent notation is allowed within a64-character numeric token and absolute exponent100 limit; numeric strings, booleans, fractional values and out-of-range numbers return400 without a write. Duplicate known properties are rejected. Do not truncate fractions or round through binary floating point before validation. Parsing remains within the existing request-body limits.

GET `/api/v1/journeys/{id}/journal` returns `{journey, annotation}`. Journey uses the existing owner-filtered lifecycle schema. Annotation is `{title, notes, version, updatedAt}`; version0/updatedAtnull represents the derived empty annotation. POST to the same path accepts `{title, notes, expectedVersion, mutationId}`. Title is at most120 UTF-16 code units, notes at most4000; reject NUL, unwanted control characters and invalid surrogate pairs. Notes permit line breaks/tabs and must remain plain text. Do not trim or alter authored content silently.

Writes use compare-and-set on the expected annotation version. A first save expects0 and creates1. Concurrent edits with the same expected version have one winner; other callers receive409. Store only the latest mutation UUID: an exact replay of the latest mutation with the same content and previous version returns the existing annotation without changing time/version. Reusing an ID with different content or retrying older work after a later edit returns409, never overwrites. Versions fit safe JavaScript integers and fail closed on exhaustion.

Require the existing session, matching account header, exact origin and CSRF for POST, no-store for reads/writes, and20 writes/account/minute through the shared database rate gate. GET needs session/account matching. Missing/unowned journey returns404 without disclosing its owner; owned active trips or commutes return409. Invalid content/version/mutation input returns400. No annotation content or token is logged or included in diagnostic exceptions.

## Domain and storage boundaries

New journal domain owns annotation validation, application store/service and JDBC persistence. The service uses `JourneyService.get` as an intentional cross-domain application interface to verify ownership and completed-trip eligibility. It must not query journey/identity repositories directly. Completed status and owner are immutable; journal foreign-key cascade prevents orphaned writes if account/journey deletion wins the race. Journal SQL only accesses its own table; reference the journey ID and account for cascading lifetime constraints. Use a versioned migration, transaction timeout and database-arbitrated conflicts across replicas, never process-local locks.

Annotations are personal durable content until cleared or account deletion. No provider content or precise coordinates are auto-copied into them. Existing planning backups exclude journals and authenticated caches. Browser editor/offline drafts require a separate tested account-bound durable save/restore design before exposing editing; do not clear unsent user text on network failure. This first backend slice is not evidence of a complete journal UI or offline editing.

## Verification

Domain length/control/surrogate tests; real PostgreSQL first save/exact replay/stale edit/mutation reuse/concurrent writer tests; owner isolation, eligibility and FK deletion race tests; HTTP session/account/origin/CSRF/rate/body guards; OpenAPI drift and generated TypeScript checks. Use synthetic accounts and disposable databases only. Preserve existing journey lifecycle behavior.
