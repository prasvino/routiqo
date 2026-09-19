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
| Presence consent | Privacy-owned PostgreSQL latest-row state with legacy journey-scoped CAS plus explicit revocation-precedence intents, opt-out reads and saturating atomic completion revocation. An owner-only browser GET/POST transport exists behind a separate default-off flag with durable limits and string generations; private active-journey consent UI with explicit check/allow/stop and uncertain-write fencing; no presence lease issuance, cache invalidation, discoverable presence or realtime publication enabled |
| Live route context | Route Update-owned PostgreSQL latest-row envelope with bounded private anchors, optional catalog provenance, fresh identity/exact-ID replacement, post-lock temporal checks, completion deletion and callable bounded expiry cleanup. A default-off internal two-transaction binder derives curated anchors from fresh guarded Valhalla geometry, uses a durable newest-attempt fence and rechecks consent/context authority before replacement; no public registration or output; optional bounded expiry job under ADR 0036 |
| Route-area display metadata | Optional bounded operator-curated labels in the immutable catalog; old unlabeled catalogs remain valid, malformed labels fail closed, diagnostics stay redacted and existing APIs/resolver output are unchanged |
| Private route-area choice reader | Internal read-only owned-journey service returns only the current context's complete labeled subset after consent, restriction, catalog and exact expiry checks. Immutable minimized snapshot; no Spring wiring, HTTP, provider calls, grants or writes. Owner transport remains pending; expected-context issuance is a separate internal prerequisite below |
| Expected-context signal issuance | Separate internal catalog-aware method requires exact context ID, route revision and consent generation under existing authority before grant/budget mutation. Legacy owner HTTP contract unchanged; guarded choice/issuance transport and Quick Signal controls remain pending |
| Private route preparation | Separately default-off owner-only GET recovery and explicit POST binding through the real configured binder; strict browser guards, minimal no-store DTOs, database account quotas and post-provider authority rechecks. Browser check/prepare controls use copied route choices, confirmed consent, synchronous scope invalidation and expiring private acknowledgements. No public Live output |
| Quick Signal storage | Internal PostgreSQL server-issued grants, retained private receipts, partial-unique actor/anchor/category contribution slots, atomic acceptance/withdrawal, fixed-minute plus rolling 20/hour actor budgets, 60-second anchor/category cooldown and bounded expiry cleanup. Mandatory durable suspension and grant-revision fencing protect new writes. A default-off catalog-aware facade derives issuance categories and rechecks current provenance/category for new acceptance; no operator workflow or publication; optional bounded expiry job under ADR 0036 |
| Private Quick Signal API | Separately default-off owner-only POST issue/accept/withdraw through the catalog-aware facade; strict browser guards, minimal no-store string-safe DTOs and separate database request quotas. Fixed private evidence/receipt lifetimes do not approve public retention; no UI or Live projection |
| Private safety foundations | Reviewed assessment, block and structured report-case domain transitions; durable contribution restrictions under the account transaction authority. Suspension denies new grants/acceptance and unsuspension cannot revive old grants. Durable private directed blocks with ordered account locks, bounded retained revisions and bilateral exclusion; no operator API, durable report workflow, public block targeting or public trust claim |
| Audited moderation prerequisite | Internal RESTRICT/RESTORE commands require finite action-specific database grants, ordered enabled operator/subject locks and exact revisions. Effect, minimized audit and an independent 20/hour operator debit commit atomically; bounded 30-day audit and independently default-off audit/debit maintenance. Signal ingestion is read-only; no admin authentication, seeded grants, queue or HTTP endpoint |
| Private browser LIVE clients | Typed consent, context and Quick Signal clients with strict schema/identity/lifetime validation, exact long values, bounded CSRF/stream deadlines and explicit cancellation. Consent and route-preparation controls mounted for confirmed active journeys; no signal controls, persistence, automatic retry or public projection |
| Persistence | Owner-scoped reads/completion, one active journey per owner, retry-safe start/completion, bounded keyset history; guarded browser journey endpoints; web client dispatch mounted on Trips |
| Offline queue | Bounded commands, native SQLite and web IndexedDB partitions; atomic result/acknowledgement, stale leases, retry/block states, single-command orchestration, web transport and IndexedDB dispatch adapter; Trips workspace with foreground/reconnect dispatch, bounded recent restore and confirmed-result reconciliation |
| Google identity | RS256 token verification with configured audience, issuer/time/nonce checks; durable subject-to-account mapping and disabled-account protection |
| Login sessions | Five-minute one-use challenge, atomic account/session exchange, hashed 15-minute opaque credentials, bounded rotation up to 12 hours, lineage revocation and disabled-account enforcement |
| Browser auth API | Opt-in challenge/exchange/session/logout and CSRF bootstrap; HttpOnly cookies, exact origin/CSRF checks, PostgreSQL rate limits, body limits and bounded expiry cleanup. Default preview still denies auth; web Google button UI and allowlisted same-origin proxy implemented; real OAuth configuration pending |
| Realtime/workers/admin | Buildable foundations only; no operational messaging, jobs or administration workflows |
| Engineering | Strict TypeScript, generated OpenAPI types/drift checks, formatting/lint/tests, Java architecture tests, secret scanner, local Compose services, CI definition |

## Verification

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

1. **Public LIVE privacy contract.** Resolve ADR 0038's collusion and withdrawal
   inference problem with measurable permitted inference, fixed partitions/windows,
   evidence independence, budgets and block/revocation semantics. Independently
   review adversarial whole-output tests before any public projection. This is an
   engineering/design gate, not merely a missing credential.
2. **Reporting, moderation and safe delivery.** Resolve canonical evidence
   references, current authorization/lock ordering and useful investigation
   retention before durable report intake. Internal scoped operator permissions
   and atomic audited restriction actions now exist; add strong administrative
   authentication, controlled grant administration, queue/case scope and revocation
   propagation. Public targeting and operational operator workflows remain pending.
3. **Actual LIVE interface.** Private consent controls are implemented; connect
   route-binding and contribution controls. Implement approved moment reads/list,
   one foreground request in flight, stale/suppressed/conflicting/offline states,
   account/journey clearing and exact explicit retry. Public output depends on the
   first two gates; private consent controls are not a working LIVE release.
4. **Journey and UI release reliability.** Broader cross-device restoration and
   unresolved conflicts; authenticated journal/history/backup QA; storage failures,
   interrupted writes, account switches, large text, reduced motion and screen
   readers. Existing local plans remain separate from server journeys.
5. **Native integration.** Redirect-safe network transport, Google UI/challenge and
   vault coordination, authenticated resources/reconnect dispatch, native MapLibre
   and actual development-build/device verification. Run Android doctor to establish
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
  before UI integration. Existing server ADRs and default-off gates still apply.
- [Journey transport](../features/journey/BROWSER_JOURNEY_TRANSPORT_SPEC.md),
  [journal transport](../features/journey/BROWSER_JOURNAL_TRANSPORT_SPEC.md) and
  [dispatch](../features/journey/DISPATCH_SPEC.md) before extending recovery.
- [Execution queue](../development/BUILD_PLAN.md) and the root
  [status and completion requirements](../../todo.txt).
