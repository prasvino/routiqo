> **Archived 2026-09-25.** Historical build-status snapshot; the current record is docs/quality/BUILD_STATUS.md. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Build verification history — archived 2026-09-19

Historical snapshot preserved before consolidating BUILD_STATUS.md. The entries
below mix phases and dates; old pending items, totals and provider/setup notes may
be superseded. Use [BUILD_STATUS.md](BUILD_STATUS.md) for current implementation,
latest validation and next priorities. Read this archive only for specific older
evidence. No historical entry authorizes activation, deployment or timers.

---
# Build status — 2026-09-19

Workspace: `D:\Pras\routiqo`. Working local-planning preview and tested backend foundations; not production-ready. This consolidated audit supersedes the previous continuation lists.

## Implemented

| Area | Current behavior |
|---|---|
| Web | Home, Explore, Trips, Profile; curated destination search/filter/details, bookmarks, editable trip and recurring commute drafts |
| Scheduling | Shared next-departure calculation, weekday recurrence, upcoming ordering, foreground refresh, DST-gap handling; no automatic journey completion or reminders |
| Commute summaries | Monthly counts and exact recorded elapsed minutes from the verified account's confirmed completed commutes saved on this device; time-zone-aware start-month grouping and explicit incomplete-history disclosure. Read-only, no invented distance or new persistence |
| Local storage | Validated web localStorage with write-error handling and cross-tab refresh; native SQLite adapter; native restart behavior still needs device testing |
| Native deletion storage | Atomic account retirement marker plus queue/snapshot removal prevents delayed updates recreating deleted data; restart, rollback and account isolation tested with file-backed SQLite. Not yet connected to native authentication/deletion UI |
| Native authentication API | Opt-in bearer-only challenge/exchange, session read/renew, lineage logout and recent-auth account deletion; bounded strict JSON, shared database peer/account/challenge limits and browser isolation. Mobile response validators implemented; native network adapter and Google UI remain pending |
| Native credential store | Expo SecureStore adapter and bounded session vault, serialized writes/clear, stale-ticket rejection, expiry checks and fail-closed recovery. Not yet mounted in native sign-in or network transport |
| Account history | Explicit 20-row account-history browsing, earlier/latest pages, completed-trip journal access, retry handling and account-switch isolation; no expansion of the offline cache |
| Web backup | JSON export with disclosure and copyable-text fallback; file validation, restore preview and idempotent merge retaining current edits; no upload |
| Mobile | Expo four-tab UI sharing catalog, planning, scheduling and tokens; Android JavaScript export, not a tested APK; native backup text export/share and pasted-JSON restore implemented, device QA pending |
| Core API | Public health/catalog; opt-in authenticated journey start/get/list/complete with owner checks; default preview denies protected routes; PostgreSQL/Flyway persistence |
| Journey write authority | PostgreSQL account-before-journey transaction boundary shared by start/completion and internal owned-journey callbacks; deletion serialization, rollback, five-second lock timeout and redacted retryable failures tested. Completion invokes consent then route-context participants atomically; signal acceptance composes both current authorities |
| Route planning | Authenticated temporary place search, opt-in guarded Valhalla estimates and Photon search with bounded directions, route alternatives and explicit MapLibre web map display using configured same-origin resources. Manual step review retains the last successful route through connection loss; no reload persistence, GPS-following navigation, downloaded offline maps or live provider verification yet |
| Trip journals (local preview) | Completed-trip private title/notes API, optimistic versions and retry identity; account-bound IndexedDB drafts, retained-journal library and editor connected to Trips. Explicit conflict recovery can discard only the exact reviewed device draft without a server write. No media, sharing or commute summaries; authenticated navigation/device QA remains pending |
| Presence consent | Privacy-owned PostgreSQL latest-row state with legacy journey-scoped CAS plus explicit revocation-precedence intents, opt-out reads and saturating atomic completion revocation. An owner-only browser GET/POST transport exists behind a separate default-off flag with durable limits and string generations; no UI, presence lease issuance, cache invalidation, discoverable presence or realtime publication enabled |
| Live route context | Route Update-owned PostgreSQL latest-row envelope with bounded private anchors, optional catalog provenance, fresh identity/exact-ID replacement, post-lock temporal checks, completion deletion and callable bounded expiry cleanup. A default-off internal two-transaction binder derives curated anchors from fresh guarded Valhalla geometry, uses a durable newest-attempt fence and rechecks consent/context authority before replacement; no public registration or output; optional bounded expiry job under ADR 0036 |
| Private route-context API | Separately default-off owner-only GET recovery and explicit POST binding through the real configured binder; strict browser guards, minimal no-store DTOs, database account quotas and post-provider authority rechecks. No UI or public Live output |
| Quick Signal storage | Internal PostgreSQL server-issued grants, retained private receipts, partial-unique actor/anchor/category contribution slots, atomic acceptance/withdrawal, fixed-minute plus rolling 20/hour actor budgets, 60-second anchor/category cooldown and bounded expiry cleanup. Mandatory durable suspension and grant-revision fencing protect new writes. A default-off catalog-aware facade derives issuance categories and rechecks current provenance/category for new acceptance; no operator workflow or publication; optional bounded expiry job under ADR 0036 |
| Private Quick Signal API | Separately default-off owner-only POST issue/accept/withdraw through the catalog-aware facade; strict browser guards, minimal no-store string-safe DTOs and separate database request quotas. Fixed private evidence/receipt lifetimes do not approve public retention; no UI or Live projection |
| Private safety foundations | Reviewed assessment, block and structured report-case domain transitions; durable contribution restrictions under the account transaction authority. Suspension denies new grants/acceptance and unsuspension cannot revive old grants. Durable private directed blocks with ordered account locks, bounded retained revisions and bilateral exclusion; no operator API, durable report workflow, public block targeting or public trust claim |
| Private browser LIVE clients | Typed consent, context and Quick Signal clients with strict schema/identity/lifetime validation, exact long values, bounded CSRF/stream deadlines and explicit cancellation. No mounted controls, persistence, automatic retry or public projection |
| Persistence | Owner-scoped reads/completion, one active journey per owner, retry-safe start/completion, bounded keyset history; guarded browser journey endpoints; web client dispatch mounted on Trips |
| Offline queue | Bounded commands, native SQLite and web IndexedDB partitions; atomic result/acknowledgement, stale leases, retry/block states, single-command orchestration, web transport and IndexedDB dispatch adapter; Trips workspace with foreground/reconnect dispatch, bounded recent restore and confirmed-result reconciliation |
| Google identity | RS256 token verification with configured audience, issuer/time/nonce checks; durable subject-to-account mapping and disabled-account protection |
| Login sessions | Five-minute one-use challenge, atomic account/session exchange, hashed 15-minute opaque credentials, bounded rotation up to 12 hours, lineage revocation and disabled-account enforcement |
| Browser auth API | Opt-in challenge/exchange/session/logout and CSRF bootstrap; HttpOnly cookies, exact origin/CSRF checks, PostgreSQL rate limits, body limits and bounded expiry cleanup. Default preview still denies auth; web Google button UI and allowlisted same-origin proxy implemented; real OAuth configuration pending |
| Realtime/workers/admin | Buildable foundations only; no operational messaging, jobs or administration workflows |
| Engineering | Strict TypeScript, generated OpenAPI types/drift checks, formatting/lint/tests, Java architecture tests, secret scanner, local Compose services, CI definition |

## Verification

