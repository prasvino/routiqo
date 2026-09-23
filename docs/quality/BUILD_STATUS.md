# Build status — 2026-09-23

Workspace: `D:\Pras\routiqo`. Working local-planning preview and tested backend foundations; not production-ready. The September 23 audit reconciles the committed reliability batches with this status; release gates below remain open.

The user has now authorized V3 community-summary implementation and staging evaluation under ADR 0055, behind a disabled production flag. The different production privacy contract is not accepted. Person-level research is paused with its existing code/docs preserved. Traveller-derived public LIVE remains disabled in production.

## September 24 native completion and history

Native Android account/journey integration is committed with debug APK and unconfigured-service emulator smoke evidence. Native account history adds explicit 20-row latest/earlier reads with strict owner/session isolation and no durable cache expansion. Final checks: **617 TypeScript tests / 75 files**, **545 Java tests / 88 suites**, all six typechecks, lint, formatting, contracts and secret scan passed. Native view evidence is labeled synthetic; real OAuth/TLS/maps and physical-device gates remain in [native Android](../validation/NATIVE_ANDROID_PENDING.md) and [native history](../validation/NATIVE_HISTORY_PENDING.md) ledgers.

## Implemented

| Area | Current behavior |
|---|---|
| Web | Home, Explore, Trips, Profile; curated destination search/filter/details, bookmarks, editable trip and recurring commute drafts |
| Recent web reliability | Batch 01 added storage, journal and transport regression coverage. Batches 02–04 implemented bounded Explore query handling, minute-boundary departure refresh, planning/backup/save error recovery, accessible category grouping, commute/plan feedback fixes, resumed time-zone grouping, and history response cleanup and explicit latest-page refresh. These are local web improvements, not authenticated cross-device or public LIVE verification. |
| Scheduling | Shared next-departure calculation, weekday recurrence, upcoming ordering, foreground refresh, DST-gap handling; no automatic journey completion or reminders |
| Commute summaries | Monthly counts and exact recorded elapsed minutes from the verified account's confirmed completed commutes saved on this device; time-zone-aware start-month grouping and explicit incomplete-history disclosure. Read-only, no invented distance or new persistence |
| Local storage | Validated web localStorage with write-error handling and cross-tab refresh; native SQLite adapter; local-plan restart passed on the API 36 emulator; authenticated restoration still needs configured-device testing |
| Native deletion storage | Atomic account retirement marker plus queue/snapshot removal prevents delayed updates recreating deleted data; restart, rollback and account isolation tested with file-backed SQLite. Native deletion UI now invokes server deletion before local retirement, with explicit local cleanup retry. |
| Native authentication API | Opt-in bearer-only challenge/exchange, session read/renew, lineage logout and recent-auth account deletion; bounded strict JSON, shared database peer/account/challenge limits and browser isolation. Native Google sign-in and bounded OkHttp transport are mounted for Android development builds; real OAuth/TLS trial remains pending. |
| Native credential store | Expo SecureStore adapter and bounded session vault, serialized writes/clear, stale-ticket rejection, expiry checks and fail-closed recovery. Mounted in native sign-in, cold-start verification, renewal, logout and deletion. |
| Account history | Web and native Android explicit 20-row owner-history browsing with earlier/latest pages, retry/offline handling and account/session isolation; no expansion of the durable recovery cache. Completed-trip journal access remains web-only. Native configured-service validation is in `docs/validation/NATIVE_HISTORY_PENDING.md`. |
| Web backup | JSON export with disclosure and copyable-text fallback; file validation, restore preview and idempotent merge retaining current edits; no upload |
| Mobile | Expo four-tab UI sharing catalog, planning, scheduling and tokens; native account controls, explicit Trips journey lifecycle/recovery and MapLibre regional basemap preview are mounted in a development client. x86_64 debug APK and unconfigured-service emulator smoke passed; real configured services and physical-device validation remain pending. Native backup text export/share and pasted-JSON restore exist. |
| Core API | Public health/catalog; opt-in authenticated journey start/get/list/complete with owner checks; default preview denies protected routes; PostgreSQL/Flyway persistence |
| Journey write authority | PostgreSQL account-before-journey transaction boundary shared by start/completion and internal owned-journey callbacks; deletion serialization, rollback, five-second lock timeout and redacted retryable failures tested. Completion invokes consent then route-context participants atomically; signal acceptance composes both current authorities |
| Route planning | Authenticated temporary place search, opt-in guarded Valhalla estimates and Photon search with bounded directions, route alternatives and explicit MapLibre web map display using configured same-origin resources. Manual step review retains the last successful route through connection loss; no reload persistence, GPS-following navigation, downloaded offline maps or live provider verification yet |
| Trip journals (local preview) | Completed-trip private title/notes API, optimistic versions and retry identity; account-bound IndexedDB drafts, retained-journal library and editor connected to Trips. Explicit conflict recovery can discard only the exact reviewed device draft without a server write. No media, sharing or commute summaries; authenticated navigation/device QA remains pending |
| Presence consent | Privacy-owned PostgreSQL latest-row state with legacy journey-scoped CAS plus explicit revocation-precedence intents, opt-out reads and saturating atomic completion revocation. An owner-only browser GET/POST transport exists behind a separate default-off flag with durable limits and string generations; private active-journey consent UI with explicit check/allow/stop and uncertain-write fencing; no presence lease issuance, cache invalidation, discoverable presence or realtime publication enabled |
| Live route context | Route Update-owned PostgreSQL latest-row envelope with bounded private anchors, optional catalog provenance, fresh identity/exact-ID replacement, post-lock temporal checks, completion deletion and callable bounded expiry cleanup. A default-off internal two-transaction binder derives curated anchors from fresh guarded Valhalla geometry, uses a durable newest-attempt fence and rechecks consent/context authority before replacement; no public registration or output; optional bounded expiry job under ADR 0036 |
| Route-area display metadata | Optional bounded operator-curated labels in the immutable catalog; old unlabeled catalogs remain valid, malformed labels fail closed, diagnostics stay redacted and existing APIs/resolver output are unchanged |
| Private route-area choice reader | Internal read-only owned-journey service returns only the current context's complete labeled subset after consent, restriction, catalog and exact expiry checks. Immutable minimized snapshot; ADR0045 adds separately gated owner HTTP and typed browser reads. No provider calls or grants from reads |
| Expected-context signal issuance | Separate internal catalog-aware method requires exact context ID, route revision and consent generation under existing authority before grant/budget mutation. Legacy owner HTTP contract remains available; ADR0045 adds the distinct guarded expected-context endpoint/client used by the private Quick Signal controls |
| Private route preparation | Separately default-off owner-only GET recovery and explicit POST binding through the real configured binder; strict browser guards, minimal no-store DTOs, database account quotas and post-provider authority rechecks. Browser check/prepare controls use copied route choices, confirmed consent, synchronous scope invalidation and expiring private acknowledgements. No public Live output |
| Quick Signal storage | Internal PostgreSQL server-issued grants, retained private receipts, partial-unique actor/anchor/category contribution slots, atomic acceptance/withdrawal, terminal known-command stop, fixed-minute plus rolling 20/hour actor budgets, 60-second anchor/category cooldown and bounded expiry cleanup. Mandatory durable suspension and grant-revision fencing protect new writes. A default-off catalog-aware facade derives issuance categories and rechecks current provenance/category for new acceptance; no operator workflow or publication; optional bounded expiry job under ADR 0036 |
| Private Quick Signal API | Separately default-off owner-only choice GET and expected issue/accept/withdraw/stop operations through the catalog-aware facade; strict browser guards, minimal no-store string-safe DTOs and separate database request quotas. Fixed private evidence/receipt lifetimes do not approve public retention or Live projection |
| Private safety foundations | Reviewed assessment, block and structured report-case domain transitions; durable contribution restrictions under the account transaction authority. Suspension denies new grants/acceptance and unsuspension cannot revive old grants. Durable private directed blocks with ordered account locks, bounded retained revisions and bilateral exclusion; no operator API, durable report workflow, public block targeting or public trust claim |
| Audited moderation prerequisite | Internal RESTRICT/RESTORE commands require finite action-specific database grants, ordered enabled operator/subject locks and exact revisions. Effect, minimized audit and an independent 20/hour operator debit commit atomically; bounded 30-day audit and independently default-off audit/debit maintenance. Signal ingestion is read-only; no admin authentication, seeded grants, queue or HTTP endpoint |
| Private browser LIVE clients and controls | Typed consent, context, choice/expected-issuance and terminal command-stop clients with strict schema/identity/lifetime validation, exact long values, bounded CSRF/stream deadlines and explicit cancellation. Confirmed active journeys mount consent, route preparation and deliberate private Quick Signal controls. A successful fresh bind supplies exact account/journey/consent/selection/context authority; GET alone cannot authorize contribution. The UI permits one explicit issue/accept, keeps at most five same-account recovery handles in workspace memory, supports Stop after Ghost Mode or completion, expires receipt metadata, never writes automatically or offline, and shares the navigation warning with journal protection. No browser persistence, feature activation or public projection |
| Official alert pilot | Default-off active-journey GET for bounded NDMA SACHET CAP weather alerts covering the Chennai district area, with current owner/rate checks, fixed-host XML validation and ETag reuse. Web list labels official source, district scope, expiry, offline/stale/empty/error states and keeps private Quick Signals separate. Full `pnpm check` passed (558 tests/63 files), web build passed and sequential `:core-api:check` passed (437 tests). A live feed/CAP shape smoke check passed on September 23; the feed had no Chennai match at that time. Real OAuth, coverage when a Chennai alert exists and rendered device QA remain pending. This is not traveller-derived public LIVE publication. |
| Traveller public LIVE prerequisites | V17 adds private dual-review verified-person authority. V18/V20 add per-receipt intent, one-person/window reservation, default-off owner share/Stop API and account-wide recovery after reload or journey completion. V19 adds a disconnected private window evaluator; bounded cleanup is separately default off. A default-off browser control requests consideration and exposes owner Stop recovery. ADR 0051's source-dependent suppression and ADR 0052's immutable-window follow-up failed independent privacy review. The user chose a measurable person-level guarantee; proposed ADR 0053 has a V21 pilot-wide person claim and an isolated 64-bit capped sampler. ADR 0054 records the selected irreversible Share direction. V22 adds a disconnected internal frozen Share foundation with atomic V21 claim, owner recovery, verification-row locking and aligned pilot manifests; independent code review accepted that isolated foundation after two fixes. Full `:core-api:check` passed with 484 tests/77 suites. A later lock-based cutoff can cause person-dependent deadline failures; the proposed immediate Share still lacks a commit/window seal proof. Transcript, stable person identity, consent/retention and real-density utility gates remain open. Earlier `pnpm check` (572 tests across 66 files) and web production build passed on September 23. No public traveller feed is enabled. |
| V3 community traffic staging implementation | V23/V24 candidate/debit, fresh Share/Stop/recovery, catalog-versioned `REPEATABLE READ` terminal publisher, canonical active-journey reader/report, audited suppression and independent retention maintenance are implemented. Generated contracts and mounted web controls use separate default-off production flags. The 12/10/80% rule is a staging heuristic, not privacy protection. Real OAuth/regional data, moderator trial, restore/device QA, utility and production privacy acceptance remain open; no V3 production output is enabled. |
| V3 moderator staging workflow | V25 and ADR 0056 add separate default-off admin authentication, finite grant checks, bounded report queue, audited dismissal/suppression, cleanup, generated transport and an admin UI. Consumer sessions cannot operate it. Real admin OAuth/MFA, supervised finite-grant operation, operator trial, appeals and retention review are pending. |
| V3 operator grants for staging | V26 and ADR 0057 add a separately disabled grant-administrator boundary for exact-account, short-lived V3 review/suppression grants, issue/revoke audit, bounded cleanup, generated transport and an admin panel. A root grant cannot be created through HTTP; real root bootstrap, OAuth/MFA, named operator trial, supervision and retention review remain open. |
| Persistence | Owner-scoped reads/completion, one active journey per owner, retry-safe start/completion, bounded keyset history; guarded browser journey endpoints; web client dispatch mounted on Trips |
| Offline queue | Bounded commands, native SQLite and web IndexedDB partitions; atomic result/acknowledgement, stale leases, retry/block states, single-command orchestration, web and native transport; Trips workspace with foreground/reconnect dispatch, bounded recent restore and confirmed-result reconciliation. Native device restart and network QA remain pending. |
| Google identity | RS256 token verification with configured audience, issuer/time/nonce checks; durable subject-to-account mapping and disabled-account protection |
| Login sessions | Five-minute one-use challenge, atomic account/session exchange, hashed 15-minute opaque credentials, bounded rotation up to 12 hours, lineage revocation and disabled-account enforcement |
| Browser auth API | Opt-in challenge/exchange/session/logout and CSRF bootstrap; HttpOnly cookies, exact origin/CSRF checks, PostgreSQL rate limits, body limits and bounded expiry cleanup. Default preview still denies auth; web Google button UI and allowlisted same-origin proxy implemented; real OAuth configuration pending |
| Realtime/workers/admin | Realtime and workers remain buildable foundations without operational messaging; the admin app contains only the default-off V3 moderator and grant-administration staging workflows, neither exercised with real operator credentials. |
| Engineering | Strict TypeScript, generated OpenAPI types/drift checks, formatting/lint/tests, Java architecture tests, secret scanner, local Compose services, CI definition |

