# Dispose a late journal response after caller cancellation

## Objective and user value

Protect bounded cancellation even when fetch ignores its signal and returns a response after the operation has ended.

**Deliverable:** focused automated regression coverage only. Prepared 2026-09-20 against `1b06ad8`; implementation pending. This addresses a narrow subset of `todo.md` → “First release — journey reliability and quality,” especially failure recovery and preserving account-bound work. Do not mark that broader release section complete.

## Context and existing execution flow

The journal transport races the pending header promise against the caller stop signal, then disposes a late response without awaiting cleanup. Existing deadline and synchronous-abort tests do not cover caller abort during a still-unresolved header promise.

Expected behavior is the existing invariant described above, now protected by the cases below. There is no requested runtime behavior change.

## Read and file ownership

Start at repository root `D:\Pras\routiqo`. Follow applicable guardrails with progressive disclosure; read relevant sections, not every document in full.

- `AGENTS.md`
- `todo.md`
- `docs/quality/BUILD_STATUS.md`
- `docs/development/CODEX_ORCHESTRATION.md`
- `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`
- `docs/quality/CODE_REVIEW.md`
- `docs/quality/TESTING_STRATEGY.md`
- `docs/security/SECURITY.md`
- `docs/security/THREAT_MODEL.md`
- `docs/privacy/DATA_RETENTION_AND_DELETION.md`
- `docs/architecture/OFFLINE_ARCHITECTURE.md`

Read these task-specific sources/specifications and the existing test conventions:

- `apps/web/lib/browser-journals.ts`
- `docs/features/journey/BROWSER_JOURNAL_TRANSPORT_SPEC.md`

**Only implementation edit:** `tests/browser-journals.test.ts` — extend the existing suite and reuse its fixtures.

Scheduling: Independent; owns browser-journals.test.ts.

## Functional requirements and implementation steps

1. Return a controlled unresolved Promise<Response> from fetch. Begin readBrowserTripJournal with a live caller signal and wait for fetch to be called.
2. Attach a rejection expectation, abort the caller, and assert AbortError with the existing safe 'Journal request cancelled.' message. Assert the captured request signal is aborted.
3. Resolve the fetch promise afterwards with a Response whose stream cancel hook returns a never-settling promise; observe the cancellation without awaiting that cleanup promise.
4. Assert the operation was already settled, cleanup is attempted once, and no reader/body parsing or additional request occurs. Restore mocks; do not advance real deadline time.

## Acceptance criteria and required tests

Caller cancellation settles before headers arrive. The late body is cancelled without being read, and a stuck cancellation promise cannot delay the already-completed operation.

Implement each described failure/recovery case as a focused test or clearly named parameterized case. Tests must fail if the targeted guard is removed; avoid assertions that merely reproduce fixture data. Run the entire owned test file, including existing cases. The real function/component is the subject; only its external dependencies or explicit failure boundary may be controlled.

## Technical conventions and boundaries

**In scope:** the listed tests, minimal test-local fixtures, deterministic event control and correct cleanup.

**Out of scope:** production fixes, broad refactors, new test infrastructure, new dependencies, cosmetic/UI work, database/schema changes, authentication/authorization changes, infrastructure, new feature activation and unresolved product decisions. Do not edit runtime sources, shared contracts, lockfiles, package manifests, database migrations, existing guardrails, `todo.md` or `BUILD_STATUS.md`. The integrator owns shared status changes.

Do not mock the transport subject or replace caller cancellation with timeout. Keep private payload/error details out of exceptions and test logs.

Use strict TypeScript, existing Vitest conventions and the existing typed fixtures. Keep helpers local unless already shared. No sleeps, relaxed timeouts, disabled assertions or permissive whole-subject mocks. Restore globals/spies/timers even after a failed assertion. If the observed source no longer matches the handoff, report the discrepancy and bounded evidence instead of forcing a test around obsolete behavior.

## Security, privacy, accessibility, performance and errors

Use synthetic UUIDs/text only; never read real user browser storage or print private content. Preserve account isolation, exact mutation semantics and fail-closed handling. Client fixtures do not establish server authorization or real cross-device correctness.

No user interface is changed; accessibility is unaffected by the test-only scope. Do not change runtime messages or error/status contracts to simplify an assertion.

Use bounded fixtures and controlled promises/events; asynchronous tests must settle or clean up their resources. Never make production deadlines longer. Catch/observe deliberate rejection at the intended boundary so the runner cannot pass while reporting an unhandled rejection. Error text must remain the established safe message, not provider/private payload content.

## Validation commands

Run from repository root. Format only the owned file:

```powershell
pnpm exec prettier --write tests/browser-journals.test.ts
pnpm exec prettier --check tests/browser-journals.test.ts
pnpm exec eslint tests/browser-journals.test.ts --max-warnings 0
pnpm exec vitest run tests/browser-journals.test.ts --maxWorkers=2
git diff --check
git status --short
```

The integrator runs `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm secrets:check` and `pnpm exec vitest run --maxWorkers=2` once across the batch. Optional web build if required by integration review: `pnpm --filter @routiqo/web build`. No backend or deployment checks are necessary for a test-only change. Report checks actually run and any failures; do not infer passing results.

## Assumptions, dependencies, risks and completion report

Existing development dependencies are installed. No external credentials, live network provider, real sign-in or device is required. There is no unresolved product decision within this test boundary. Recheck for equivalent coverage before adding tests; avoid duplicating completed work.

The main risks are a fixture failing before the intended branch, a mock hiding real behavior, or asynchronous cleanup leaking across tests. Demonstrate the intended branch through observable calls/results, and follow the cleanup conventions above.

If the regression exposes a real defect, retain a minimal failing reproducer and report it for root triage; do not make a broad production fix under this handoff. At completion report the exact added scenarios, files, checks and residual limitations. Do not commit, push or claim a production release gate complete.