Journal transport correction (2026-09-19): the existing editor's read/save client
now enforces one 18-second operation deadline and a streamed 32 KiB byte limit,
rejects redirected or unexpected successful responses, and never waits indefinitely
for read or cancellation cleanup. Captured edits and exact acknowledgement checks
preserve durable drafts and uncertain-save recovery. Independent review passed;
**42 journal transport/storage/editor tests in 3 files** passed. No journal storage,
backend or UI behavior changed. Final combined validation is recorded separately.

Private browser client pass (2026-09-19): consent read/intent, context read/bind,
and signal issue/accept/withdraw clients now use generated types with strict
runtime DTO checks. They preserve exact long revisions and nanosecond instants,
capture request bodies before CSRF, enforce same-origin redirect refusal and
bounded whole-operation deadlines, and handle late or uncooperative transports.
Historical private receipts remain acknowledgements; no automatic retry, storage,
UI mounting, flag activation or public publication was added.

Browser authentication now bounds strict UTF-8 JSON to 16 KiB, refuses redirects,
requires exact success statuses and shares one deadline across CSRF plus internal
mutations. Failed/unneeded response bodies are cancelled without reading details.
Independent adversarial review passed for both client boundaries after cleanup,
newline-validation and cancellation-race corrections.

Verification: **291 TypeScript tests in 42 files**, including 18 focused LIVE
tests, passed with two workers. An initial parallel run hit two existing mobile
dependency timeouts; both passed in a focused rerun and the complete two-worker
run. All workspace typechecks, contract drift, lint, formatting, secret scan and
the web production build passed. No backend changes or backend suite rerun.

Trips received three small accessibility fixes: 44 px weekday targets with narrow
wrapping, spoken “to” between route endpoints, and stacked date/time fields below
380 px. Rendered desktop/390 px/320 px checks and focus restoration are recorded
in TRIPS_UI_AUDIT_2026-09-19.md. Real authenticated/provider/device verification
and broader accessibility testing remain release gates.

Native identity validation hardening (2026-09-19): mobile account/session/challenge
response validators and secure-vault reads/commits now reject the all-zero UUID.
Canonical non-nil IDs remain version-agnostic. Corrupt stored identities fail closed
before any credential reaches the restoration verifier; explicit clear still recovers
storage. Focused protocol/vault/restoration validation passed **31 tests in 3 files**,
mobile typecheck, targeted lint/format checks, root diff review and secret scan.
This was a narrow client validation change: no UI, native network adapter, backend
endpoint or device behavior changed. Prior full backend/TypeScript totals below are
historical full runs; they were not rerun for this targeted fix.

Private durable blocking pass (ADR 0040): moderation-owned directed edges now use
an identity-owned transaction locking both enabled accounts in PostgreSQL UUID
order. Strict revision CAS, stale-block precedence, exact unblock, saturation,
100 outgoing rows including unblocked revisions, and both deletion cascades are
enforced. Initial unblock uses the same capacity gate as block. Unknown or wrong
pair state denies. APIs cannot reach trusted mutations or concrete pair authority.
A bilateral CLEAR is only a transaction snapshot, never future output permission.

Final core-api check and bootJar passed **363 Java tests across 50 suites**, zero
failures/errors/skips. Real PostgreSQL tests prove two opposite-direction writers
wait on the lower UUID while the higher remains NOWAIT-lockable, and competing
last-slot requests yield one success/one capacity denial. Deletion races, replay
revisions, saturation, missing/disabled accounts, rollback/redaction and invalid
participant snapshots pass. Root integration, independent adversarial review,
secret scan and diff checks passed. No frontend/contracts changed or builds repeated.

Reporting remains a reviewed proposal, not a durable service: canonical evidence
identity, reference revocation/lock ordering, exact-retry disclosure and a useful
investigation lifecycle must be resolved before intake persistence. See
DURABLE_REPORT_INTAKE_PROPOSAL.md. Public publication remains closed under ADR 0038;
no public targeting, directory, projection or operator endpoint was introduced.
Only disposable databases were migrated. No activation, push or deployment.

Autonomous Live safety pass (ADRs 0037–0039), restore point **0cd6457**:
rolling 20/hour acceptance ledger, 60-second anchor/category cooldown, bounded
cleanup, private safety domain transitions, and durable contribution suspension.
Grants capture restriction revisions; suspend/unsuspend cannot revive old unused
grants. Missing/wrong-account authority denies. Retained private replay/withdrawal
remain available. Concurrency, cleanup, clock, deletion and rollback are tested.

Final core-api check and bootJar passed **351 Java tests across 49 suites**, zero
failures/errors/skips. Root integration, independent adversarial review, secret
scan and diff checks passed. No frontend/contracts changed; prior TS validation
remains the latest. Migrations ran only on disposable databases. V13 upgrades
require every acceptance writer stopped for a full hour after the last old write;
no mixed-version acceptance (see LOCAL_SETUP).

Public publication remains unapproved under ADR 0038: thresholds alone fail
collusion and withdrawal inference. Evidence-independence authority and a reviewed
block/revocation-safe protocol remain design gates. No public projection, operator
API or operational reporting is claimed. Internal durable blocking followed under
ADR 0040, verified separately above. Existing flags remain default off. No push or deployment occurred.

Live expiry maintenance pass (ADR 0036): added a separately default-off,
persistence-only job calling existing cleanup interfaces once per category with
100-row limits. It uses a dedicated scheduler with 60-second initial/fixed delay,
isolates category failures with constant-only diagnostics and prevents overlapping
local ticks. Authentication maintenance retains a separate scheduler. No SQL,
migration, retention-duration, endpoint or public Live behavior changed.

Full core-api check and bootJar passed **328 Java tests across 48 suites**, zero
failures/errors/skips. Five new tests cover profile/flag gates, real adapter
composition, runtime scheduler isolation, full-batch bounds, failure recovery,
redaction and duplicate concurrent entry. Existing context persistence **19/19**
and signal persistence **17/17** tests passed, including lock and replay/purge
regressions. Root diff review, independent review, secret scan and diff checks
passed. Local preview `/trips` returned HTTP 200 after restart. No frontend source
or contracts changed, so TypeScript/UI builds were not repeated.

The job remains disabled. Production activation, cleanup throughput/backlog alerts,
backup lifecycle, public moderation/revocation and cohort-safe publication remain
gates. The scheduler is application infrastructure, not a Codex automation timer.

Private browser Quick Signal transport pass (ADR 0035): the separately default-off
POST issue, acceptance and withdrawal leaves require real persistence, catalog,
route binding and catalog-aware signal composition. Exact session/account,
same-origin CSRF, strict bounded JSON and durable peer/account request budgets run
before the existing transactional storage authority. Responses contain only the
server grant or minimal private receipt with decimal-string revisions/generations.
Exact retained replay and terminal withdrawal preserve all timestamps; no provider
request, public read/list endpoint, UI or Live projection was added.

The focused Java set passed **31 tests**: 17 enabled real HTTP/PostgreSQL/Valhalla
tests, 11 default-off journey regressions and 3 parser tests. It covers exact
15-minute evidence and 24-hour retention, all 17 closed enum values, Long.MAX and
greater-than-JavaScript-safe string precision, malformed UTF-8/duplicate/trailing
JSON, security/account isolation, request versus storage quotas, retained replay
after evidence expiry/Ghost/completion/grant cleanup, expiry denial, supersession,
withdrawal and duplicate HTTP acceptance with two observed account-lock waiters
but one receipt/storage charge. The exact proxy suite passed **9 tests**. Full
core check and bootJar passed **323 Java tests across 46 suites**, zero failures,
errors or skips. Contract generation/drift, formatting, workspace types and lint
passed; all **260 TypeScript/component tests across 41 files** passed. Secret and
diff checks passed. Root and independent review found no remaining source issue.
Fixtures used disposable PostgreSQL, synthetic identity and loopback Valhalla;
no user data, live provider or public endpoint was used.