## Verification

September 23 native Android journey implementation:

- Full `:core-api:check` passed **544 Java tests / 88 suites**, zero failures/errors/skips. Full `pnpm check` passed **607 TypeScript tests / 74 files** with contract drift, format, type and lint checks. Focused native account/transport/map/SQLite checks passed 21 tests. `git diff --check` and the secret scan passed. These are synthetic identity and local protocol tests; Android compile and device QA are tracked in [the native pending ledger](../validation/NATIVE_ANDROID_PENDING.md).
- V27 widens only the native challenge nonce storage. Native bearer journeys remain separately opt-in, and the V3 production flags are unchanged. Android staging still needs real Google OAuth registration, HTTPS backend, reviewed regional map resources and a device trial.

September 23 V3 operator grant administration:

- Focused PostgreSQL and admin HTTP checks passed **42 tests**, including uniform denial before target lookup, queue/action versus revocation ordering, DB-time expiry, replay after root expiry/revocation, audit capacity and audit-insert rollback. Full `:core-api:check` passed **542 Java tests/88 suites**, zero failures/errors/skips. Full `pnpm check` passed **598 tests/71 files** with contracts, formatting, typecheck and lint; the admin production build and secret scan passed. A temporary mock-upstream browser pass inspected the grant panel at desktop and 320 px; fixtures were removed. Independent adversarial review's four Medium findings were corrected and re-reviewed with no remaining concrete issue in scope. See [implementation evidence](../validation/V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md) and [real trial handoff](../validation/V3_STAGING_TRIAL_PENDING.md). Real OAuth/MFA, root bootstrap and operator trial remain unverified.

