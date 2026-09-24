# Native journal validation ledger

Updated: 2026-09-24. Owner-only completed-trip journal reads and native browsing
are implemented. Native durable editing remains the next implementation phase.

## Implemented browsing

- Completed account-history trips expose an explicit journal action. Inline detail
  shows authored plain text and lifecycle dates, with Back preserving the page.
- Loading, empty notes, unavailable/missing, session-required and offline states
  are distinct. Loaded content is retained offline only within the current session.
- Closing, selecting another trip, going offline and disposing cancel/fence reads.
  Account/session epoch changes remount private views; authentication denial clears
  private content. No journal persistence or writes are introduced by browsing.
- Opening a trip far down a full history page and returning to history reposition
  the section without animation. Dates and text wrap at increased font size.

## Remaining implementation

- Design account-bound durable drafts and conflict recovery before native editing.
  Do not reuse the lifecycle outbox for journal annotation mutations.

## External validation

- Use the real Google OAuth and staging HTTPS configuration tracked in
  `NATIVE_ANDROID_PENDING.md` to verify owner reads and cross-account denial.
- Exercise real annotations on an emulator and a
  representative physical Android device, including large text, accessibility,
  network loss, renewal, sign-out and delayed reads during account changes.

No new provider, database migration, journal retention or production activation is
required by the read transport. Existing planning backups and journey recovery
snapshots must not acquire journal content.

## Browsing verification

- Full TypeScript regression suite: **626 tests / 78 files passed**. Following the
  final scroll correction, the five journal controller/view tests, mobile
  TypeScript and focused lint passed again. Workspace formatting, all six typecheck
  targets, lint, contract drift and secret scan passed before that correction.
- Actual emulator screenshots cover loaded/empty/loading/error/missing/session
  and both offline states, plus 360 dp width at 130% text. View callbacks were
  exercised for open, Back and retry. A second temporary synthetic account-context
  fixture exercised the complete Trips/history/controller scroll flow with 20 trips
  and a long journal. Both fixtures were removed; neither used real credentials
  or established real-service operation.
- Evidence and limitations: `docs/quality/evidence/native-journal-browsing-2026-09-24/README.md`.
- Final restored-app Android build passed (43 seconds, 467 tasks, 13 executed),
  installed and launched. Startup logs showed the JavaScript app running with no
  fatal exception/signal or ReactNativeJS error in the inspected process capture.
- Root review checked cancellation, late replies, account epoch isolation, plain
  text preservation, eligibility and deep-page navigation. No unresolved blocking
  finding remains. Backend code is unchanged in this browsing phase; its prior
  **546 Java tests** are historical transport evidence, not a new run.

## Earlier read-transport verification

- Full TypeScript regression suite: **621 tests / 76 files passed** with two
  workers. Focused native transport/account tests: **15 passed**.
- Focused real HTTP checks: **15 Java tests passed**, covering native identity,
  journey and journal reads plus default-profile denial. Saved annotations and
  empty journals, owner isolation, ineligible trips/commutes, prohibited methods,
  browser-header rejection and unchanged stored content are covered.
- Full `:core-api:check`: **546 Java tests / 88 suites passed**, with zero
  failures, errors or skips, including PostgreSQL integration and architecture checks.
- Workspace formatting, all six typecheck targets, lint, generated OpenAPI drift
  and secret scan passed. The final changed TypeScript files also pass formatting.
- Root review checked the exact read-only route, existing server authority,
  bounded bridge responses, cancellation before delayed credential dispatch and
  final account-generation checks. No unresolved blocking review finding remains.
- Android doctor: all six prerequisites Ready. The x86_64 debug APK build passed
  (467 tasks, 15 executed) using a fresh unrestricted Gradle daemon. Two earlier
  attempts were stopped after the sandbox-bound daemon stalled Ninja; no build
  check was bypassed. APK SHA-256:
  `90543278bdb1070390fd3795f8da8ce49ec223818d962007859a95da1a998ab0`.
  This is compilation/packaging evidence, not a new device or real-service trial.

These earlier tests use synthetic accounts. Current browsing evidence is separately
listed above. No configured-service device trial or production readiness is claimed.