Browser route-context transport pass (ADR 0034): the separately default-off
GET/POST leaf requires the real persistence, catalog, resolver and Valhalla binding
composition. GET is nonmutating and read-only at the application boundary. POST
strictly accepts endpoints plus alternative and exact current-context expectation,
then invokes the existing binder once. Responses contain only context identity,
decimal-string revision, sorted opaque anchor IDs and server times. The exact
same-origin proxy uses an eight-second GET deadline and a 25-second POST deadline,
64 KiB response cap and no retries. No signal endpoint, UI, activation or public
Live output was added.

The focused backend set passed **22 tests**: 8 new real HTTP/PostgreSQL/Valhalla
tests, 10 journey/default-off regressions and 4 architecture tests. It covers
owner binding/read/lost-response recovery, minimized and exact long-valued DTOs,
no-route/no-anchor preservation, strict UTF-8/JSON/path/media/body/browser guards,
session/rate/persistence/provider sanitization, cross-owner denial, readable Ghost
state, expiry/completion, stale expectations, separate cross-session budgets and
consent revocation while the provider is blocked. The exact proxy suite passed
**8 tests**, including 25-second POST versus eight-second GET deadlines. Full core
check and bootJar passed **310 Java tests across 45 suites**, zero failures, errors
or skips. Contract generation/drift, formatting, workspace types and lint passed;
all **259 TypeScript/component tests across 41 files** passed on immediate rerun
after the unrelated Xcode dependency import first exceeded its five-second test
timeout. Secret and diff checks passed. Root and independent review found no
remaining source issue. Automated provider traffic used loopback fixtures and the
database was disposable; no user data, live provider or public endpoint was used.


Catalog-aware signal authority pass (ADR 0033): V12 adds nullable non-nil catalog
provenance to private route contexts. Provider binding stores the actual catalog
version; ordinary replacement clears it and historical rows remain unvalidated.
The configured default-off facade derives complete grant categories from the
server catalog and rechecks context provenance, anchor membership and current
category eligibility inside the existing new-acceptance transaction before grant,
budget, slot or receipt mutation. Exact retained replay remains first after
catalog removal/version changes, consent withdrawal or completion. An architecture
rule forbids API packages from depending on the trusted low-level storage service.

The focused set passed **66 tests** across stored-context domain and PostgreSQL,
binding, signal storage, production configuration and architecture suites. It
covers nullable/non-nil migration constraints, legacy/raw provenance clearing,
actual configured binding-to-issue-to-accept, exact catalog-derived categories,
missing/changed/removed provenance, disallowed current categories, consent/context
changes with no partial mutation, retained exact replay and changed retry conflict,
plus all affected low-level and route-binding regressions. Full core check and
bootJar passed **300 Java tests across 44 suites**, zero failures, errors or skips.
No public HTTP/UI, TTL change, feature activation,
provider data, scheduler or Live output was added.

Internal route-binding pass (ADR 0032): an account-scoped durable attempt fences
fresh provider work between two short authority transactions. The first transaction
validates active ownership, sharing consent and exact context, charges the shared
ten-per-minute database budget and persists a 90-second newest-attempt snapshot.
The second rechecks journey, consent generation, context, catalog, attempt identity
and post-lock time, consumes before a fixed 15-minute context replacement, and
rolls consumption back with a failed context write. No-route and no-match outcomes
consume without replacing context. Direct context writes and completion invalidate
pending work atomically; leaf expiry cleanup cannot resurrect an older response.

The focused set passed **56 tests** across route-binding domain/persistence,
configured production composition, and affected context, signal-storage and
resolver-configuration suites. It covers provider calls outside transactions,
success and private empty outcomes, exact context expectations, quota sharing and
failure charging, redacted rate/provider/persistence failures, newest-attempt
ordering in both response orders, a newer failed attempt, consent/completion/direct-
context invalidation, replacement plus physical purge, deletion, future/deadline
denial after observed row-lock waiting, database precision/constraints and
consumption rollback. A bounded loopback Valhalla fixture exercises the real
region-guarded configured binder with PostgreSQL authority. Full verification
passed **291 Java tests across 44 suites**, zero failures, errors or skips, and
bootJar succeeded. Secret and diff checks passed. The resolver remains default
off; no public HTTP, signal issuance, geometry persistence or Live output was added.

Provider-backed anchor resolution pass (ADR 0031): a strict bounded local catalog,
immutable redacted models and a default-off internal resolver reuse the configured
region and guarded Valhalla provider. Fresh selected-route geometry stays in
memory. Matching is vertex-only within 100 metres, excludes requested and geometry
endpoints through 1,000 metres, handles longitude wrap and denies more than 128
matches. No journey binding, provider-quality claim, HTTP, storage migration,
signal issuance or public output was added.

The focused set passed **23 tests**: 19 new catalog/domain/resolver/configuration
tests plus 4 existing routing configuration regressions. It covers immutable and
redacted models, exact/oversized/malformed UTF-8 input, strict schema/category/UUID
rules, one to 512 anchors, disabled/missing/invalid startup, region constraints,
alternative selection, no-route versus no-match, exact 100-metre matching and
1,000-metre exclusions, sparse vertices, dateline/polar math, 128-match overflow,
ambient transactions and provider failures. A bounded local HTTP fixture exercises
the real configured guarded Valhalla composition and out-of-region denial without
external traffic. Full core check and bootJar passed **273 Java tests across 41
suites**, zero failures, errors or skips. Root and independent review approved the
source and tests; secret and diff checks passed. The existing port-3000 preview and
its pre-existing generated `next-env.d.ts` diff were untouched. No frontend build
or provider-quality test was run or claimed.

Default-off browser consent pass (ADR 0030): owner-only GET and explicit-intent
POST use the existing browser session, account partition, origin/CSRF and bounded
body guards. Consent generations remain exact decimal strings through HTTP,
OpenAPI, generated TypeScript and the same-origin proxy. Durable database budgets
permit 60 reads, 10 enables and 20 disables per account/minute in separate
categories. Peer, account-rate, session-store and consent-authority failures deny
with bodyless no-store 503 responses. The sample flag remains false and no UI,
automatic enable retry, signal ingestion, lease, cache invalidation or public Live
output was added.

The focused real HTTP/PostgreSQL set passed **17 tests**: 8 enabled consent tests
and 9 existing journey/default-off regressions. It covers owner roundtrip and
ordering, completed/replaced/wrong-owner journeys, exact maximum generation,
strict JSON/path/media/body handling, session/account/origin/CSRF isolation,
distributed budgets and redacted failure paths. The focused proxy suite passed
**7 tests**, including exact-path/method denial, exact maximum-generation response
and string request forwarding. Full core check and bootJar passed **254 Java tests
across 37 suites**, zero failures, errors or skips. Full workspace checks passed
**258 TypeScript/component tests across 41 files**, generated-contract drift,
formatting, strict types and lint; the web production build, secret scan and diff
check passed. Root and independent review approved the corrected source and tests.
Disposable PostgreSQL and synthetic test identity only; no user data was migrated.

Explicit consent-intent pass (ADR 0029): the internal `submitIntent` path runs under
the existing account, owned-journey and consent locks. Active-journey enable needs
the exact generation and every accepted enable advances it. Stale or current
disable takes precedence and advances even when already off. Future generations
deny. Completed disable returns inactive off without mutation; completed enable
denies. Explicit disable and completion saturate safely at maximum generation.
The trusted legacy `change` contract remains unchanged.

