# Native journal read validation ledger

Updated: 2026-09-24. Scope is the owner-only completed-trip journal API and native
read transport. Android journal browsing and editing are not part of this phase.

## Remaining implementation

- Mount explicit journal browsing from completed trips in native account history.
  Include loading, empty annotation, unavailable, offline and retry states; clear
  private content and reject late results on account/session changes.
- Design account-bound durable drafts and conflict recovery before native editing.
  Do not reuse the lifecycle outbox for journal annotation mutations.

## External validation

- Use the real Google OAuth and staging HTTPS configuration tracked in
  `NATIVE_ANDROID_PENDING.md` to verify owner reads and cross-account denial.
- Once browsing is mounted, exercise real annotations on an emulator and a
  representative physical Android device, including large text, accessibility,
  network loss, renewal, sign-out and delayed reads during account changes.

No new provider, database migration, journal retention or production activation is
required by the read transport. Existing planning backups and journey recovery
snapshots must not acquire journal content.

## Verification evidence

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

These tests use synthetic accounts. No journal browsing UI, configured-service
device trial or production readiness is claimed.