September 23 V3 moderator staging workflow:

- Full `:core-api:check` passed **526 tests/86 suites** with PostgreSQL integration coverage and zero failures, errors or skips; the final focused admin HTTP test also passed after origin-equivalence hardening. Full `pnpm check` passed **594 tests/70 files**, including eight admin client/proxy tests, generated contracts, formatting, typecheck and lint. The admin production build and secret scan passed. A temporary browser fixture exercised desktop and 320 px layouts, unavailable evidence, suppression failure and exact retry, dismissal and empty pagination; the fixture was removed before build. An independent adversarial review's report/review race, session expiry, origin and queue-bound findings were corrected and re-reviewed. See [staging implementation evidence](../validation/V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md) and [real trial handoff](../validation/V3_STAGING_TRIAL_PENDING.md). No real admin OAuth, grant operation or regional pilot was exercised.

September 23 V3 community-summary implementation:

- Proposed ADR 0055 and `COMMUNITY_TRAFFIC_SUMMARY_SPEC.md` describe a distinct consented aggregation option using an actual publication snapshot, account limits and a provisional 12/10/80% staging rule. They explicitly retain residual participation inference and do not claim DP, legal anonymity, approval or real utility. The owner-selected ADR 0053/0054 research path and its four public-protocol P1 findings remain open.
- The owner subsequently authorized full V3 implementation and staging evaluation behind disabled production flags. Sequential `:core-api:check` passed **514 Java tests/85 suites**; `pnpm check` passed **586 TypeScript tests/68 files**, generated-contract drift, formatting, typecheck and lint; the web production build and secret scan passed. Browser Share/report/Stop/recovery/offline interactions and the final server-time feed were rendered with temporary simulated-transport fixtures, removed before build. See [staging implementation evidence](../validation/V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md) for exact coverage, synthetic utility and open release gates.

