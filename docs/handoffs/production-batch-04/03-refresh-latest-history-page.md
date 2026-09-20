# Allow explicit refresh of an already-loaded latest history page

Prepared 2026-09-20 against `db622e6`. **Ready implementation handoff; not implemented by this preparation run.**

## Objective and value

Keep the existing Latest journeys action available whenever a page is displayed, including a first or empty page. It explicitly invokes the existing load(null) path, stays disabled while busy and creates no automatic requests.

This is a production improvement with required regression tests, supporting `todo.md` journey reliability and quality. It is not a test-only task or completion of a release gate.

## Current architecture and behavior

The initial load button disappears after a page is loaded. Latest journeys is rendered only when displayed.cursor is non-null. Consequently, an empty first page or a first page without earlier results offers no action to refresh newly completed journeys while the component stays mounted.

The web history component explicitly requests a single validated account-history page from the existing transport. It holds at most 20 rows in memory, uses keyset cursors and opens journals only for completed trips. Server authorization remains authoritative; component/account guards are not a replacement.

## Exact file ownership

- Production edit: `apps/web/components/journey-history.tsx`
- Extend existing tests: `apps/web/components/journey-history.test.tsx`

Read-only references:

- `docs/features/journey/ACCOUNT_HISTORY_SPEC.md`

Scheduling: Distinct files; may be implemented separately. Run combined transport/component regressions after tasks 01–02 are integrated.

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

1. Remove only the cursor-dependent restriction around the existing Latest journeys action. Keep initial load, earlier pagination, failure retry and authentication recovery controls unchanged.
2. Reuse load(null), the current revision/AbortController guards, account-keyed component and one-page memory model. Do not introduce a second fetch method, refresh timer or new provider.
3. Extend the component tests: load an empty first page, verify Latest journeys is available, then explicitly refresh to show a newly returned completed trip.
4. Test refresh from a nonempty first page, null cursor, exact account/signal arguments and disabled controls during a deferred refresh. Rerender/collapse/reopen must not auto-fetch.
5. Reject a refresh transiently and assert the old page remains with explicit retry; preserve existing 401/403 clearing, account switch and journal eligibility tests. No new storage side effects.

## Acceptance criteria

- An empty or populated first page can be refreshed without remounting or navigating away.
- Exactly the explicit action triggers the next read with a null cursor; busy state prevents additional user clicks.
- Transient failures preserve the page; authentication/account cleanup and earlier-page behavior remain intact.
- New regressions expose the prior behavior and pass after the fix.
- All existing affected cases remain passing; no skipped assertions or relaxed timeouts.
- Only the owned files change, with scoped mock/global cleanup.

## Boundaries and risks

In scope: the described production improvement, minimal local helpers and targeted tests.

Out of scope: database/schema/auth changes, new endpoints, dependency additions/upgrades, other transports, storage adapters, shared validators, LIVE features, provider/infrastructure setup, native work, broad refactoring and deployment.

The always-visible action is the scoped usability direction derived from the existing Latest journeys behavior, not a new polling feature. No API/auth changes, automatic freshness claims or larger offline cache.

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
pnpm exec prettier --write apps/web/components/journey-history.tsx apps/web/components/journey-history.test.tsx
pnpm exec prettier --check apps/web/components/journey-history.tsx apps/web/components/journey-history.test.tsx
pnpm exec eslint apps/web/components/journey-history.tsx apps/web/components/journey-history.test.tsx --max-warnings 0
pnpm exec vitest run tests/browser-history.test.ts apps/web/components/journey-history.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

The integrator runs full tests/types/lint/format/secret checks and one web build after the batch; see README. Do not build repeatedly per task or disturb a running preview.

## Assumptions and delivery

Existing dependencies are installed. No credentials, infrastructure or device is needed for implementation and synthetic tests. No unresolved architectural decision lies inside these boundaries.

Check the working tree before editing; preserve concurrent work. Deliver files changed, behavior, actual check results and remaining manual limitations. Do not stage, commit, push or deploy without a new user request.
