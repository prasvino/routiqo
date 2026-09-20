# Production improvements — batch 04

Prepared 2026-09-20 against `db622e6`. Working tree was clean at inspection. Batches 01, 02 and 03 are committed.

**Three ready production improvements with required tests. Handoffs only; no implementation performed.**

## Implementation queue

| Order | Task                                                                                                 | Owned files                                                                               | Dependency                                         |
| ----- | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | -------------------------------------------------- |
| 1     | [Discard unused history error-response bodies](01-history-error-body-disposal.md)                    | `apps/web/lib/browser-history.ts`, `tests/browser-history.test.ts`                        | First transport change.                            |
| 2     | [Keep history cancellation independent of stream cleanup](02-history-reader-cleanup-bound.md)        | `apps/web/lib/browser-history.ts`, `tests/browser-history.test.ts`                        | After task 01: same files.                         |
| 3     | [Allow explicit refresh of an already-loaded latest history page](03-refresh-latest-history-page.md) | `apps/web/components/journey-history.tsx`, `apps/web/components/journey-history.test.tsx` | Independent files; integrate with transport tasks. |

Tasks 01–02 improve the same response-cleanup lifecycle in deliberately small steps. Run sequentially under one owner; do not dispatch simultaneous writers. Task 03 exposes an existing explicit refresh operation to first/empty pages. It adds no automatic polling.

Batch 01's history cancellation test covered prompt cleanup, and its error-body tests covered a different journey transport. These tasks fix current history runtime gaps and extend existing tests, not duplicate the prior test-only work.

## Ownership

Only the four unique source/test paths in the table belong to this batch. Leave all previous handoff folders and batch 02/03 implementation files unchanged. Leave shared auth, storage, validation, contracts, schemas and feature flags unchanged. Root owns `todo.md` and `BUILD_STATUS.md`.

A separate folder is not a separate checkout. Before edits inspect `git status --short`; coordinate any new collision. Do not reset, clean, stash, stage everything or overwrite another agent's work.

## Working procedure

Use repository selective orchestration: bounded worker implementation, root review/integration. Reproduce each issue first, implement only the stated behavior and run the focused checks. Preserve existing tests. For task 02, prove settlement independently of a still-pending cleanup promise.

Do not use this batch to claim that the entire history transport has been redesigned or all 12-second deadline cases verified. Abort-ignoring header fetches, redirects beyond existing policy, richer cancellation UI and broader cross-device recovery remain separately scoped work.

## Integration checks

Run once after all edits settle:

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

Review error classification, asynchronous rejection handling, account reset behavior and request counts, not just passing test totals. Coordinate build timing with any running preview. The worker reports actual evidence and manual limitations; the user will bring the batch for review. No commit/push/deployment is requested.

## Release dependencies

See [blocked and separately scoped release work](BLOCKED_RELEASE_WORK.md). This batch adds reliable private history behavior; it does not activate public LIVE or make the app release-ready.