September 23 internal frozen Share foundation:

- V22 stores immutable pilot manifest metadata and an owner recovery link tied atomically to the retained V21 one-person claim. The internal service checks current owner/journey, private signal, consent, context, restriction, verification, catalog and whole-window pilot bounds. Verification-row locking serializes reviewer-account deletion with Share. No Spring bean, API, flag, UI, publisher, pilot data or V18 migration was added.
- Independent code review found a reviewer-deletion race and unaligned pilot-window defect; both were corrected and re-reviewed with no remaining P1/P2 in the disconnected foundation. PostgreSQL tests cover rollback, replay, duplicate-person accounts, account deletion, reviewer-deletion order and pilot-window boundaries. Stop/Ghost/completion/moderation race coverage remains pending for any active V2 action.
- From `backend`, a fresh `.\\gradlew.bat :core-api:check` passed: **484 tests across 77 suites, zero failures/errors/skips**. `git diff --check` passed. The public protocol's four P1 findings remain open; traveller output and V2 user actions remain disabled.

September 23 public LIVE protocol design pass:

- ADR 0054 and the focused protocol spec now describe a candidate irreversible explicit Share input and fixed whole-pilot transcript. An independent engineering adversarial review did **not** approve implementation or public release. It found four open P1 areas: commit/window sealing and deadline dependence, stable person identity across deletion, consent/retention compatibility, and operational/delivery transcript rules. Two P2 document defects were corrected. See [review findings](../validation/PUBLIC_LIVE_PROTOCOL_REVIEW_2026-09-23.md).
- That earlier design pass changed documentation only. The subsequent disconnected V22 foundation is described separately above. No flag was enabled, no V18 intent was migrated, and no publisher/reader or real-density utility was established. Traveller-derived public LIVE remains disabled.

September 23 status reconciliation:

