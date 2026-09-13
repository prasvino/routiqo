# Build status — 2026-09-13

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
| Journey write authority | PostgreSQL account-before-journey transaction boundary shared by start/completion and internal owned-journey callbacks; deletion serialization, rollback, five-second lock timeout and redacted retryable failures tested. Completion now invokes configured participants atomically; durable context/receipts remain pending |
| Route planning | Authenticated temporary place search, opt-in guarded Valhalla estimates and Photon search with bounded directions, route alternatives and explicit MapLibre web map display using configured same-origin resources. Manual step review retains the last successful route through connection loss; no reload persistence, GPS-following navigation, downloaded offline maps or live provider verification yet |
| Trip journals (local preview) | Completed-trip private title/notes API, optimistic versions and retry identity; account-bound IndexedDB drafts, retained-journal library and editor connected to Trips. Explicit conflict recovery can discard only the exact reviewed device draft without a server write. No media, sharing or commute summaries; authenticated navigation/device QA remains pending |
| Presence consent | Privacy-owned PostgreSQL latest-row state with journey-scoped CAS, opt-out reads and atomic completion revocation. No HTTP consent commands, presence lease issuance, cache invalidation, discoverable presence or realtime publication enabled |
| Persistence | Owner-scoped reads/completion, one active journey per owner, retry-safe start/completion, bounded keyset history; guarded browser journey endpoints; web client dispatch mounted on Trips |
| Offline queue | Bounded commands, native SQLite and web IndexedDB partitions; atomic result/acknowledgement, stale leases, retry/block states, single-command orchestration, web transport and IndexedDB dispatch adapter; Trips workspace with foreground/reconnect dispatch, bounded recent restore and confirmed-result reconciliation |
| Google identity | RS256 token verification with configured audience, issuer/time/nonce checks; durable subject-to-account mapping and disabled-account protection |
| Login sessions | Five-minute one-use challenge, atomic account/session exchange, hashed 15-minute opaque credentials, bounded rotation up to 12 hours, lineage revocation and disabled-account enforcement |
| Browser auth API | Opt-in challenge/exchange/session/logout and CSRF bootstrap; HttpOnly cookies, exact origin/CSRF checks, PostgreSQL rate limits, body limits and bounded expiry cleanup. Default preview still denies auth; web Google button UI and allowlisted same-origin proxy implemented; real OAuth configuration pending |
| Realtime/workers/admin | Buildable foundations only; no operational messaging, jobs or administration workflows |
| Engineering | Strict TypeScript, generated OpenAPI types/drift checks, formatting/lint/tests, Java architecture tests, secret scanner, local Compose services, CI definition |

## Verification

Durable consent pass (ADR 0026): migration V8 stores one minimal latest consent
row per account with owned-journey and state constraints. Reads run under current
owned-journey authority and return opt-out without mutation. Explicit changes use
journey-scoped expected generations; actual Ghost transitions fence stale enables.
Configured journey completion revokes matching consent in the same account and
journey transaction, while failures and generation overflow roll back both.

Disposable PostgreSQL tests cover schema ownership/cascade, default reads, one-row
replacement, same-state updates, stale/reordered generations, independent-adapter
CAS races, consent/completion serialization with observed database lock waiting,
configured participant wiring, terminal retries and rollback. A same-state off
write preserves generation and therefore does not fence a delayed enable with the
same expected generation; a future public command API needs durable intent ordering.
No public endpoint, lease, cache/realtime invalidation or signal storage was added.

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

Next: bounded route-context transaction participation, followed by grant/receipt/
slot migrations and race/cleanup tests. This slice does not provide
public Ghost Mode commands, delivered-cache revocation, signal storage, issuer
endpoints or public Live output.

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
4. Privacy-reviewed presence, expiring rooms, blocking/moderation/reporting and realtime delivery. Existing pure policy tests do not implement these services.
5. Reminders/push, trip journals/commute summaries, media and AI adapters, useful admin workflows.
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
