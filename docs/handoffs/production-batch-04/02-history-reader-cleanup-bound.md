# Keep history cancellation independent of stream cleanup

Prepared 2026-09-20 against `db622e6`. **Ready implementation handoff; not implemented by this preparation run.**

## Objective and value

Attempt reader cancellation without awaiting its completion, observe cleanup rejection, and release the reader lock promptly. Preserve the original abort/read/validation outcome; cleanup must not extend the operation.

This is a production improvement with required regression tests, supporting `todo.md` journey reliability and quality. It is not a test-only task or completion of a release gate.

## Current architecture and behavior

readBoundedJson uses `await reader.cancel().catch(...)` in finally before releasing the lock. If the stream's cancellation promise never settles, an already-observed caller abort or size-limit failure never reaches the caller. Batch 01 tests only a stream whose cancellation completes promptly.

The web history component explicitly requests a single validated account-history page from the existing transport. It holds at most 20 rows in memory, uses keyset cursors and opens journals only for completed trips. Server authorization remains authoritative; component/account guards are not a replacement.

## Exact file ownership

- Production edit: `apps/web/lib/browser-history.ts`
- Extend existing tests: `tests/browser-history.test.ts`

Read-only references:

- `apps/web/lib/browser-journeys.ts`
- `docs/features/journey/ACCOUNT_HISTORY_SPEC.md`

Scheduling: Run after task 01. Reuse its local conventions; do not overwrite its tests or helper.

No other production/test file may be changed. Batches 01–03 are already committed at this baseline. Reuse their tests; this task adds different failure behavior rather than recreating earlier coverage.

## Applicable guardrails

Read relevant sections progressively:

- `AGENTS.md`
- `apps/web/AGENTS.md`
- `docs/product/ROUTIQO_MASTER_CONTEXT.md`
- `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`
- `docs/development/CODEX_ORCHESTRATION.md`
- `docs/design/ROUTIQO_UI_UX_SYSTEM.md`
- `docs/architecture/FRONTEND_ARCHITECTURE.md`
- `docs/security/SECURITY.md`
- `docs/security/THREAT_MODEL.md`
- `docs/privacy/DATA_RETENTION_AND_DELETION.md`
- `docs/quality/CODE_REVIEW.md`
- `docs/quality/TESTING_STRATEGY.md`

Follow the installed Next.js documentation required by web AGENTS under `apps/web/node_modules/next/dist/docs/` for framework-facing changes. For the UI task, use relevant project-local `.agents/skills/routiqo-ui-quality/SKILL.md` checks. No whole-app critique or redesign is requested.

## Implementation steps and functional requirements

1. After task 01, change only the reader cleanup section and any minimal private cleanup code in this module. Keep the distinction between an unlocked response body and the locked reader; body.cancel cannot replace reader.cancel on a locked body.
2. Handle cancellation cleanup as best effort, including synchronous throw and asynchronous rejection, and release the lock without waiting for the returned promise. Do not weaken the readChunk abort race or validators.
3. Add an in-flight stalled-read test: wait for a locked reader with bounded vi.waitFor, abort the caller, and use a stream whose cancel returns a controlled unresolved promise. Assert AbortError is delivered and the lock is released before resolving cleanup.
4. Add an oversized-stream case using more than the existing 32KiB limit with the same delayed-cleanup boundary; assert the existing limit failure settles without partial data. Cover rejected cleanup without an unhandled rejection.
5. Preserve the committed batch 01 prompt-cancellation test and its guaranteed abort/rejection observation. Settle controllable fixture promises in finally when needed; no real 12-second sleeps or unbounded polling.

## Acceptance criteria

- Caller abort and size-limit rejection settle while cleanup is still pending.
- The reader lock is released, cancellation is attempted and cleanup rejection is observed.
- Page/cursor validation, 32KiB limit, 12-second abort signal and the original error outcome are unchanged.
- New regressions expose the prior behavior and pass after the fix.
- All existing affected cases remain passing; no skipped assertions or relaxed timeouts.
- Only the owned files change, with scoped mock/global cleanup.

## Boundaries and risks

In scope: the described production improvement, minimal local helpers and targeted tests.

Out of scope: database/schema/auth changes, new endpoints, dependency additions/upgrades, other transports, storage adapters, shared validators, LIVE features, provider/infrastructure setup, native work, broad refactoring and deployment.

Do not claim complete history deadline hardening: header-fetch races that ignore AbortSignal are outside this small task. No shared deadline framework, schema, auth or other transport changes.

Do not update shared `todo.md`, `BUILD_STATUS.md`, prior batch handoffs or guardrails. Root owns status and integration after review. If a failure requires a larger design, retain a reproducer and report it rather than expanding this task.

## Security, privacy, accessibility and performance

Preserve same-origin/no-store/redirect-error requests and exact account binding. Never log/read error payloads for diagnostics or introduce automatic requests, persistent history copies or precise-location data. Test only synthetic identities and content.

Preserve T02 account isolation, T12 private history, T13 stale response handling and T14 bounded requests. Keep current row bounds and error classification. Do not treat a fulfilled cleanup attempt as proof of physical data erasure.

Use bounded waits and explicitly observed rejections. Async cleanup must not keep the operation open. Restore spies/globals/timers; never use an unbounded readiness loop or a fixed microtask count as the only proof of settlement.

For the UI task, retain ordinary labelled buttons, busy disabled state, status/error feedback and the existing disclosure layout. For transport-only tasks there is no visual change. No numerical performance claim or expanded network activity is authorized.

## Required tests and manual checks

Implement the cases specified in the steps using Vitest and existing fixtures. Use real Response/stream boundaries for transport tests and the real component with its existing mocked transport for UI tests. Do not mock the function under test.

Transport tests should prove settlement before a controlled cleanup promise resolves, not just assert final output after releasing that promise. UI tests must retain account-switch cancellation and transient-error recovery coverage.

For task 03, inspect the rendered control at desktop/narrow widths and by keyboard when a suitable configured preview is available. Real authenticated history QA requires actual identity configuration: record it as unverified when unavailable rather than enabling auth or claiming fixture results prove live integration.

## Validation commands

From `D:\Pras\routiqo`:

```powershell
pnpm exec prettier --write apps/web/lib/browser-history.ts tests/browser-history.test.ts
pnpm exec prettier --check apps/web/lib/browser-history.ts tests/browser-history.test.ts
pnpm exec eslint apps/web/lib/browser-history.ts tests/browser-history.test.ts --max-warnings 0
pnpm exec vitest run tests/browser-history.test.ts apps/web/components/journey-history.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

The integrator runs full tests/types/lint/format/secret checks and one web build after the batch; see README. Do not build repeatedly per task or disturb a running preview.

## Assumptions and delivery

Existing dependencies are installed. No credentials, infrastructure or device is needed for implementation and synthetic tests. No unresolved architectural decision lies inside these boundaries.

Check the working tree before editing; preserve concurrent work. Deliver files changed, behavior, actual check results and remaining manual limitations. Do not stage, commit, push or deploy without a new user request.