- Commits `54007b6`, `edfacc3`, `db622e6` and `14b73c9` contain the batch 01 regression tests and batch 02–04 web source/test changes. The batch handoff README files describe their earlier preparation state; those queues are now implemented in the current tree.
- Current-tree `pnpm exec vitest run --maxWorkers=2` passed **551 tests / 61 files**. `pnpm contracts:check`, `pnpm format:check`, `pnpm typecheck`, `pnpm lint`, and `pnpm --filter @routiqo/web build` passed. Typecheck used five Turbo cache hits and ran the web package afresh.
- `pnpm secrets:check` could not connect to the Docker Desktop Linux engine and exited 1; it did not establish a clean secret scan. Backend checks and browser/device QA were not rerun for this documentation/status audit.
- The audit inspected commits, source ownership and release documents. It did not establish real OAuth, provider, device, multi-device, public LIVE or production behavior.

Latest delayed journey history restoration pass, 2026-09-20:

- History-only merging preserves an already confirmed completion when a delayed
  active observation has matching ID, kind and canonical microsecond start time.
  Immutable conflicts and inconsistent completion times still abort the entire
  transaction. Command acknowledgement retains its strict result validation.
- Incoming batches are copied/validated before asynchronous storage, duplicate
  IDs are rejected, and comparisons use the original transaction snapshot so
  bounded-cache pruning cannot resurrect an old active journey. Current outbox
  contents, account isolation and retired-account protection remain unchanged.
- All **488 TypeScript tests / 52 files** passed with `vitest run --maxWorkers=2`.
  The initial default-concurrency run had 487 passes and one existing Xcode
  dependency-import test timeout; the bounded-concurrency rerun passed without
  changing timeouts or assertions. `pnpm check` contract, formatting, type and
  lint stages passed. Secret scanning and diff checks passed; root source review
  corrected the cache-pruning edge. Focused verification passed 31 tests.
- No UI, backend endpoint, feature flag or persistence schema changed. Web build,
  backend tests and actual browser/device QA were not rerun for this storage-only
  slice; earlier evidence below remains historical.

Latest private Quick Signal UI pass, 2026-09-19:

- The active-journey panel uses only fresh-bind authority and exact displayed
  context, revision, consent generation, catalog category and safe-interaction
  intent. Scope, lifecycle and route-selection changes synchronously fence late
  work; expiry is checked at each async boundary and cleared one way. Account
  preflight protects Stop while Stop remains available after consent revocation,
  Ghost Mode or journey completion.
- Recovery is memory-only, same-account and capped at five handles. Uncertain
  acceptance/Stop remains explicit; there is no automatic retry, reconnect write
  or offline queue. Receipt metadata expires locally without claiming server
  deletion. Navigation warning behavior coexists with the trip journal guard.
- The latest full `pnpm check` passed **483 TypeScript tests across 52 files**,
  generated-contract drift, formatting, type and lint checks. Focused rendered
  lifecycle tests cover synchronous invalidation, stale identity, offline and
  unmount uncertainty, one-shot issuance, expiry including clock rollback,
  recovery capacity and focus preservation. The production web build passed.
  Actual component fixtures covered desktop/narrow controls, scaled reflow,
  journal coexistence and fragment navigation. An isolated Next.js fixture verified
  Link/Back cancellation and confirmed SPA departure; native confirmation decisions
  and transport/account data were simulated. Independent review approved after
  correcting history and hash navigation. See [scoped QA and limitations](PRIVATE_QUICK_SIGNAL_UI_QA.md)
  and [ADR 0048](../adr/0048-private-browser-signal-recovery.md).
- Secret scan and diff checks passed. Backend code was unchanged; the prior
  **431 Java tests/61 suites** remain the latest backend evidence, not a rerun.
- All private LIVE endpoints and cleanup jobs remain default off. No real OAuth,
  provider/catalog, device, public publication or deployment claim is made.

Latest terminal command-stop and contract-quality pass, 2026-09-19:

- ADRs 0046/0047 stop a known command under the existing owner/account transaction:
  consume an unused grant or withdraw its retained receipt. Stop works after
  consent/context/completion changes and is independent of the choice API flag.
  Strict browser client/proxy/contract support adds no automatic retry or UI.
- **431 Java tests/61 suites** and **420 TypeScript tests/48 files** passed,
  zero failures/errors/skips. Core check/bootJar, workspace types/lint/format,
  generated contracts, web production build and secret scan passed. Independent
  design/source/test review approved; no flags activated.
- The 76-test focused backend run covers both account-lock race orders, real
  rollback/redaction, cleanup fencing, forged ownership snapshots, strict HTTP,
  separate quota and partial flags. Choice-off security-chain testing isolates
  the facade with a mock; all-on HTTP separately verifies real PostgreSQL state.
