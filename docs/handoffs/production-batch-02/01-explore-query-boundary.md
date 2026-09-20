# Handle repeated Explore search parameters safely

Status: ready for bounded implementation, **not implemented**. Repository inspection: 2026-09-20, baseline `1b06ad8` with another agent actively editing the prior reliability batch.

## Objective and user value

A malformed or repeated public URL parameter no longer crashes the discovery page.

This is a production improvement **plus required tests**, supporting `todo.md` → journey reliability and quality. It does not complete the authenticated release QA gate.

## Existing behavior and execution flow

The async Explore page types q as string and passes params.q directly into Explore. Repeated URL keys can supply string[]. searchDestinations immediately calls query.trim(), so an array reaches a string-only runtime boundary.

## Expected behavior and functional requirements

Normalize q at the page boundary: support string | string[] | undefined, use the first value for repeated keys, and use an empty string when absent or the array is empty. Keep ordinary single-value searches unchanged. This is a task-local normalization choice, not a new search feature.

## Exact scope and file ownership

Edit existing production file:

- `apps/web/app/explore/page.tsx`

Create the following test file (proposed new file at inspection time):

- `apps/web/app/explore/page.test.tsx`

Read-only context:

- `apps/web/components/explore.tsx`
- `packages/shared/src/catalog.ts`
- `docs/features/journey/LOCAL_PLANNING_SPEC.md`

Do not edit any other file for this task. If another agent has since created the proposed test file or changed the target, inspect and coordinate ownership rather than overwriting. Read the batch README for the previous agent's exclusion list.

## Applicable guardrails and architecture

Work from `D:\Pras\routiqo`. Read applicable portions of:

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

Preserve separation of UI state, validated device-local planning and authenticated server journeys. No new persistence, dependencies, providers, endpoint contracts or feature flags. Follow the installed Next.js documentation required by the web AGENTS file (`apps/web/node_modules/next/dist/docs/`) before changing framework-facing code. For UI review, use the existing project-local `.agents/skills/routiqo-ui-quality/SKILL.md` checks when applicable; this task does not authorize a whole-app redesign.

## Step-by-step approach

1. Read the installed Next.js guide for page searchParams, then widen the page prop type to its actual string/array shape.
2. Normalize in the page before creating Explore; keep the shared search function's string contract intact. Do not stringify arrays into comma-separated queries or add a new utility module for one expression.
3. Add a jsdom page test that awaits the server page function and verifies the child receives a string; include missing, empty, ordinary and repeated q cases. Exercise the resulting local search using the real catalog helper or rendered Explore with its existing dependencies isolated.
4. Check /explore?q=coast&q=hills in the local browser: it should show the first query and not crash.

## Acceptance criteria

- Single/missing q preserves current behavior; repeated q selects its first value; empty array safely behaves like empty search.
- No change to the shared catalog, URL persistence, auth or provider requests.
- The new regression fails against the previous production behavior and passes with the bounded fix.
- Existing affected tests still pass; all test/global/timer mocks are restored.
- Only owned files change, with no dependencies, schema, auth or infrastructure modifications.

## Boundaries, risks and error handling

In scope: the specified production fix, its local tests and targeted verification.

Out of scope: broad cleanup, refactoring adjacent components, enabling LIVE or Google login, shared storage algorithms, API/auth changes, database migrations, region/provider configuration, mobile code and deployment. Do not edit `todo.md`, `BUILD_STATUS.md`, shared guardrails or any previous handoff; the root integrates status evidence after review.

Do not broaden this into bidirectional URL synchronization or alter Explore's independent in-page query/category state.

Keep failures recoverable and truthful. Do not swallow errors by pretending success, reset saved content or weaken validators merely to make tests pass. If the required fix expands beyond this boundary, retain the focused reproducer and document the concrete issue for review instead of expanding the task.

## Privacy, accessibility and performance

Use only synthetic plans, destinations and identifiers. No real browser profile exports, credentials, private text logging, network upload or location collection. Render text through React; do not introduce HTML injection. Keep existing privacy disclosures, plain labels, focus behavior and status/error roles.

Use small fixtures, bounded event-driven waits and scoped mock cleanup. No new polling service, automatic server request or performance claim. Preserve existing visual tokens and layout; do not add decorative motion.

## Required tests and manual verification

Implement all scenarios in the approach above. Use Vitest and Testing Library already installed; begin component tests with the existing jsdom environment annotation. Reuse repository fixture patterns. Mock the external boundary, not the production function/component being verified. Fake timers are appropriate for controlled clock/URL cleanup tests, not arbitrary wait-based synchronization.

Visit normal, empty and repeated-query URLs. Confirm search and category controls remain keyboard usable.

Inspect the rendered affected flow at desktop and narrow widths when feasible, including error state and keyboard behavior. Record any browser/screen-reader check not performed as unverified; jsdom does not establish native dialog focus or device accessibility.

## Commands

From repository root:

```powershell
pnpm exec prettier --write apps/web/app/explore/page.tsx apps/web/app/explore/page.test.tsx
pnpm exec prettier --check apps/web/app/explore/page.tsx apps/web/app/explore/page.test.tsx
pnpm exec eslint apps/web/app/explore/page.tsx apps/web/app/explore/page.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/app/explore/page.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

Before integration the root runs `pnpm exec vitest run --maxWorkers=2`, `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm secrets:check` and `pnpm --filter @routiqo/web build` once for the combined production batch. Coordinate build timing with other work; do not disturb a running preview. Do not repeatedly run full builds per task.

## Dependencies, assumptions and delivery

No external credentials, device access or unresolved architectural decision is required. Existing local dependencies are assumed available; do not upgrade/install packages as part of the task. The specific normalization/feedback choices above are the scoped implementation direction, not claims that every detail was already specified elsewhere.

Deliver the production diff, tests, actual check results and remaining manual limitations. Do not commit, push, deploy or mark release gates complete. The user will bring the completed batch back for review.
