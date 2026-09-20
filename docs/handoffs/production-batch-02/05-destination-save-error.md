# Show recoverable save errors inside destination dialogs

Status: ready for bounded implementation, **not implemented**. Repository inspection: 2026-09-20, baseline `1b06ad8` with another agent actively editing the prior reliability batch.

## Objective and user value

Users can understand and recover from failed bookmark saves without closing the destination they are viewing.

This is a production improvement **plus required tests**, supporting `todo.md` → journey reliability and quality. It does not complete the authenticated release QA gate.

## Existing behavior and execution flow

DestinationDialog catches and ignores saveDestination failures. The provider's only visible error is Shell's banner outside the native modal, which is behind the dialog's top layer. The open dialog gives no local feedback when saving fails.

## Expected behavior and functional requirements

Keep a local save error and render a plain-text role=alert near the dialog actions using existing form-error styling. Clear the error on a new attempt and successful save; preserve the provider's feedback and storage behavior. Use a fixed safe fallback such as 'This place could not be saved on this device. Try again.' rather than exposing an arbitrary thrown object.

## Exact scope and file ownership

Edit existing production file:

- `apps/web/components/destination-dialog.tsx`

Create the following test file (proposed new file at inspection time):

- `apps/web/components/destination-dialog.test.tsx`

Read-only context:

- `apps/web/components/modal.tsx`
- `apps/web/components/shell.tsx`
- `apps/web/components/planning-provider.tsx`
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

1. Add minimal local state and update the existing save button handler. Do not close the dialog on failure or update saved status optimistically.
2. Render the error inside the dialog; reuse existing classes and accessible semantics. Preserve current button labels, focus and disabled behavior while loading.
3. Add jsdom tests with existing dialog showModal/close stubs and a controlled usePlanning boundary. Throw from saveDestination, assert an alert within the named dialog and retained content/actions.
4. Retry successfully, rerender the fixture with updated saved IDs and assert local error clears and Saved state matches confirmed provider state. Include an unsave failure and verify readiness still prevents actions.

## Acceptance criteria

- Save/unsave failures are visible inside the modal and announced as an alert.
- Failure preserves the dialog and current bookmark state; success clears its error.
- No extra storage writes, navigation or global feedback removal.
- The new regression fails against the previous production behavior and passes with the bounded fix.
- Existing affected tests still pass; all test/global/timer mocks are restored.
- Only owned files change, with no dependencies, schema, auth or infrastructure modifications.

## Boundaries, risks and error handling

In scope: the specified production fix, its local tests and targeted verification.

Out of scope: broad cleanup, refactoring adjacent components, enabling LIVE or Google login, shared storage algorithms, API/auth changes, database migrations, region/provider configuration, mobile code and deployment. Do not edit `todo.md`, `BUILD_STATUS.md`, shared guardrails or any previous handoff; the root integrates status evidence after review.

Task 03 also uses PlanningProvider but this task must only read it, not edit it. A mocked provider must explicitly rerender state on success rather than hide an optimistic-update defect. No shared Modal changes.

Keep failures recoverable and truthful. Do not swallow errors by pretending success, reset saved content or weaken validators merely to make tests pass. If the required fix expands beyond this boundary, retain the focused reproducer and document the concrete issue for review instead of expanding the task.

## Privacy, accessibility and performance

Use only synthetic plans, destinations and identifiers. No real browser profile exports, credentials, private text logging, network upload or location collection. Render text through React; do not introduce HTML injection. Keep existing privacy disclosures, plain labels, focus behavior and status/error roles.

Use small fixtures, bounded event-driven waits and scoped mock cleanup. No new polling service, automatic server request or performance claim. Preserve existing visual tokens and layout; do not add decorative motion.

## Required tests and manual verification

Implement all scenarios in the approach above. Use Vitest and Testing Library already installed; begin component tests with the existing jsdom environment annotation. Reuse repository fixture patterns. Mock the external boundary, not the production function/component being verified. Fake timers are appropriate for controlled clock/URL cleanup tests, not arbitrary wait-based synchronization.

Open a destination from Explore or Profile; simulate denied writes, retry and check keyboard access plus a narrow viewport. Confirm the alert is inside the visible dialog.

Inspect the rendered affected flow at desktop and narrow widths when feasible, including error state and keyboard behavior. Record any browser/screen-reader check not performed as unverified; jsdom does not establish native dialog focus or device accessibility.

## Commands

From repository root:

```powershell
pnpm exec prettier --write apps/web/components/destination-dialog.tsx apps/web/components/destination-dialog.test.tsx
pnpm exec prettier --check apps/web/components/destination-dialog.tsx apps/web/components/destination-dialog.test.tsx
pnpm exec eslint apps/web/components/destination-dialog.tsx apps/web/components/destination-dialog.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/components/destination-dialog.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

Before integration the root runs `pnpm exec vitest run --maxWorkers=2`, `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm secrets:check` and `pnpm --filter @routiqo/web build` once for the combined production batch. Coordinate build timing with other work; do not disturb a running preview. Do not repeatedly run full builds per task.

## Dependencies, assumptions and delivery

No external credentials, device access or unresolved architectural decision is required. Existing local dependencies are assumed available; do not upgrade/install packages as part of the task. The specific normalization/feedback choices above are the scoped implementation direction, not claims that every detail was already specified elsewhere.

Deliver the production diff, tests, actual check results and remaining manual limitations. Do not commit, push, deploy or mark release gates complete. The user will bring the completed batch back for review.