Focused domain and disposable PostgreSQL tests cover both delivery orders with
observed database lock waiting, concurrent replicas, repeated intents, future and
stale generations, replacement/completion isolation, wrong/disabled/deleted
accounts, maximum generation and transaction-required diagnostics. Signal-storage
composition proves explicit revocation racing acceptance creates no receipt,
spends no acceptance budget and leaves the grant unused; a maximum-generation
revocation also invalidates an already issued grant. No HTTP, consent UI, command
receipt, offline enable queue, cache invalidation, lease issuer or public output
was added.

Full core check and bootJar passed **245 Java tests across 36 suites**, zero
failures, errors or skips. All **45 focused tests** passed: 6 consent-domain, 22
consent-persistence and 17 signal-storage tests. Root and independent security
review approved the source, tests and saturating terminal behavior. Secret and
diff checks passed. No frontend contracts or UI changed; prior 257 TypeScript
tests and web production build remain the latest frontend evidence. Disposable
Testcontainers databases only; no user data was migrated.

Transactional signal-storage pass (ADR 0028): migration V10 persists complete
UNUSED/CONSUMED grants independently from retained receipts, enforces closed
categories and partial-unique ACTIVE contribution slots, and keeps one fixed-minute
budget per actor/action. Issuance and new acceptance run under account, journey,
consent and context authority; acceptance atomically budgets, supersedes, consumes
and inserts. Exact retained replay ignores new lifetime settings and consumes no
budget. Withdrawal is terminal without renewal.

Focused disposable PostgreSQL tests cover owned/current issuance, category and
anchor binding, microsecond duration rules, partial-work rollback, concurrent
duplicate acceptance, changed payload conflict, cross-journey slot replacement,
withdrawal, Ghost/context/completion invalidation, expiry after a grant-lock wait,
minute budgets across adapters/journeys, independent grant/receipt cleanup,
receipt purge before live consumed-grant expiry, retained retry after grant cleanup,
missing/expired denial, SKIP LOCKED bounds, account deletion and redaction.
No HTTP, public projection, provider registration, moderation or scheduler exists.

Full core check and bootJar passed **234 Java tests across 36 suites**, zero
failures, errors or skips. All 16 focused transactional storage tests passed after
the authority-race, cleanup-identity and duration-ordering corrections. Root and
independent security review approved the final source, V10 schema, tests and docs.
Secret and diff checks passed. No frontend contracts or UI changed; prior 257 TS
tests and web production build remain the latest frontend evidence. Disposable
Testcontainers databases only; no user data was migrated.

Durable route-context pass (ADR 0027): migration V9 stores one minimal latest
context per account with owned-journey, UUID, revision, distinct-anchor and
24-hour lifetime constraints. Expiry floors to database microsecond precision,
with submicrosecond effective lifetimes rejected. Reads are nonmutating and half-open. Replacements
sample time after the context row lock, mint fresh UUIDs and require the exact
current identity while advancing same-journey revisions. Completion runs consent
before context deletion and rolls participant failures back atomically.

The internal expiry operation deletes 1–100 exact expired rows through an indexed
`FOR UPDATE SKIP LOCKED` query and a five-second transaction. It is a context-row-
only lock-order exception, rejects ambient transactions and has no scheduler.
Focused disposable PostgreSQL tests cover ownership/cascade, temporal boundaries,
stale and concurrent CAS, revision overflow, post-wait clock advancement,
completion ordering/rollback, locked-row skipping, account-lock independence,
concurrent replacement preservation and redacted database timeout/failure.
No provider anchor validation, admission issuer, receipt/grant/slot store, public
endpoint, cache invalidation or user database migration was added.

Full core check and bootJar passed **218 Java tests across 35 suites**, zero
failures, errors or skips. The 20 focused route-context tests passed after the
microsecond precision correction. Root and independent security review approved
the final source, schema, ordering, cleanup and documentation. Secret and diff
checks passed. No frontend contract or UI changed; prior 257 TS tests and web
production build remain the latest frontend evidence. Testcontainers databases
only; no user data was migrated.

Durable consent pass (ADR 0026): migration V8 stores one minimal latest consent
row per account with owned-journey and state constraints. Reads run under current
owned-journey authority and return opt-out without mutation. Explicit changes use
journey-scoped expected generations; actual Ghost transitions fence stale enables.
Configured journey completion revokes matching consent in the same account and
journey transaction, while participant failures roll back both. ADR 0029 later
changed maximum-generation completion to saturating terminal revocation.

Disposable PostgreSQL tests cover schema ownership/cascade, default reads, one-row
replacement, same-state updates, stale/reordered generations, independent-adapter
CAS races, consent/completion serialization with observed database lock waiting,
configured participant wiring, terminal retries and rollback. The legacy same-state
off write preserves generation; the later ADR 0029 explicit-intent path resolves
this ordering for future adapters. This historical phase added no public endpoint,
lease, cache/realtime invalidation or signal storage.

Full core check and bootJar passed **198 Java tests across 33 suites**, zero
failures, errors or skips. The 14 focused durable-consent tests exercise generation
overflow rollback, concurrent CAS writers, completion rollback, completion versus
stale enable with observed PostgreSQL lock waiting, deletion cascade and the
documented same-state-off ordering limit. Root and independent security review
approved the final source and documentation. Secret scan and diff checks passed.
No frontend contracts or UI changed; prior 257 TS tests and web production build
remain the latest frontend evidence. Testcontainers databases only; no user data
was migrated.

Account/journey authority pass (ADR 0025): identity owns the enabled-account
transaction boundary and Journey owns locked current-journey callbacks. Existing
start/completion now participate, sharing account-first order with token-based
deletion. Ambient transaction entry is rejected. SQL/transaction exceptions are
redacted before rollback logging and at the outer boundary; browser journey writes
return empty no-store 503 responses for unavailability, preserving queue retries.

Full core check and bootJar passed **184 Java tests across 32 suites**, zero
failures, errors or skips. Targeted database/HTTP validation passed 34 tests,
including 10 authority tests with actual PostgreSQL lock waits, timeout, rollback,
deletion and separate-actor progress. Independent review approved after the
pre-rollback logging correction and dual-interface bean wiring test. Secret scan,
diff checks, generated-contract drift, workspace typecheck and 11 browser journey/
dispatch tests passed. No visual changes; prior full 257 TS tests/web build remain
the latest full frontend run. Testcontainers databases only; no user migrations.

Route-context and grant/receipt/slot persistence were subsequently completed in
ADRs 0027 and 0028. Public Ghost Mode commands, delivered-cache revocation, issuer
endpoints and public Live output remain unavailable.

Live L0.3b command-policy pass: internal SignalCommandGrant models irreversible
consumption within the admission lifetime; SignalCommandPolicy distinguishes new
candidates, retained private replays, changed-payload conflicts and denials. Replay
requires current authenticated journey ownership. Supplied expired receipts deny
even with an UNUSED grant; missing receipts never reset consumed grants. No
timestamps are renewed. Command/fingerprint diagnostics redact private metadata.

Full core check and bootJar passed **173 Java tests across 31 suites**, zero
failures, errors or skips, including 15 focused command tests. Root and independent
review approved the expired-row correction; secret scan and diff checks passed.
Specs, ADR 0024 and threat model are aligned. No frontend change or new public
endpoint; previous 257 TS tests and web production build remain latest evidence.
Next: domain-owned authority transaction interfaces, common lock order, migrations
and disposable database race/cleanup tests. Durable idempotency and public Live
publication are still pending; these pure decisions do not provide either.