- Contract validation now rejects stray Response Object fields before generation.
  Sixteen legacy unquoted descriptions produced 21 such fields; corrected quoting
  restores intended text. Five regression tests cover malformed/quoted fields,
  reusable responses, valid extensions and duplicate operation IDs. This focused
  guard complements existing tooling; it is not full OpenAPI semantic validation.
- The next private contribution interface has a reviewed lifecycle checkpoint,
  not mounted controls. Public publication, real OAuth/providers and device QA
  remain separate gates. Preview `/trips` returned HTTP 200 after restart.

Latest guarded choice/expected-issuance browser pass, 2026-09-19:

- ADR0045 adds two separately gated owner leaves and typed clients with exact
  tuple/freshness checks, bounded Unicode labels and no legacy fallback. Choices
  never issue grants; expected issuance shares the legacy request quota.
- **416 Java tests/57 suites** and **400 TypeScript tests/46 files** passed,
  zero failures/errors/skips. Core check/bootJar, workspace types/lint/format,
  generated contracts, web build and secret scan passed. Independent review
  approved after strict UTF-8 and YAML corrections; no flags activated.
- Dedicated expected-path consent-winning lock-race and grant-insertion rollback
  tests close ADR0044's previous evidence gaps. Max Unicode is checked through
  DTO serialization plus client/proxy tests; real catalog/provider/OAuth remain
  unverified. No contribution UI or public projection is claimed.

Latest internal expected-context issuance pass, 2026-09-19:

- ADR 0044 adds a mandatory exact context/revision/consent precondition inside
  the existing issuance transaction before grant construction or budget mutation.
  Legacy Java/HTTP issuance and acceptance/replay/withdrawal remain unchanged.
- **409 Java tests across 56 suites**, zero failures/errors/skips; core check
  and bootJar passed. Thirteen targeted tests passed first; independent production
  and final test review approved. Secret scan and diff checks passed.
- Tests cover stale tuples with/without an existing budget, same-anchor rebind,
  consent cycle, real replacement-winning lock race, exact nanosecond expiry,
  one authority callback and new-path acceptance/replay/withdrawal.
- Dedicated expected-path insertion-failure rollback and consent-winning issuance
  race tests remain documented follow-ups before browser exposure. Existing shared
  transaction regression evidence remains; no new HTTP contract/UI is claimed.

Latest internal owner choice reader pass, 2026-09-19:

- ADR 0043 composes existing account/journey authority and participant order;
  fails closed for foreign, off, suspended, stale, unprovenanced or incompletely
  labeled contexts. Results are bounded, immutable, canonically ordered and
  redacted. Reads create no grants and consume no contribution budget.
- **403 Java tests across 55 suites**, zero failures/errors/skips; core check
  and bootJar passed. Independent design/code review approved. Strengthened
  constructor and null-state regressions also passed in a subsequent targeted
  five-test run; production code was unchanged after the full suite.
- Full Instant precision prevents sub-microsecond expiry reversal. Snapshot
  lifetime is capped at 24 hours, with the exact boundary and +1 ns tested.
- This internal service is not wired to Spring or HTTP. A guarded default-off
  owner transport and coordinated exact displayed-context issuance semantics
  are still required before Quick Signal controls. No public gate is relaxed.

Latest internal catalog metadata pass, 2026-09-19:

- ADR 0042 adds only optional `displayLabel`, preserving old catalog shape and
  constructor compatibility. Strict field/Unicode/code-point/byte bounds and
  cause-free redaction are tested; labels do not reach current API DTOs.
- **398 Java tests across 54 suites**, zero failures/errors/skips; core check
  and bootJar passed. Independent design/code review approved. No endpoint,
  migration, flag activation, provider call or catalog provisioning was added.
- Character filtering rejects specified categories, not every invisible or
  confusable Unicode character. Operator readability, geographic/public-suitability
  review and consistent fresh-version rollout remain necessary.

Latest private route UI and routing transport pass, 2026-09-19:

- Explicit Check/Prepare controls connect the selected calculated route to the
  existing private binding API after confirmed consent. GET remains an observed
  context expectation, not proof of displayed-route association; fresh binding
  may resolve different geometry. No flags, storage or public output added.
- Synchronous consent/route epochs, parent re-verification and offline gates,
  scoped notices, terminal journey latch and passive expiry reject stale results.
  Independent review approved after two lifecycle/display corrections.
- Routing/search now capture request bodies before CSRF and bound CSRF, headers,
  streamed bytes and validation under one 18-second deadline, including abort-
  ignoring adapters. Late/error cleanup cannot hold an operation open.
- **365 TypeScript tests across 45 files** passed. Workspace types, lint,
  formatting, contracts, secret scan and web production build passed. That UI
  slice made no backend changes; later backend evidence is recorded above.
