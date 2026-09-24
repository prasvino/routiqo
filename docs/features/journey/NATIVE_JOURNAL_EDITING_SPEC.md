# Native durable journal editing

Scope: finish private title/notes editing for owned completed trips on Android,
building on native journal browsing and the existing journal domain/CAS service.
This is a user-facing feature, including device drafts, account delivery, restore
and conflict recovery. No media, sharing, AI enrichment, new provider or LIVE gate.
Architecture/retention decision: ADR 0058. Relevant threats include T01/T02 actor
and session confusion, T12 private-data leakage, T13 replay/races, T19
deletion/retention and T20 multi-replica consistency. Native identity is never
inferred from a cache.

## Acceptance

- A loaded completed-trip journal offers Edit journal. The native editor uses the
  established tokens, text scaling, keyboard-safe scrolling and accessible controls.
  Keep the four tabs. A modal may contain this focused editing task so background
  tab navigation cannot silently discard text; Android Back must use the same guard
  as the visible close action. Confirm abandoning unsaved text. Saved device drafts
  survive closing, process restart and logout; another account cannot read them.
- Title (120 UTF-16 units) and plain notes (4000) follow shared validation and retain
  authored whitespace. Show Save draft on device separately from Save to account.
  Device saving is available offline for an already-confirmed completed trip.
  Account save persists the exact draft before any network call; storage failure
  prevents dispatch. No automatic journal send/retry on reconnect or sign-in.
- A stable mutation UUID identifies an exact saved draft. Repeating its send uses
  the same UUID/version/text. Changed text gets a new UUID. Never implicitly rebase
  an unsent draft onto a newer server version.
- A successful account response is not shown as fully settled until SQLite accepts
  the exact current draft acknowledgement. Timeout, network loss, cancellation,
  malformed acknowledgement and stale local work retain the durable draft. A late
  result cannot clear newer work, change another selection, or cross account epochs.
- Conflict 409 keeps local work. Explicitly fetch/review the latest account version;
  provide an explicitly confirmed Use account version action that discards only the
  reviewed draft and matching cached version. Do not silently merge or overwrite.
  Distinguish unavailable/storage/full/offline/session/missing/conflict states.
- Offer an explicit list of journals saved on this device within the verified
  account's Trips area so drafts remain discoverable when server history pages
  change. Display draft status. Cold-start private access still requires the existing
  verified native session; never treat a stored account ID as authentication.
- Account/session changes clear private visible state and fence pending operations.
  Authentication denial cannot fall back to showing cached private content. Explicit
  device saves remain retained under their original account. Account deletion must
  atomically retire and remove journal content alongside the native journey cache.
  A successful verified rotation for the same account is distinct from sign-out:
  it may preserve editor-local text while cancelling earlier operations and
  invalidating remote freshness/conflict review. Do not erase typing on routine
  renewal. Keep that editor state outside epoch-keyed browsing history; changing
  or losing the verified account still clears the whole private workspace.

## HTTP and transport

Add POST `/api/v1/native/journeys/{id}/journal` under the existing opt-in native-auth
profile. Require bearer authority and exact account header, reject browser ambient
headers/query strings, retain no-store/body/response/deadline limits. Use the existing
JournalService and database CAS/replay behavior; no new annotation table/migration.
Rate-limit account writes using the durable journal-write-account bucket (20/min),
shared with browser writes so platforms cannot multiply the quota.

Input is exactly title, notes, expectedVersion, mutationId. Strict types, duplicate
properties, unknown properties, controls/surrogates and exact integer numeric
validation must fail closed. Follow the existing bounded decimal/exponent handling,
not binary-float rounding. Owner isolation/eligibility and domain error statuses
remain unchanged. Add only this POST to JS/Kotlin/server allowlists. A successful
response must match journey, title, notes and expectedVersion + 1. Never log text,
bearer values or raw request/response bodies. Update OpenAPI and generated types.

## Device storage

Use the existing SQLite database, separate from the lifecycle outbox and local
planning data. A versioned `journal_partitions_v1` table stores one validated bounded
partition per account: version 1, accountId, drafts, journals. Maximum 20 drafts,
20 confirmed snapshots and 1 MiB serialized UTF-8 per partition. Validate before
reads/writes; corrupt data is retained and reported, never silently reset.

Exclusive transactions enforce compare-and-set on previous draft mutation identity,
exact replay, monotonic cache versions, matching immutable journey lifecycle,
same-version same-content, atomic acknowledgement and guarded discard. A snapshot
with an unsent draft cannot be evicted. At capacity, only the oldest unprotected
snapshot may be evicted; reject if all are protected. Use the existing retired-account
marker in every journal transaction so deletion cannot be undone by late work.
Logout retains drafts. Deletion removes them in the same exclusive transaction as
journey retirement. No journal text enters planning backup/export or logs.

Storage interface in `apps/mobile/src/storage/journal-storage.ts`:
`NativeJournalDraft = TripJournalWrite & { journeyId: string }`; functions take
`db: OutboxDatabase` first, then account, with browser-equivalent signatures:
initializeNativeJournalStorage(db), readNativeStoredJournal(db, account, journeyId),
listNativeStoredJournals(db, account), cacheNativeTripJournal(db, account, input),
saveNativeJournalDraft(db, account, draft, expectedPreviousMutationId),
acknowledgeNativeJournalDraft(db, account, journeyId, expectedMutationId, response),
discardNativeJournalDraft(db, account, journeyId, expectedMutationId, reviewed).
Read returns `{draft, journal}`; list returns `{draft, journal}[]`; save returns the
canonical draft; acknowledge returns boolean; cache/discard return TripJournal.

Transport export in `native-journal.ts`:
`writeNativeJournal(identity, accountId, journeyId, input: TripJournalWrite, signal?)`.
Reuse the read path's strict identity/session/deadline/cancellation discipline.

## Verification and release limits

Meaningful real HTTP tests for native actor/owner/eligibility/strict input/quota,
CAS replay and guard denials; client tests for exact payload, late credentials,
timeout/cancellation and invalid acknowledgements. Execute the production SQLite
queries against file-backed SQLite for restart, CAS, stale acknowledgement, rollback,
quota/eviction, corruption, isolation and atomic retirement. Controller/view tests
cover offline durable save-before-send, retry identity, conflicts and guarded closing.

Root reviews the integrated changes, runs affected and regression checks, builds
Android and exercises rendered editor states/interactions using clearly labeled
synthetic fixtures if OAuth is unavailable. Remove fixtures before final smoke.
Record real OAuth/staging/TalkBack/physical-device/deletion trials in the pending
ledger. Commit this feature only after integrated verification; do not deploy or
activate production services as part of implementation.
