# Build status — 2026-09-12

Workspace: `D:\Pras\routiqo`. Working local-planning preview and tested backend foundations; not production-ready. This consolidated audit supersedes the previous continuation lists.

## Implemented

| Area | Current behavior |
|---|---|
| Web | Home, Explore, Trips, Profile; curated destination search/filter/details, bookmarks, editable trip and recurring commute drafts |
| Scheduling | Shared next-departure calculation, weekday recurrence, upcoming ordering, foreground refresh, DST-gap handling; no automatic journey completion or reminders |
| Local storage | Validated web localStorage with write-error handling and cross-tab refresh; native SQLite adapter; native restart behavior still needs device testing |
| Web backup | JSON export with disclosure and copyable-text fallback; file validation, restore preview and idempotent merge retaining current edits; no upload |
| Mobile | Expo four-tab UI sharing catalog, planning, scheduling and tokens; Android JavaScript export, not a tested APK; native backup text export/share and pasted-JSON restore implemented, device QA pending |
| Core API | Public health/catalog; opt-in authenticated journey start/get/list/complete with owner checks; default preview denies protected routes; PostgreSQL/Flyway persistence |
| Route planning | Authenticated temporary place search, explicit endpoint selection and private Mapbox estimates; optional one-time browser location with cancellation/accuracy checks. No map rendering/navigation or live provider verification yet |
| Trip journals (local preview) | Completed-trip private title/notes API, optimistic versions and retry identity; account-bound IndexedDB drafts, retained-journal library and editor connected to Trips. Explicit conflict recovery can discard only the exact reviewed device draft without a server write. No media, sharing or commute summaries; authenticated navigation/device QA remains pending |
| Presence policy | Internal consent generation and bounded lease rules tested; Ghost Mode invalidates older generations. No discoverable presence, durable consent service or realtime publication enabled |
| Persistence | Owner-scoped reads/completion, one active journey per owner, retry-safe start/completion, bounded keyset history; guarded browser journey endpoints; web client dispatch mounted on Trips |
| Offline queue | Bounded commands, native SQLite and web IndexedDB partitions; atomic result/acknowledgement, stale leases, retry/block states, single-command orchestration, web transport and IndexedDB dispatch adapter; Trips workspace with foreground/reconnect dispatch, bounded recent restore and confirmed-result reconciliation |
| Google identity | RS256 token verification with configured audience, issuer/time/nonce checks; durable subject-to-account mapping and disabled-account protection |
| Login sessions | Five-minute one-use challenge, atomic account/session exchange, hashed 15-minute opaque credentials, bounded rotation up to 12 hours, lineage revocation and disabled-account enforcement |
| Browser auth API | Opt-in challenge/exchange/session/logout and CSRF bootstrap; HttpOnly cookies, exact origin/CSRF checks, PostgreSQL rate limits, body limits and bounded expiry cleanup. Default preview still denies auth; web Google button UI and allowlisted same-origin proxy implemented; real OAuth configuration pending |
| Realtime/workers/admin | Buildable foundations only; no operational messaging, jobs or administration workflows |
| Engineering | Strict TypeScript, generated OpenAPI types/drift checks, formatting/lint/tests, Java architecture tests, secret scanner, local Compose services, CI definition |

## Verification

Journal recovery pass: **154 TypeScript/component tests passed**, including contracts, formatting, types and lint. Web production build and secret scan passed. The prior full journal core API suite passed **85 Java tests**; a focused HTTP scalar-validation audit is in progress. Source review and tests cover account-switch clearing, retained draft access, exact mutation retries, conflict review/discard, transaction failure and deletion retirement. Browser tools recovered: the signed-out Trips desktop layout was visually inspected after restarting the local server. This is not authenticated journal visual QA. Arbitrary multi-entry browser history jumps remain an open Medium release issue; see `docs/security/TRIP_JOURNAL_THREAT_REVIEW.md`.

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
3. Mapbox/native route integration, routing and location permissions, offline route behavior; provider configuration and native development build required.
4. Privacy-reviewed presence, expiring rooms, blocking/moderation/reporting and realtime delivery. Existing pure policy tests do not implement these services.
5. Reminders/push, trip journals/commute summaries, media and AI adapters, useful admin workflows.
6. Android Studio/device setup and native SQLite/restart/accessibility/performance QA; broader web large-text/reduced-motion/storage-error QA; native backup sharing/paste/device QA.
7. Production secrets, deployment/observability, remote CI and release verification.

## Next implementation reference

Read `docs/features/journey/SERVER_PERSISTENCE_SPEC.md` and ADR 0005 before extending persistence. Default preview needs no database; only the explicit persistence profile runs migrations. No migration was applied to the user's existing Compose volume in this pass.

For offline sync, read `docs/features/journey/OUTBOX_SPEC.md` and ADR 0006. Shared commands, native storage, web dispatch, atomic local snapshots and web queue storage are implemented. Broader reconciliation and native secure transport remain pending. Google is the first login method; phone OTP is deferred. Read `docs/features/auth/GOOGLE_SIGN_IN_SPEC.md` and ADR 0007 before continuing authentication.


