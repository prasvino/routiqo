# Production improvements — batch 02

Prepared 2026-09-20 against baseline `1b06ad8`. **Planning handoffs only; production fixes have not been implemented in this preparation run.**

This separate folder contains **six small production improvements, each with required regression tests**. Unlike reliability-batch-01, every task changes real app behavior. Six were selected from inspected code rather than padding the queue with speculative features. These support the journey reliability/UI quality items in `todo.md`; they do not replace remaining release work.

## Ready to implement

| Order | Task                                                                                            | Production file                              | Required new tests                                |
| ----- | ----------------------------------------------------------------------------------------------- | -------------------------------------------- | ------------------------------------------------- |
| 1     | [Handle repeated Explore search parameters safely](01-explore-query-boundary.md)                | `apps/web/app/explore/page.tsx`              | `apps/web/app/explore/page.test.tsx`              |
| 2     | [Refresh departure labels at actual minute boundaries](02-departure-clock-minute-boundaries.md) | `apps/web/components/use-local-clock.ts`     | `apps/web/components/use-local-clock.test.tsx`    |
| 3     | [Clear stale success feedback when planning storage fails](03-planning-feedback-recovery.md)    | `apps/web/components/planning-provider.tsx`  | `apps/web/components/planning-provider.test.tsx`  |
| 4     | [Clean up failed planning downloads](04-backup-download-cleanup.md)                             | `apps/web/components/planning-backup.tsx`    | `apps/web/components/planning-backup.test.tsx`    |
| 5     | [Show recoverable save errors inside destination dialogs](05-destination-save-error.md)         | `apps/web/components/destination-dialog.tsx` | `apps/web/components/destination-dialog.test.tsx` |
| 6     | [Expose destination categories as a named control group](06-explore-filter-accessibility.md)    | `apps/web/components/explore.tsx`            | `apps/web/components/explore.test.tsx`            |

Each handoff includes the observed defect, exact files, proposed behavior, implementation steps, acceptance criteria, guardrails, tests, commands and limitations. Start with task 01 and continue in order. No production task depends on another; task 03 and 05 share a read-only provider relationship and should receive a combined smoke check. Tasks 01 and 06 both affect Explore through different owned files.

## Isolation from the agent already working

The previous batch remains in `docs/handoffs/reliability-batch-01/`. Do not alter that folder, its tasks or the other agent's uncommitted work. This batch's preparation changed only this new folder.

A separate handoff directory is not a separate Git checkout. Before edits, run `git status --short` and inspect current ownership. No files below may be modified by batch 02:

- `tests/planning-storage.test.ts`
- `tests/browser-journal-storage.test.ts`
- `tests/browser-storage-open-lifecycle.test.ts`
- `apps/web/components/journal-editor.test.tsx`
- `apps/web/components/journey-workspace.test.tsx`
- `tests/browser-history.test.ts`
- `tests/browser-journals.test.ts`
- `tests/browser-journeys.test.ts`

Also leave their associated storage, journal, workspace and transport runtime implementations unchanged. Read-only imports/context are allowed. None of the six assigned production files or proposed new tests overlaps these owned files.

Do not stage all files, reset, stash, clean or overwrite another agent's work. If ownership changes, finish inspection and report the collision rather than inventing a parallel rewrite. Do not update shared `todo.md` or `BUILD_STATUS.md`; root owns integration/status reporting.

## Execution and review

Use the repository's selective worker/reviewer workflow. One bounded worker can complete this sequentially. Every task must include a real production change and tests that expose the prior failure. If equivalent work has already landed, cite it instead of duplicating it.

Use installed Next.js guidance and the existing Routiqo design/accessibility checks. Keep all current storage/auth/privacy invariants. Do not add packages, schemas, server endpoints, feature flags or new architecture. Do not make speculative product expansions.

After all tasks, provide changed files, concise behavior changes, targeted and combined check results, plus browser checks actually performed. The user will bring the implementation back for review; no commit/push/deployment is requested here.

## Combined verification

Run from `D:\Pras\routiqo` once changes are integrated and concurrent file writes are finished:

```powershell
pnpm exec vitest run --maxWorkers=2
pnpm typecheck
pnpm lint
pnpm format:check
pnpm secrets:check
pnpm --filter @routiqo/web build
git diff --check
git status --short
```

A full suite at a changing working tree is not final integration evidence. Coordinate build timing so it does not interfere with the running preview. Individual handoffs give owned-file formatting/lint/test commands.

## Release work kept separate

See [blocked and separately scoped release work](BLOCKED_RELEASE_WORK.md). That document is a dependency inventory, **not an implementation queue for this worker**. Completion of this batch does not make Routiqo production-ready.
