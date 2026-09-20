# Production improvements — batch 03

Prepared 2026-09-20 at commit `54007b6`. **Three implementation handoffs; no runtime code changed during preparation.**

Batch 01 is committed. Batch 02 is being implemented in the same working tree. This new folder is a separate queue, not an isolated checkout. The tasks below each require a small production change and its tests. Three concrete improvements were selected rather than adding speculative work to meet a count.

## Ready queue

| Order | Task                                                                                              | Production file                             | Tests                                            |
| ----- | ------------------------------------------------------------------------------------------------- | ------------------------------------------- | ------------------------------------------------ |
| 1     | [Explain and recover from an empty commute weekday selection](01-commute-weekday-feedback.md)     | `apps/web/components/plan-dialog.tsx`       | `apps/web/components/plan-dialog.test.tsx` (new) |
| 2     | [Keep removal errors scoped to the current confirmation](02-plan-removal-error-lifecycle.md)      | `apps/web/components/trips.tsx`             | `apps/web/components/trips.test.tsx` (new)       |
| 3     | [Refresh commute month grouping when the browser resumes](03-commute-summary-timezone-refresh.md) | `apps/web/components/commute-summaries.tsx` | `apps/web/components/commute-summaries.test.tsx` |

The tasks have no feature dependency on one another and distinct file ownership. A single agent can implement them in this order. Follow repository selective orchestration; the root owns integration/security review. Tasks 01/02 use the planning provider's public contract but must not modify it.

## Ownership and coexistence

Batch 02 owns these production files and their tests; **do not edit them**:

- `apps/web/app/explore/page.tsx` and `apps/web/app/explore/page.test.tsx`
- `apps/web/components/use-local-clock.ts` and `apps/web/components/use-local-clock.test.tsx`
- `apps/web/components/planning-provider.tsx` and `apps/web/components/planning-provider.test.tsx`
- `apps/web/components/planning-backup.tsx` and `apps/web/components/planning-backup.test.tsx`
- `apps/web/components/destination-dialog.tsx` and `apps/web/components/destination-dialog.test.tsx`
- `apps/web/components/explore.tsx` and `apps/web/components/explore.test.tsx`

Leave batch 01 files, shared Modal, shared planning/summary helpers, storage adapters and auth/LIVE code unchanged. Leave `docs/handoffs/reliability-batch-01/` and `docs/handoffs/production-batch-02/` unchanged.

Run `git status --short` before edits. Do not reset/stash/clean, stage everything, or overwrite concurrent work. If ownership changes, coordinate the conflict; folder separation alone does not protect source files. Shared status documents are root-owned and should be updated only after integration review.

## Definition of done

- The intended production behavior changes and each new regression exposes the old behavior.
- Owned-file tests, lint/format and web type checks pass.
- UI changes are inspected in the affected rendered flow where available; record limitations honestly.
- Deliver a concise diff/check report for review. Do not commit, push, deploy or mark a broad release gate complete.

## Combined verification

After concurrent edits settle, the root reviews the combined diff and runs once:

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

Individual files provide focused commands. Coordinate web build timing so it does not disrupt the preview. No new packages or backend changes are needed.

## Release work outside this batch

[BLOCKED_RELEASE_WORK.md](BLOCKED_RELEASE_WORK.md) separates external and design prerequisites from this ready queue. These UI improvements do not make public LIVE, native integration or deployment ready.