- Actual components were exercised in a labelled simulated-transport fixture:
  desktop/390/320 layouts, keyboard focus, route choice, bound/empty/conflict,
  pending Stop and offline/reconnect behavior. This is not live-provider or OAuth
  verification. See [QA evidence](PRIVATE_ROUTE_PREPARATION_UI_QA.md).

Latest moderation maintenance pass, 2026-09-19:

- Separate default-off audit/debit scheduler reuses existing domain cleanup;
  100 rows/category/tick, 60-second initial/fixed delays, isolated failures and
  no overlap or drain loop. Authentication has an independent named scheduler.
- **394 Java tests across 54 suites**, zero failures/errors/skips; core check and
  bootJar passed. Final strengthened job tests also passed separately after this
  run, covering full batches, next-tick recovery and redacted logging.
- Configuration tests exercise web/native authentication with moderation only,
  all three schedulers, absent/false flags, missing persistence and missing cleanup
  authority. Independent review approved design, production code and final tests.
- No database schema or retention policy changed; no maintenance flag activated.
  Staging backlog/alerts/capacity, backup retention and operator rollout remain.

Latest private consent UI pass, 2026-09-19:

- Explicit private check/allow/stop controls mounted only for confirmed active
  journeys; no automatic requests, browser persistence or backend activation.
- **329 TypeScript tests across 43 files** passed, including 32 focused consent
  and workspace tests. Workspace types, lint, formatting, contracts and secret
  scan passed. Web production build passed.
- Independent review approved after strengthening foreground result acceptance.
  Desktop/390 px/320 px fixture rendering, 44 px targets, keyboard focus and
  check/allow/stop/offline states verified. This was simulated transport UI QA,
  not real OAuth/end-to-end proof. See [QA evidence](PRIVATE_CONSENT_UI_QA.md).

Latest moderation pass, 2026-09-19:

- 16 focused real-PostgreSQL moderation tests and three command/receipt tests passed,
  including concurrent operators,
  grant revocation and post-lock expiry, action/deletion serialization, rollback,
  replay horizons, saturation, capacity, quotas and bounded debit cleanup. A real
  cleanup/writer lock race verifies that an expired slot can be deleted before a
  waiting writer creates a fresh charge, which survives subsequent cleanup.
- Architecture checks and independent adversarial code review passed. Guards cover
  both ordinary calls and method references to interface/concrete mutation paths.
- Full core check and bootJar passed: **386 Java tests across 52 suites**, no
  failures, errors or skipped tests. Secret scan passed. Artifact inspection
  confirmed the production JAR excludes the unaudited test-only helper.
- Operator deletion cascades and exact 720-hour audit retention across a London
  daylight-saving transition are tested. Negative bytecode fixtures verify the
  architecture guard detects forbidden direct calls and method references.
- ADR 0041 replaces the unaudited production helper with the audited boundary;
  the old helper exists only in test setup. Grant administration, strong operator
  authentication and operational reporting remain unfinished.

Previous integrated client pass, 2026-09-19:

| Check | Result |
|---|---|
| TypeScript integration | 308 tests across 42 files passed with two workers |
| Types and contracts | All workspace typechecks and generated OpenAPI drift check passed |
| Static checks | Repository lint, formatting and secret scan passed |
| Web production build | Passed; all existing routes generated/compiled |
| Independent review | Auth, private LIVE, journal and journey transport boundaries approved after fixes |
| Production dependencies | Audit reported no known vulnerabilities |
| Rendered Trips QA | Desktop, 390 px and 320 px layouts, route accessibility names and dialog focus restoration checked |

The first parallel test run encountered two existing mobile dependency timeouts.
Both passed in a focused rerun, and complete runs with two workers passed before
and after the final changes. No test timeout was increased or assertion removed.

The previous client pass implemented:

- Private browser consent, route-context and Quick Signal clients using generated
  types and strict runtime DTO validation. Exact long revisions and nanosecond
  instants are preserved. Inputs are captured before CSRF; late/expired work
  cannot launch a later write. These clients are not mounted in UI.
- Browser auth redirect refusal, exact success statuses, strict 16 KiB streamed
  JSON and one 12-second deadline for operations with internal CSRF.
- Journal transport with a shared 18-second deadline, strict 32 KiB streaming and
  nonblocking cancellation cleanup. Exact edits and acknowledgement rules retain
  unsent drafts on uncertain outcomes; failure copy describes an account save as
  unconfirmed rather than claiming it was never committed.
- Journey delivery/recovery with a shared 12-second deadline, 4 KiB journey and
  32 KiB history byte limits, immutable command snapshots and existing delivery
  classifications. Malformed or uncertain successes cannot acknowledge the queue.