Live L0.3a receipt pass: internal QuickSignalReceipt binds evidence to route context
and revision, with explicit retention bounded by 24 hours and the evidence expiry.
Withdrawal and supersession are terminal and preserve the original timestamps;
exact replay comparison remains separate from evidence eligibility. Retained
metadata is not physically erased by withdrawal. The new cohort design checkpoint
records unresolved block/withdrawal/differencing cases and keeps public projection
closed instead of claiming that a threshold-only publisher is safe.

Full core check/bootJar passed **158 Java tests across 29 suites**, zero failures,
errors or skips. Eight receipt tests cover retention/expiry/extreme time bounds,
terminal state, replay context and redaction. Root/independent review and secret
scan passed. No database, API, UI, live providers or private user data were used.
No TS change; previous 257 TS tests and production web build remain latest.
Next independent work is admission-bound command identity and transactional
storage/retention design; publication still requires the cohort privacy ADR.

Live L0.2a admission pass: added immutable bounded LiveRouteContext and
SignalAdmission models and an application-layer consistency policy using an
independently authenticated actor plus current journey/consent/context snapshots.
It rejects wrong ownership, changed route/consent revisions, missing anchors,
disallowed categories, future/expired admissions and ended journeys. Ghost Mode
then re-enable cannot revive an old admission. Sets are copied and diagnostics
redacted. ADR 0023 documents authority, registration and storage gates.

Full core check/bootJar passed **150 Java tests across 28 suites**, zero failures,
errors or skips; nine admission tests cover ownership, invalidation, time
boundaries, immutable sets and redaction. Root and independent source review
found no remaining issues; secret/diff checks passed. This is an internal policy,
not an operational admission issuer, persisted route binding, atomic write check
or public projection. No TS/UI changes; prior 257 TS tests/web build remain latest.
Next: cohort publication and block-safe suppression, then receipt/storage/withdrawal.

Live L0.1 domain pass: internal QuickSignal and QuickSignalValue implement 17
closed values across five categories, immutable non-nil identity/context fields,
nonnegative consent generation, bounded receipt/expiry windows and half-open
freshness. Contribution-slot matching uses actor/anchor/category across journey
changes; exact submission matching never renews timestamps. Generic errors and
redacted string output omit private evidence metadata. These are pure primitives,
not storage idempotency, membership authorization, aggregation or a LIVE UI.

Full core check/bootJar passed **141 Java tests across 27 suites**, zero failures,
errors or skips; seven signal tests cover enum values, null/invalid context,
temporal extremes/boundaries, slots, replay matching and diagnostics. Independent
review and secret scan passed. No TypeScript/UI/provider changes; prior 257 TS
tests and web production build remain the latest relevant evidence. Next is the
cohort/admission design, including the missing server journey-to-route binding,
then persistence/withdrawal and protected ingestion. No Live endpoint is enabled.

Regional dataset integrity tooling pass: `pnpm maps:check -- <directory>` verifies
a bounded versioned inventory of three local Valhalla/Photon/tile artifacts,
coverage metadata, source/attribution fields, exact sizes and streamed SHA-256
hashes. It rejects traversal, escaping junctions, Windows device paths, non-files,
invalid metadata and corruption with redacted errors. Local artifacts under
`map-data/` are ignored by Git. No downloads, extraction, services, personal data
or runtime configuration writes occur. This verifies bytes against the operator's
inventory, not authenticity, provider formats, route quality or live availability.

Full check passed **257 tests across 41 files**, contracts, formatting, types and
lint. The script also passed explicit ESLint. Independent review and seven focused
tests passed, including a real Windows junction escape and malformed/oversized
manifest checks. The five-minute streaming timeout and POSIX FIFO behavior were
source-reviewed, not exercised in this Windows run. Backend/web runtime source
is unchanged; prior 134 Java tests and web build remain the latest build evidence.

Open-source runtime wiring pass: the opt-in routing profile now constructs Photon
and region-guarded Valhalla together from six mandatory operator settings. No
Mapbox token, public default or fallback is used. Missing/invalid configuration
fails startup without raw values or parsing causes; startup performs no provider
network requests. Planner and privacy disclosures, example settings, setup guide,
ADR and feature/threat specifications were updated together. Backend and browser
versions must be deployed together to preserve truthful disclosures.

Full TypeScript check passed **250 tests across 40 files**, including contracts,
formatting, types and lint; production web build and secret scan passed. Four
configuration tests cover profile gating, missing/invalid settings and redaction,
paired identities and coverage rejection before transport. Full core check and
bootJar passed **134 Java tests across 26 suites**, with no failures/errors/skips.
Independent configuration/security review found no actionable issues. Actual service/data
provisioning, authenticated visual QA, provider quality and Android verification
remain pending. No local live settings, dataset downloads or deployment occurred.

Coverage HTTP/browser pass: **249 TypeScript/component tests and 130 Java tests
across 25 Java suites passed**. Contract generation/drift, formatting, strict types,
lint, production web build, core check/bootJar and secret scanning passed.
The first full TS run had one unchanged Xcode dependency test exceed its 5-second
timeout while the production build was running; a full isolated rerun passed all
249 tests without source or timeout changes. Independent security review approved.
Coverage rejection is bodyless HTTP 422 after authentication, account, origin,
CSRF and quota checks; the proxy discards upstream error details. The planner
shows a fixed message and retains the last successful route. Component regressions
verify this behavior; actual browser visual QA remains pending because the browser
automation runtime fails during initialization. Runtime provider selection and
regional service activation remain pending; no real provider or device was used.

Offline journey controls pass: **227 TypeScript/component tests passed**, plus contracts, formatting, strict types, lint and secret scanning. The verified-account workspace announces offline local-save behavior and disables server history restore, manual dispatch retry and conflict checks until online. Restore/conflict handlers recheck connectivity. Local start/finish queuing and existing due/lease/authentication/visibility dispatch gates remain unchanged. Regression confirms local starts remain available, restore makes no offline request and reconnect enables the control without fetching history automatically. Existing conflict reconnect tests pass. No backend/native changes, new build or live authenticated visual QA in this pass.

Temporary planner reset pass: **226 TypeScript/component tests passed**, plus contracts, formatting, strict types, lint and secret scanning. Clear route planning aborts pending work and resets temporary places, attribution, estimates and mode offline. Regression verifies that a late location callback cannot recreate the cleared selection. The status explicitly discloses that provider browser caches may remain; saved journeys are unaffected. No backend/native or storage-policy changes, new production build or live browser/provider QA in this pass.

Explicit cancellation pass: **225 TypeScript/component tests passed**, with contracts, formatting, strict types, lint and secret scanning. Cancel request now releases pending search/calculation/location work immediately, preserves loaded estimates on recalculation cancellation, and ignores late results/errors. Eleven planner tests pass, including a late cancelled location callback after selecting a place and a late cancelled calculation failure. Location cancellation explains that the browser permission prompt may remain. No backend/native changes or new live-provider, production-build or browser visual QA in this pass.

Map load deadline pass: **224 TypeScript/component tests passed**, plus contracts, formatting, strict types, lint and secret scanning. Explicit map loading now has a 20-second SDK/style deadline, removes an unfinished map on timeout, ignores its late callbacks and permits explicit retry. Fake-clock regression covers timeout cleanup, late load rejection, successful retry and a ready map remaining available past the deadline. No backend/native changes or live provider/UI QA in this pass; the previous production web build remains the latest build evidence.

