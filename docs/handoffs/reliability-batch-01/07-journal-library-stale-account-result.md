# Ignore a delayed journal library result after account switch

## Objective and user value

Prevent private titles from the previous account appearing after a switch while preserving access to the current account's library.

**Deliverable:** focused automated regression coverage only. Prepared 2026-09-20 against `1b06ad8`; implementation pending. This addresses a narrow subset of `todo.md` → “First release — journey reliability and quality,” especially failure recovery and preserving account-bound work. Do not mark that broader release section complete.

## Context and existing execution flow

JourneyWorkspace refreshes identity on focus, clears account-bound state, and lists retained journals in an effect whose active flag is invalidated during cleanup. The existing account-switch test closes an editor but does not cover an unresolved old library request.

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
- `apps/web/AGENTS.md`
- `docs/design/ROUTIQO_UI_UX_SYSTEM.md`
- `docs/architecture/FRONTEND_ARCHITECTURE.md`

Read these task-specific sources/specifications and the existing test conventions:

- `apps/web/components/journey-workspace.tsx`
- `apps/web/lib/journal-storage.ts`
- `docs/features/journey/WEB_JOURNAL_STORAGE_SPEC.md`

**Only implementation edit:** `apps/web/components/journey-workspace.test.tsx` — extend the existing suite and reuse its fixtures.

The nested web AGENTS instruction requires the relevant installed Next.js guide before coding. Resolve it under `apps/web/node_modules/next/dist/docs/`; preserve the existing component-test setup. No framework API change is requested.

Scheduling: Independent; owns journey-workspace.test.tsx.

## Functional requirements and implementation steps

1. Reuse the existing browserAccount/focus setup, valid journal fixtures and mocked JournalEditor. Return a controllable deferred library promise for A.
2. Switch the mocked identity to B and dispatch the existing focus event. Wait for listBrowserJournals(B), resolve it with B's distinct synthetic title.
3. Resolve A's original library promise last inside the test's normal act/waitFor pattern.
4. Assert B remains the only displayed library entry, A's title does not appear, and opening B invokes the existing mocked editor for B and its journey. Verify both exact account IDs were used for library reads.

## Acceptance criteria and required tests

A's delayed result cannot repopulate B's library; B's visible entry opens under B's identity, with no stale title exposed.

Implement each described failure/recovery case as a focused test or clearly named parameterized case. Tests must fail if the targeted guard is removed; avoid assertions that merely reproduce fixture data. Run the entire owned test file, including existing cases. The real function/component is the subject; only its external dependencies or explicit failure boundary may be controlled.

## Technical conventions and boundaries

**In scope:** the listed tests, minimal test-local fixtures, deterministic event control and correct cleanup.

**Out of scope:** production fixes, broad refactors, new test infrastructure, new dependencies, cosmetic/UI work, database/schema changes, authentication/authorization changes, infrastructure, new feature activation and unresolved product decisions. Do not edit runtime sources, shared contracts, lockfiles, package manifests, database migrations, existing guardrails, `todo.md` or `BUILD_STATUS.md`. The integrator owns shared status changes.

This verifies client state isolation, not server authorization or real OAuth. Do not change auth mocks globally, authority behavior, LIVE controls or component production code.

Use strict TypeScript, existing Vitest conventions and Testing Library's labelled/role-based queries. Preserve the jsdom test annotation, existing dialog mocks and cleanup. Keep helpers local unless already shared. No sleeps, relaxed timeouts, disabled assertions or permissive whole-subject mocks. Restore globals/spies/timers even after a failed assertion. If the observed source no longer matches the handoff, report the discrepancy and bounded evidence instead of forcing a test around obsolete behavior.

## Security, privacy, accessibility, performance and errors

Use synthetic UUIDs/text only; never read real user browser storage or print private content. Preserve account isolation, exact mutation semantics and fail-closed handling. Client fixtures do not establish server authorization or real cross-device correctness.

Query the existing labelled controls and status/error messages; preserve their accessible behavior. These DOM tests do not verify visual layout, focus across real browser navigation, assistive technology or device accessibility. No visual redesign or screenshots are required for this test-only scope.

Use bounded fixtures and controlled promises/events; asynchronous tests must settle or clean up their resources. Never make production deadlines longer. Catch/observe deliberate rejection at the intended boundary so the runner cannot pass while reporting an unhandled rejection. Error text must remain the established safe message, not provider/private payload content.

## Validation commands

Run from repository root. Format only the owned file:

```powershell
pnpm exec prettier --write apps/web/components/journey-workspace.test.tsx
pnpm exec prettier --check apps/web/components/journey-workspace.test.tsx
pnpm exec eslint apps/web/components/journey-workspace.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/components/journey-workspace.test.tsx --maxWorkers=2
git diff --check
git status --short
```

The integrator runs `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm secrets:check` and `pnpm exec vitest run --maxWorkers=2` once across the batch. Optional web build if required by integration review: `pnpm --filter @routiqo/web build`. No backend or deployment checks are necessary for a test-only change. Report checks actually run and any failures; do not infer passing results.

## Assumptions, dependencies, risks and completion report

Existing development dependencies are installed. No external credentials, live network provider, real sign-in or device is required. There is no unresolved product decision within this test boundary. Recheck for equivalent coverage before adding tests; avoid duplicating completed work.

The main risks are a fixture failing before the intended branch, a mock hiding real behavior, or asynchronous cleanup leaking across tests. Demonstrate the intended branch through observable calls/results, and follow the cleanup conventions above.

If the regression exposes a real defect, retain a minimal failing reproducer and report it for root triage; do not make a broad production fix under this handoff. At completion report the exact added scenarios, files, checks and residual limitations. Do not commit, push or claim a production release gate complete.
