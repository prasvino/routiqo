# Clear stale success feedback when planning storage fails

Status: ready for bounded implementation, **not implemented**. Repository inspection: 2026-09-20, baseline `1b06ad8` with another agent actively editing the prior reliability batch.

## Objective and user value

Users and assistive technology receive current save status rather than a stale success alongside a failure.

This is a production improvement **plus required tests**, supporting `todo.md` → journey reliability and quality. It does not complete the authenticated release QA gate.

## Existing behavior and execution flow

PlanningProvider sets message after successful updates/clear. Its read, update and clear failure handlers set error without clearing the earlier success message. Shell renders error as an alert and message in a polite live region simultaneously, leaving contradictory status after a failed operation.

## Expected behavior and functional requirements

Clear the obsolete success message when an observed planning read or mutation fails. Keep the last verified state, safe error feedback and existing exception/boolean contracts. A later successful explicit operation should clear the error and announce its own success as before.

## Exact scope and file ownership

Edit existing production file:

- `apps/web/components/planning-provider.tsx`

Create the following test file (proposed new file at inspection time):

- `apps/web/components/planning-provider.test.tsx`

Read-only context:

- `apps/web/components/shell.tsx`
- `apps/web/lib/planning-storage.ts`
- `docs/features/journey/LOCAL_PLANNING_SPEC.md`
- `docs/features/journey/LOCAL_BACKUP_SPEC.md`

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

1. Add setMessage('') to the relevant read/update/clear failure paths; avoid changing storage algorithms, initial readiness, error wording or successful mutations.
2. Create a provider test consumer exposing ready, error, message, current state and action buttons. Use real provider behavior and a controlled Storage boundary.
3. Seed valid data, make a successful operation, then simulate setItem failure: assert no old success remains, error is present, and saved state is unchanged. Recover and assert success replaces the error.
4. Cover read failure delivered through the existing storage listener and removeItem failure in clear after an earlier success. Ensure failures do not reset state or report successful clear. Restore spies/globals.

## Acceptance criteria

- Every covered failure clears previous success feedback while preserving last verified planning state.
- Successful retry gives only current success and clears error; original mutation/clear contracts remain unchanged.
- The new regression fails against the previous production behavior and passes with the bounded fix.
- Existing affected tests still pass; all test/global/timer mocks are restored.
- Only owned files change, with no dependencies, schema, auth or infrastructure modifications.

## Boundaries, risks and error handling

In scope: the specified production fix, its local tests and targeted verification.

Out of scope: broad cleanup, refactoring adjacent components, enabling LIVE or Google login, shared storage algorithms, API/auth changes, database migrations, region/provider configuration, mobile code and deployment. Do not edit `todo.md`, `BUILD_STATUS.md`, shared guardrails or any previous handoff; the root integrates status evidence after review.

Do not edit planning-storage.ts or tests/planning-storage.test.ts: the previous batch owns that boundary. Provider tests may import/read it. No cross-tab conflict protocol, auth or storage schema work.

Keep failures recoverable and truthful. Do not swallow errors by pretending success, reset saved content or weaken validators merely to make tests pass. If the required fix expands beyond this boundary, retain the focused reproducer and document the concrete issue for review instead of expanding the task.

## Privacy, accessibility and performance

Use only synthetic plans, destinations and identifiers. No real browser profile exports, credentials, private text logging, network upload or location collection. Render text through React; do not introduce HTML injection. Keep existing privacy disclosures, plain labels, focus behavior and status/error roles.

Use small fixtures, bounded event-driven waits and scoped mock cleanup. No new polling service, automatic server request or performance claim. Preserve existing visual tokens and layout; do not add decorative motion.

## Required tests and manual verification

Implement all scenarios in the approach above. Use Vitest and Testing Library already installed; begin component tests with the existing jsdom environment annotation. Reuse repository fixture patterns. Mock the external boundary, not the production function/component being verified. Fake timers are appropriate for controlled clock/URL cleanup tests, not arbitrary wait-based synchronization.

After a successful save, simulate denied storage and attempt another save/clear. Confirm error feedback is truthful and earlier success is absent from the status region.

Inspect the rendered affected flow at desktop and narrow widths when feasible, including error state and keyboard behavior. Record any browser/screen-reader check not performed as unverified; jsdom does not establish native dialog focus or device accessibility.

## Commands

From repository root:

```powershell
pnpm exec prettier --write apps/web/components/planning-provider.tsx apps/web/components/planning-provider.test.tsx
pnpm exec prettier --check apps/web/components/planning-provider.tsx apps/web/components/planning-provider.test.tsx
pnpm exec eslint apps/web/components/planning-provider.tsx apps/web/components/planning-provider.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/components/planning-provider.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

Before integration the root runs `pnpm exec vitest run --maxWorkers=2`, `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm secrets:check` and `pnpm --filter @routiqo/web build` once for the combined production batch. Coordinate build timing with other work; do not disturb a running preview. Do not repeatedly run full builds per task.

## Dependencies, assumptions and delivery

No external credentials, device access or unresolved architectural decision is required. Existing local dependencies are assumed available; do not upgrade/install packages as part of the task. The specific normalization/feedback choices above are the scoped implementation direction, not claims that every detail was already specified elsewhere.

Deliver the production diff, tests, actual check results and remaining manual limitations. Do not commit, push, deploy or mark release gates complete. The user will bring the completed batch back for review.
