# Routiqo reliability handoffs — batch 01

Prepared 2026-09-20 against commit `1b06ad8`. **Handoffs only; these tasks have not been implemented by this batch.**

## Why this batch

These 10 bounded test tasks address the journey-reliability items in `todo.md`: delayed responses, unavailable storage, interrupted operations, account isolation and preservation of saved work. They verify existing, inspected behavior without credentials, devices, infrastructure, schema migrations or new product decisions. They are engineering work, not ten new product features or a substitute for authenticated browser/device release QA.

The remaining public LIVE work requires privacy/publication and safety contracts. Real sign-in, maps infrastructure and device verification have external dependencies. Do not use this batch to enable those features or claim their release gates are complete.

## Dispatch

Give the other agent one numbered handoff at a time, with repository access. Each handoff contains objective, observed flow, file ownership, steps, acceptance, constraints and commands. Recheck current coverage before editing: another agent may have completed a case since this snapshot. If already covered equivalently, report that evidence rather than duplicating it.

| Order | Handoff                                                                                                    | Owned file                                           | Scheduling                                                          |
| ----- | ---------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- | ------------------------------------------------------------------- |
| 1     | [Planning storage failure recovery](01-planning-storage-recovery.md)                                       | `tests/planning-storage.test.ts`                     | Independent; owns only its test file.                               |
| 2     | [Journal cache eviction preserves unsent drafts](02-journal-safe-capacity-eviction.md)                     | `tests/browser-journal-storage.test.ts`              | Run before task 03; both own tests/browser-journal-storage.test.ts. |
| 3     | [Reject duplicate and orphan journal records without repair](03-journal-corruption-preservation.md)        | `tests/browser-journal-storage.test.ts`              | Run after task 02 to avoid concurrent edits to the same file.       |
| 4     | [Bound unavailable IndexedDB opens and dispose late handles](04-indexeddb-open-lifecycle.md)               | `tests/browser-storage-open-lifecycle.test.ts` (new) | Independent; owns a new test file.                                  |
| 5     | [Retain editor text after a stale local acknowledgement](05-journal-stale-acknowledgement-ui.md)           | `apps/web/components/journal-editor.test.tsx`        | Run before task 06; both own journal-editor.test.tsx.               |
| 6     | [Open and save cached journal drafts when refresh fails](06-journal-cached-refresh-fallback.md)            | `apps/web/components/journal-editor.test.tsx`        | Run after task 05 to avoid concurrent edits to the same file.       |
| 7     | [Ignore a delayed journal library result after account switch](07-journal-library-stale-account-result.md) | `apps/web/components/journey-workspace.test.tsx`     | Independent; owns journey-workspace.test.tsx.                       |
| 8     | [Cancel a stalled history response body on caller abort](08-history-inflight-body-cancellation.md)         | `tests/browser-history.test.ts`                      | Independent; owns browser-history.test.ts.                          |
| 9     | [Dispose a late journal response after caller cancellation](09-journal-late-header-cancellation.md)        | `tests/browser-journals.test.ts`                     | Independent; owns browser-journals.test.ts.                         |
| 10    | [Discard private response bodies on journey not-found](10-journey-not-found-body-disposal.md)              | `tests/browser-journeys.test.ts`                     | Independent; owns browser-journeys.test.ts.                         |

Tasks 02 → 03 and 05 → 06 must be serialized because they share a file. Other tasks may run independently with distinct ownership if the orchestrator finds parallel work useful. Never run multiple agents' commands against the same output or edit the same file concurrently. The numbering is the suggested single-agent queue; file sequencing is not a feature dependency.

## Integration and honest evidence

- Use the repository's selective orchestration: a bounded implementation worker, then root review/integration. No worker needs to redesign architecture.
- Keep production sources, auth, contracts, database versions, feature flags, dependencies and UI unchanged.
- If a test exposes a real defect, preserve the minimal reproducer and report the source boundary and failure. Do not expand this handoff into an unreviewed production fix.
- Each worker runs its focused checks and reports changed files, actual test counts/results and limitations. Do not claim a mocked component test proves persistence or server authorization.
- The integrator reviews all diffs, runs the combined suite once, and alone updates shared status documents if warranted. Keep broad `todo.md` release gates open.
- The earlier root-level dispatch recovery and acknowledgement-abort handoffs are already represented in the inspected baseline; do not repeat them.

## Commands for integration

Run from `D:\Pras\routiqo` using the repository's installed dependencies and supported Node/pnpm versions.

```powershell
pnpm exec vitest run --maxWorkers=2
pnpm typecheck
pnpm lint
pnpm format:check
pnpm secrets:check
git diff --check
git status --short
```

Individual handoffs provide formatting and targeted test/lint commands. For this test-only batch, building every application/backend per task adds little evidence; if the integrator requires a web build, use `pnpm --filter @routiqo/web build` once after integration. Do not start services, install packages, deploy, commit or push as part of these handoffs. A build is not real OAuth, provider or Android verification.