Directions selection pass: **223 TypeScript/component tests passed**, plus contracts, formatting, strict types, lint, production web build and secret scan. All directions now supports direct step selection with focus transfer, selected-step semantics/highlight and continued Previous/Next review. Re-selecting the active route preserves the review position. An actual browser synthetic 360px-content preview verified selection, focus and readable wrapping; selected styling was then added. The temporary preview was deleted before production build. This was not native-device, live provider or authenticated end-to-end QA. No backend, location collection or route persistence changes.

Endpoint swap pass: **222 TypeScript/component tests passed**, with contracts, formatting, strict types, lint and secret scanning. The planner exchanges selected endpoints, query text and attribution offline, clears old results, and requires explicit recalculation. Regression coverage uses a device-location origin and searched destination, verifies disabled states and reversed transport coordinates, and confirms no automatic search/location/calculation. Backend/native source unchanged; no new production build or live provider/browser visual QA in this bounded pass.

Map alternative recovery pass: **221 TypeScript/component tests passed**, including 12 map lifecycle tests, plus contracts, formatting, strict types, lint, production web build and secret scanning. Selecting alternatives reuses the loaded map/source/markers online or offline; latest committed geometry wins during lazy import/style loading. Invalid geometry tears down the old map and requires an explicit retry after valid input returns. Root review identified setup cleanup and invalid-input retry gaps; both were fixed and covered. Offline post-ready provider errors retain the map with a degraded-details notice; online/pre-ready failures remain retryable errors. SDK behavior is simulated in tests; no live Mapbox rendering or new device/visual QA is claimed. Backend/native source unchanged. Offline architecture/outbox notes now distinguish implemented journey queues from temporary route views. Downloaded native maps/navigation remain pending.

Connection-loss interruption pass: **217 TypeScript/component tests passed**, with contracts, formatting, strict types and lint; secret scan clean. The planner now aborts in-flight network requests and releases busy state immediately on an offline event while retaining loaded directions. Tests cover a late search response during a newer retry, a late recalculation response, passive reconnect and an uninterrupted explicit local location reading. No backend/native/provider configuration changes; prior production build and Java results below remain the latest build/backend evidence. This pass used component tests, not live provider or device QA.

Web maps/directions pass: **215 TypeScript/component tests and 88 Java tests passed**, with contracts, formatting, strict types, lint, core Gradle check/bootJar and web production build. Production dependency audit found no known vulnerabilities; secret scanning passed. Independent source review closed the implementation findings; ADR 0019 records the Medium provider browser-cache residual risk and release verification obligation. Actual browser inspection of a temporary synthetic directions view verified step controls, alternative reset and a 360px-wide content layout; a blank missing-token map area was fixed and rechecked. The fixture was removed before production build. This was not a mobile-device viewport test or live provider/authentication test. Mapbox is not configured, and Android SDK/native project are absent. GPS-following navigation, downloaded offline regions, persistent route recovery and offline rerouting remain unimplemented. Connection-loss support currently retains manual directions only while the view stays open.

Native credential-store pass: **200 TypeScript/component tests passed**, with contracts, formatting, strict types and lint. The 15 vault tests and two adapter tests cover stale/copied tickets, delayed loads/writes, expiry while queued/in flight, malformed records, ambiguous storage failures and error redaction. Independent source review's expiry-race finding was fixed and re-reviewed with no remaining implementation findings. Expo configuration introspection confirmed Android backup/extraction resource references and no Face ID usage permission; installed SecureStore XML excludes its data from cloud backup and device transfer. Production dependency audit found no known vulnerabilities; secret scanning passed. Android JavaScript export passed (1,111 modules, 2.9MB Hermes bundle). The vault is not mounted in the preview, and this export/mocked coverage does not verify native Keystore execution, a working sign-in flow, device lock/reinstall behavior or server revocation. Backend/web source was unchanged.

Native retirement pass: **183 TypeScript/component tests passed**, including contracts, formatting, strict types and lint. The file-backed SQLite suite passes all 12 tests; the original code failed six retirement regression cases before the fix. Reviewed production write paths all check retirement in their exclusive transaction. Android JavaScript export passed (1,111 modules, 2.9MB Hermes bundle), and secret scanning passed. This is not an APK or device test. Backend/web source was unchanged in this pass; the prior web production build remains the latest web build evidence. Native session transport, secure credentials, deletion UI and device/backup erasure verification remain pending.

Commute/navigation pass: **166 TypeScript/component tests passed**, with contracts, formatting, types and lint. Tests cover multi-entry journal history restoration, account-bound commute aggregation, DST, microsecond totals and browser ICU formatting differences. A temporary synthetic commute component page was inspected in the actual browser; it exposed a month-format mismatch that was fixed and rechecked successfully. The temporary page and diagnostic were removed. This verifies the summary component, not live authentication or native device behavior. Web production build, Android JavaScript export (2.9MB Hermes bundle) and secret scanning passed. Backend code was unchanged in this pass; Android export is not device QA.

Journal recovery pass: **154 TypeScript/component tests passed**, including contracts, formatting, types and lint. Web production build and secret scan passed. The prior full journal core API suite passed **85 Java tests**; the subsequent scoped JSON validation fix passed all 6 journal HTTP tests. Numeric strings, fractional values and duplicate known fields now return400 without writes. Source review and tests cover account-switch clearing, retained draft access, exact mutation retries, conflict review/discard, transaction failure and deletion retirement. Browser tools recovered: the signed-out Trips desktop layout was visually inspected after restarting the local server. This is not authenticated journal visual QA. Arbitrary multi-entry browser history jumps remain an open Medium release issue; see `docs/security/TRIP_JOURNAL_THREAT_REVIEW.md`.

Latest place-search/location pass: **120 TypeScript/component tests and 65 Java tests passed**, with contracts, formatting, types, lint and Gradle check/bootJar. Secret scan clean. The final web production build passed, including one-time location and both routing endpoints. Synthetic tests cover fixed provider hosts, temporary lookup, query/result limits, account/CSRF/origin/rate guards, explicit selection, stale responses, account changes and location denial/accuracy/timeout/cancellation. Visual capture failed twice with `SetIsBorderRequired failed: No such interface supported (0x80004002)`; rendered UI/mobile layout verification remains pending. The temporary review page was removed. Old active journeys outside the recent20 page now get an owner-bound detail check before atomic restoration; missing/unavailable detail leaves saved work unchanged.

Latest routing pass: **102 TypeScript tests and 61 Java tests passed**, with contract drift, formatting, strict types, lint and Gradle check/bootJar. Secret scanning found no leaks. Authenticated routing, bounded Mapbox transport, same-origin proxy and cancellable browser calculator are implemented; route-selection UI and live provider/OAuth configuration remain pending. Dependency security fixes were verified with zero production audit advisories and a successful Android JavaScript export. These results supersede the historical totals below.

Latest post-crash journey/storage pass: **93 TypeScript tests passed** with contracts, format, strict types and lint. Gradle check/bootJar passed after adding authenticated journey HTTP coverage and account-switch protection. Native Android export passed after snapshot storage; complete web/admin builds and Android export passed. Tests cover owner isolation, request guards, stable retries, bounded pagination, deletion races, atomic SQLite/IndexedDB result acknowledgement, failed writes, stale leases and transport outcomes. Final secret scan passed, including the IndexedDB addition. Real Google/native device testing remains pending.