- Trips weekday targets of at least 44 px, accessible route direction text and
  stacked date/time fields on the narrowest screens. See the
  [focused UI audit](TRIPS_UI_AUDIT_2026-09-19.md) for evidence and limitations.

Focused evidence includes 18 LIVE tests, 14 journal transport tests (42 with
storage/editor consumers), and 18 journey/dispatch tests. Adversarial review
covered redirects, malformed streams, stalled headers/body, shared CSRF deadlines,
late failures, cancellation that never settles, timer-starving empty chunks,
caller mutation during awaits, retained receipts and generic errors.

The initial integrated run caught a stale configuration-test expectation for the
old read/write interface. It was corrected to require the read-only interface,
preserving the missing-authority startup failure assertion; the complete rerun
passed. A final cleanup-test adjustment removes dependence on other test accounts'
expired rows while retaining the future-debit protection assertion.
The final complete run includes that correction and the added cleanup/writer race.
This backend-only phase did not rerun the unchanged frontend checks above.
Historical phase evidence is preserved in the
[verification archive](BUILD_STATUS_HISTORY_2026-09-19.md).

No public LIVE publishing, feature activation, real OAuth/provider/device QA,
production deployment, user database migration, push or timer was performed.
Default-off private endpoints remain default off. No synthetic data was presented
as real LIVE activity.

## Pending, in dependency order

1. **Public LIVE privacy contract.** ADR 0054 proposes an irreversible explicit
   Share commit and frozen whole-pilot input under ADR 0053's candidate person-level
   bound. It is not approved or implemented: current V18 Stop semantics, browser
   disclosure and deletion behavior conflict with it. Stable person identity,
   source-independent timing/failure behavior, real-density utility and independent
   adversarial whole-transcript review remain required before any public projection.
2. **Reporting, moderation and safe delivery.** Resolve canonical evidence
   references, current authorization/lock ordering and useful investigation
   retention before durable report intake. Internal scoped operator permissions
   and atomic audited restriction actions now exist; add strong administrative
   authentication, controlled grant administration, queue/case scope and revocation
   propagation. Public targeting and operational operator workflows remain pending.
3. **Actual LIVE interface.** Private consent, route preparation and private Quick
   Signal contribution/recovery controls are implemented. Implement approved moment reads/list,
   one foreground request in flight, stale/suppressed/conflicting/offline states,
   account/journey clearing and exact explicit retry. Public output depends on the
   first two gates; private controls are not a working public LIVE release.
4. **Journey and UI release reliability.** The batch 01–04 local web regressions
   and fixes above are complete. Broader cross-device restoration and unresolved
   conflicts; authenticated journal/history/backup QA; storage failures,
   interrupted writes, account switches, large text, reduced motion and screen
   readers still need release evidence. Existing local plans remain separate from
   server journeys.
5. **Native integration.** Redirect-safe network transport, Google UI/challenge and
   vault coordination, authenticated resources/reconnect dispatch, native MapLibre
   are implemented with debug APK and unconfigured-service emulator smoke evidence. Complete real OAuth/TLS/maps and physical-device verification. Run Android doctor to establish
   current machine readiness; do not rely on historical missing-tool lists.
6. **External configuration and operations.** Real Google OAuth and staging login;
   controlled regional Valhalla/Photon/tiles and reviewed anchor catalog; production
   databases/secrets/network/domain/TLS; migration/backup/rollback tests, operated
   cleanup capacity, monitoring, remote CI and gradual pilot rollout. Mapbox tokens
   are not required by the selected open-source direction.
7. **Later product phases.** GPS-following guidance and downloaded offline maps,
   rooms/realtime, map LIVE overlay, Ask Ahead, Pulse, reminders/push, media/sharing
   and AI. Existing web route directions are memory-only, not offline navigation.

## Next implementation reference

Start with the current task and load only the relevant documents:

- [LIVE scope](../features/live/ROUTIQO_LIVE_SPEC.md),
  [publication design](../features/live/COHORT_PUBLICATION_DESIGN.md) and
  [report intake proposal](../features/live/DURABLE_REPORT_INTAKE_PROPOSAL.md).
- [Private LIVE client contract](../features/live/BROWSER_LIVE_CLIENT_SPEC.md)
  and the implemented [Quick Signal lifecycle checkpoint](../features/live/BROWSER_QUICK_SIGNAL_UI_PLAN.md).
  Existing server ADRs and default-off gates still apply.
- [Journey transport](../features/journey/BROWSER_JOURNEY_TRANSPORT_SPEC.md),
  [journal transport](../features/journey/BROWSER_JOURNAL_TRANSPORT_SPEC.md) and
  [dispatch](../features/journey/DISPATCH_SPEC.md) before extending recovery.
- [Execution queue](../development/BUILD_PLAN.md) and the root
  [status and completion requirements](../../todo.txt).
