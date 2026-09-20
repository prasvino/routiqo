# Explain and recover from an empty commute weekday selection

Status: ready to implement; **handoff only, not implemented**.
Inspected 2026-09-20 at `54007b6`, with batch 02 actively changing other files.

## Objective and value

When submitting a commute with no selected days, show an actionable inline weekday error, associate it with the existing Repeat on fieldset and move focus to its first weekday button. Do not call savePlan for that submission. Picking a day clears that specific error. Normal storage failures continue to use the existing general error and retain form values.

This is a small production improvement with regression tests, supporting the local planning and journey UI reliability items in `todo.md`. It does not close authenticated/browser/device release gates.

## Current behavior and architecture

PlanDialog allows every Repeat on button to be unselected. It then calls savePlan; the shared validatePlan/upsertPlan path rejects the commute with only 'Please check the journey details.' The user has no explanation that a weekday is required.

Keep UI state in the existing component. Reuse the established provider/helper boundaries. Local plans are not active server journeys, and confirmed commute summaries are partial device-local history, not a complete account report.

## Exact files

Production edit: `apps/web/components/plan-dialog.tsx`.
Create proposed new test file: `apps/web/components/plan-dialog.test.tsx`.

Read-only context:

- `packages/shared/src/planning.ts`
- `docs/features/journey/LOCAL_PLANNING_SPEC.md`

Do not change any other file. Check current status before editing: another agent may have advanced the repository. If the proposed test file already exists, inspect ownership before extending it.

## Applicable conventions and guardrails

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

Read the relevant installed Next.js guide under `apps/web/node_modules/next/dist/docs/` as required by web AGENTS. Apply the project-local `.agents/skills/routiqo-ui-quality/SKILL.md` checks where applicable; no whole-app audit or redesign is requested. Preserve existing tokens, styles, labels, semantic controls, reduced-motion behavior and TypeScript strictness.

## Implementation steps and functional requirements

1. Add minimal field-specific state to PlanDialog. Before constructing/saving the commute, check its existing days selection and set a message such as 'Choose at least one day for this commute.'
2. Use a stable ID (React useId) to connect the inline error to the fieldset with aria-describedby; mark invalid state only while applicable. Retain the existing legend, pressed-state buttons, native form fields and design classes.
3. Focus the first weekday button using a scoped ref on failed submission. Clear the weekday error when a day is chosen or the user switches to trip; do not silently choose a day for them.
4. Add the new component suite using real PlanDialog and the existing dialog stub pattern with a mocked planning boundary. Test all-days deselected, no save call, inline description/focus, correction and successful save, trip with empty days, and storage failure preserving entered values.

## Acceptance criteria

- Empty-day commute submission identifies the exact fix and moves keyboard focus to the day controls without losing form data.
- Correcting a day allows an ordinary single save; trip saves still use days: [].
- Shared validation remains authoritative and unchanged; other validation/storage errors retain existing behavior.
- The new regression demonstrates the old behavior before the fix and passes afterwards.
- Existing affected tests remain passing with no ignored assertions or relaxed timeouts.
- Only the two owned files change, and every timer/listener/global mock is cleaned up.

## Scope, safety and error handling

In scope: the described component change and focused regression tests.

Out of scope: auth/session changes, database/schema work, provider configuration, LIVE publication, new dependencies, shared-helper refactors, native changes, persistence format changes and unrelated UI cleanup. Leave `todo.md`, `BUILD_STATUS.md`, both earlier handoff batches and other agents' implementation files unchanged.

Do not reproduce the full shared plan validator in the component or automatically reset user choices. This adds targeted feedback for an already-established rule, not a new recurrence policy.

Use synthetic data only. No private payload logging, location collection, server requests or new retention. Keep errors plain text rendered by React, preserve authored form values on failure, and never claim a failed write succeeded. Respect account validation and existing fail-closed behavior.

## Tests, accessibility and performance

Implement every scenario described above using the existing Vitest/Testing Library setup. Add the jsdom environment annotation to a new component test. Reuse the native-dialog showModal/close stub pattern from existing component tests rather than adding a package. Mock only boundaries; render the real subject component. Use labelled/role-based queries and bounded event-driven waits.

Create a commute, deselect all weekdays and submit by keyboard. Correct the error, then switch between commute and trip and verify error/focus behavior at a narrow width.

Check desktop and narrow layout, keyboard focus, labels and relevant error states. Record any browser or assistive-technology checks not performed as unverified. Synthetic tests do not prove actual Android or authenticated staging behavior.

No new background loops or unbounded fixtures. Do not claim numerical performance gains without measurement.

## Validation commands

Run from `D:\Pras\routiqo`:

```powershell
pnpm exec prettier --write apps/web/components/plan-dialog.tsx apps/web/components/plan-dialog.test.tsx
pnpm exec prettier --check apps/web/components/plan-dialog.tsx apps/web/components/plan-dialog.test.tsx
pnpm exec eslint apps/web/components/plan-dialog.tsx apps/web/components/plan-dialog.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/components/plan-dialog.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

The root runs the full suite, lint, types, formatting, secret scan and web build once after integration; see README. Coordinate build timing with the active preview and other agents. No backend build or deployment is required for this scope.

## Dependencies and delivery

No credentials, infrastructure, device access or unresolved product decision is needed. Existing dependencies are assumed installed. The precise UI recovery behavior in this handoff is the bounded proposed direction; do not misrepresent it as an already-implemented feature.

If investigation reveals that the task requires a larger redesign, preserve a reproducer and document the reason instead of expanding scope. Report actual tests/checks and manual limitations. Do not stage, commit, push or deploy; the user will bring implementation back for review.