Latest web account pass: **51 Java tests and 40 TypeScript tests passed**, zero failures/errors; Gradle check/bootJar, contract drift, formatting, types, lint and web production build passed. Secret scan clean. Renewal/revocation, recent-auth deletion, owner cascade and concurrent deletion/renewal have disposable PostgreSQL coverage. HTTP deletion checks cover CSRF, explicit account confirmation and fresh sign-in. Phone deletion dialog and unavailable-service recovery were checked in a temporary synthetic component preview, removed before the production build. Screenshots: delete-account-mobile.png and delete-account-error-mobile.png. Profile preview remains intact. No live Google sign-in has been tested.


Latest browser transport pass: **42 Java tests passed**, zero failures/errors; Gradle `check bootJar` passed. **31 TypeScript tests passed**, along with generated-contract drift, formatting, strict types and lint. Secret scan passed. Real HTTP tests cover the cookie flow, CSRF/origin checks, limits, replay/logout, secure-cookie policy, cleanup and concurrent database rate checks. The Google verifier is a test-only substitute in the HTTP suite; independent signed-token tests verify cryptography. No live Google account login or browser UI test is claimed by this pass.

2026-09-08 session pass: Gradle `check bootJar` passed with **36 Java tests, zero failures/errors**. Seven new PostgreSQL tests cover hashed storage, one-use exchange, wrong binding, expiry (including during verification), concurrent exchange, rollback and revocation/disabled accounts. Secret scan passed. Local PostgreSQL, Redis and storage are healthy. No TypeScript changes; its last verified total remains 31 tests.

Latest Google identity pass: Gradle `check bootJar` passed with **29 Java tests and zero failures/errors**. New checks cover real token signatures and rejected claims, missing configuration, concurrent account creation and disabled accounts. Secret scan passed. No Google network/account or production database was used by the tests. TypeScript source was unchanged in this pass.

Latest outbox pass: contracts, formatting, TypeScript and lint passed; all **31 TypeScript tests passed**. Nine new tests cover queue retries, crash leases, blocked states, validation/bounds and the native adapter's SQL against real file-backed SQLite, including reopen, account isolation and failed-write rollback. Expo Android export passed (1,103 modules, 2.8 MB Hermes bundle). Secret scan passed. No native device or sync UI behavior is claimed.

Earlier backend pass: Gradle `check bootJar` passed with **21 Java tests, zero failures/errors**, and all three backend packages built. Backend source was unchanged in the outbox pass. The PostgreSQL integration suite exercises real disposable PostgreSQL 16, migrations and constraints, ownership, retries, simultaneous writers, pagination and denied unauthenticated writes in the persistence profile.

Previous passes verified consumer/admin production builds and Expo Android export, rendered web interactions at desktop and phone width, draft persistence after reload, commute ordering, dialog keyboard/Escape/focus behavior, search/bookmarks, and backup validation/restore/merge. Browser download delivery was not confirmed in the in-app browser; the visible backup-text fallback was verified. This is not evidence of real-device native behavior.

Local PostGIS, Redis and MinIO were healthy during this audit. No production services or personal data were used. Synthetic QA plans/bookmarks remain in the preview browser. GitHub Verify passed for initial commit60d16a7 (run34253287130); later local changes have not run remotely. The Git remote is configured as https://github.com/prasvino/routiqo.git; no deployment has been performed.

## Pending, in dependency order

1. Actual Google OAuth configuration and end-to-end testing; native credential transport. Web login/proxy, bounded renewal and recent-auth account deletion are implemented. Browser HTTP security, contracts and bounded expiry cleanup are implemented; proxy trust and rate/cleanup capacity still need deployment validation.
2. Complete cross-device restoration and unresolved-conflict UX; verify live OAuth and authenticated browser visuals. Web foreground dispatch, bounded recent restoration and confirmed-result reconciliation are implemented; native secure transport remains pending. Authenticated journey contracts/controllers, atomic native snapshots and web IndexedDB are implemented. Local plans remain separate from server journey records.
3. Implement the confirmed open-source migration (ADR 0021): provider contracts, MapLibre web rendering, bounded Valhalla/Photon adapters and paired runtime wiring are implemented. Provision and validate controlled regional routing/search services and tiles via Martin. Then validate native maps, bounded downloads/eviction/deletion, guidance and on-device rerouting. Regional datasets/services and Android development build/device QA are required; Mapbox credentials are no longer a target dependency. Existing web directions remain memory-only until a separate storage change.
4. Approved public Live publication design, durable block/report workflows, operator authority/audit and projection revocation. Private consent/context/signal persistence, rolling limits and contribution suspension are implemented; they do not authorize public output. Rooms/realtime are later scope.
5. Minimum safety/admin workflows for the pilot. Private text journals and local commute summaries are implemented; reminders/push, media and AI remain later scope.
6. Android Studio/device setup and native SQLite/restart/accessibility/performance QA; broader web large-text/reduced-motion/storage-error QA; native backup sharing/paste/device QA.
7. Production secrets, deployment/observability, remote CI and release verification.

## Next implementation reference

Read `docs/features/journey/SERVER_PERSISTENCE_SPEC.md` and ADR 0005 before extending persistence. Default preview needs no database; only the explicit persistence profile runs migrations. No migration was applied to the user's existing Compose volume in this pass.

For offline sync, read `docs/features/journey/OUTBOX_SPEC.md` and ADR 0006. Shared commands, native storage, web dispatch, atomic local snapshots and web queue storage are implemented. Broader reconciliation and native secure transport remain pending. Google is the first login method; phone OTP is deferred. Read `docs/features/auth/GOOGLE_SIGN_IN_SPEC.md` and ADR 0007 before continuing authentication.



Account-history pass (2026-09-12): **178 TypeScript/component tests passed**, with contracts, formatting, types and lint. Web production build and secret scanning passed. The verified-account workspace now offers explicit, bounded 20-row account-history browsing and opens journals for older completed trips. Transport rejects malformed, duplicate, unordered and non-progressing pages; component tests cover exact-cursor retries, authentication clearing, account changes and React Strict Mode effect replay. Reads do not mutate offline snapshots or queues. Backend and native code were unchanged. Authenticated rendered history, small-screen and live Google sign-in QA remain pending; automated component coverage is not live authentication evidence.

Native authentication HTTP pass (2026-09-12): opt-in native-auth now supports challenge/exchange, session read/renew, lineage logout and recent-auth deletion independently of web-auth. Strict bearer/header/query/body rules, database peer/account/challenge limits and redacted DTOs are implemented; browser CSRF and cookie-only resources remain intact. Lost-renewal logout with a retained predecessor revokes its successor and remains retryable. OpenAPI/types, ADR0020, setup/security/threat documentation and unmounted mobile response validators are updated. Full check passed **230 TypeScript/component tests across 38 files**, contracts, formatting, types and lint; core-api check/bootJar passed **95 Java tests across 21 suites, zero failures/errors/skips** using disposable PostgreSQL and synthetic identity verification. Secret scan and independent security review passed; the medium logout/rotation finding was fixed and regression-tested. No new device/UI/live-provider validation or deployment. Redirect-safe native networking, Google UI/challenge coordination, vault integration and native resource authorization remain pending. Android setup is being handled by the user.

Native HTTP adversarial verification pass (2026-09-12): actual HTTP coverage now
includes empty forbidden browser headers, a raw empty-query request target,
chunked oversized bodies, disabled/expired credentials across read/renew/delete,
and denial of native bearer credentials on browser journey resources. Full
core-api check/bootJar passed **98 Java tests, zero failures/errors/skips**; secret
scan passed. No production source changed. Initial fixture issues (HttpClient
normalizing empty query and invalid expiry chronology) were corrected; the final
requests exercise the intended cases. Added read-only Android prerequisite doctor;
normal and RequireReady modes ran under Windows PowerShell. SDK installation is in
progress: SDK folder, Build Tools and Platform Tools detected; API36, command-line
tools and selected JDK17 are not yet ready. This is file inspection, not device QA.

Native restoration prerequisite (2026-09-12): added an unmounted coordinator that
loads the secure vault, verifies the credential through an injected adapter and
returns only a matching accountId snapshot. Stale loads/responses, account changes,
expiry during verification, invalid clocks and storage/provider failures fail
closed. Clear invalidates pending verification before removing local credentials;
it does not claim server logout. External vault writers must invalidate restoration
before changing credentials. Eight new tests cover these boundaries; independent
security review approved. Contracts/format/types/lint and secret scan passed.
The first full test run hit a pre-existing Xcode dependency import timeout; targeted
rerun passed without changes and the final complete run passed **238 tests across
39 files**. No native transport/UI mounting or device/provider verification occurred.
Latest backend validation remains98 Java tests; backend source was unchanged.

Map provider direction changed by user (2026-09-12): ADR0021 selects MapLibre,
self-hosted Valhalla/Photon and regional OSM-derived tiles under Routiqo control.
Provider identity migration has started; existing renderer/adapters and earlier test results still
refer to Mapbox. Prioritize provider contract generalization, web renderer/adapters,
regional hosting and Android offline feasibility in that order. No Mapbox account
setup is required for the new target. Public free tiles are optional prototype-only;
true phone-offline rerouting remains a distinct native engineering gate.

Open-source migration phase 1 (2026-09-12): Java routing/search adapters now declare
mandatory typed identities, controllers emit the actual identity, and OpenAPI0.10.0
plus generated/shared clients accept only mapbox/valhalla routes and mapbox/photon
places. Unknown/cross-purpose identities and malformed/bounded results are rejected.
Route estimate labels follow the response identity. Existing Mapbox adapters still
serve production-configured requests; no Valhalla/Photon service or MapLibre renderer
has been enabled. Synthetic authenticated HTTP tests verify alternative identities.
Root review found no unresolved integration issue. Full TS check passed **242 tests
across 40 files**, contracts/format/types/lint; final contract drift check and secret
scan passed. Full core-api check/bootJar passed **98 Java tests across 21 suites,
zero failures/errors/skips**. No live provider/device/UI visual verification or new
production web build in this boundary pass. Next: migrate renderer and implement
bounded self-hosted provider adapters, then regional data/device verification.

## Open-source migration phase 2 — web renderer

MapLibre GL JS 6.9.0 replaces the direct Mapbox GL web dependency and CSS. Explicit
Show map now requires a reviewed same-origin `/maps/...json` style; missing/invalid
configuration leaves directions available without loading the SDK. No public map
provider or token fallback exists. URL guards reject external/out-of-namespace
resources, credentials, query/fragment, controls and encoded traversal/separators.

The pinned worker/shared module and license are generated locally during dev/build
under `/maplibre/6.9.0/`. Browser QA caught missing worker packaging, which is fixed;
route readiness now waits for source processing within the existing 20s deadline.
Reuse, offline retention, cancellation, latest geometry and cleanup remain tested.

Verification: full contracts/format/types/lint check passed; final suite **247 tests
across 40 files**, including **18 map tests**, passed. Production web build passed;
secret scan clean; production dependency audit reports no known vulnerabilities.
Independent security review approved URL guards, packaging and source-readiness
races. Actual CUA browser screenshots verified a synthetic local background style,
line, markers, alternative updates at 360px and missing-style fallback. Temporary
QA page/style, diagnostics and alternate build output were removed before build.
No real tiles, locations, OAuth, backend provider calls or Android device were used.
Backend unchanged; prior 98 Java tests remain the latest backend validation.

Next: bounded Valhalla/Photon adapters and controlled regional data/tile services,
then native MapLibre compatibility and downloaded-region lifecycle. Production
static-service redirect, logging, attribution and actual network verification are
still release gates. This completes web renderer migration, not maps/navigation
or full offline functionality. No push or deployment in this phase.

## Open-source migration phase 3 — Photon adapter

Implemented an unmounted Photon place provider with fixed operator-origin
validation, encoded explicit search, five-result limit, deterministic OSM IDs and
bounded multilingual labels/Point coordinates. Duplicate/trailing JSON, invalid
UTF-8 or surrogate pairs, malformed identifiers and oversized results fail closed;
errors are redacted and interruption preserved. Fixed OSM attribution replaces
untrusted provider attribution fields. No live configuration or browser flow changes.

Extracted a provider-neutral RoutingTransport shared with the existing Mapbox
adapters. The bounded HTTP implementation now rejects malformed UTF-8 and enforces
a whole-operation10-second timeout including a stalled response body, cancelling
on timeout/interruption. This corrects an existing header-only timeout limitation.

Final core-api check/bootJar passed **108 Java tests across22 suites**, with zero
failures/errors/skips, including7 Photon and4 transport tests. Synthetic loopback
HTTP verifies valid integration, no redirect following, byte bounds, invalid UTF-8,
stalled-body timeout and interrupted callers. Positive Tamil/non-BMP labels and
malformed-provider regressions passed. Secret scan and diff checks passed. Root and
independent security reviews resolved the timeout and Unicode/control findings.
No TypeScript/UI changes; prior247 TS tests and web build remain latest validation.

Pending: configure a controlled Photon regional service, implement Valhalla,
coordinate backend selection with truthful browser disclosures, and verify live
regional quality/logging/egress. No external query, dataset download, deployment,
provider switch or native navigation change occurred in this phase.

## Open-source migration phases 4–5 — Valhalla and regional routing guard

ValhallaRouteProvider now normalizes native JSON routing responses through the
existing domain interface. Fixed-origin POST keeps endpoints out of the URL;
explicit mode/units/instruction/alternative choices preserve the app contract.
The polyline6 decoder bounds varints and positions, validates accumulated ranges,
and checks maneuver shape indices. Strict bounded UTF-8/JSON rejects malformed
alternatives, instructions and totals. Only HTTP400/error442 becomes no-path;
other provider failures remain redacted unavailable errors. Actual native wire
shape and no-path status were verified against official Valhalla source.

Added bounded JSON POST transport (20KiB request, configured response cap up to1MiB,
whole-operation10s deadline, no redirects/credential forwarding) alongside GET.
Added independent RoutingRegion/RegionLimitedRouteProvider guard: reject endpoints
before provider access and reject out-of-region geometry/maneuvers across the
whole returned result. Conservative inclusive rectangles require finite ordered
bounds with longitude span under180 degrees. No automatic geographic inference,
route clipping, persistence or fabricated reroutes.

Both phases remain unmounted: production routing configuration is unchanged.
Coverage HTTP/client outcomes, runtime provider selection/disclosures, deployed
regional graphs/Photon/tiles and quality/egress/logging checks remain gates.
Android doctor remains unchanged: API36 and command-line tools are missing; a
JDK17 must be selected for Android separately from the backend's JDK25. No device
or real provider was used.

Verification: final core-api check and bootJar passed **129 Java tests across25
suites**, zero failures/errors/skips, including10 Valhalla and9 regional guard
tests. Loopback POST tests cover the actual provider path, no-path classification,
redirect refusal, exact request-byte limit and bounded error bodies. Guard tests
cover an outside intermediate geometry point despite inside endpoints. Independent
security review has no unresolved findings; secret scan and diff checks clean.
No TS/UI changes; prior247 TypeScript tests and production web build remain latest.
No push, deployment, reset-credit use or timer change occurred.
