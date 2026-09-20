# Keep removal errors scoped to the current confirmation

Status: ready to implement; **handoff only, not implemented**.
Inspected 2026-09-20 at `54007b6`, with batch 02 actively changing other files.

## Objective and value

Clear removal feedback whenever a new confirmation opens or the current confirmation closes, including Keep plan, Escape/close and successful removal. A failed removal must still retain the current confirmation and actionable error.

This is a small production improvement with regression tests, supporting the local planning and journey UI reliability items in `todo.md`. It does not close authenticated/browser/device release gates.

## Current behavior and architecture

Trips stores one error string for its removal dialog. Modal onClose clears it, but Keep plan only clears remove, and successful removal also only clears remove. After a failed deletion followed by Keep plan, opening another plan's confirmation displays the earlier error before any attempt.

Keep UI state in the existing component. Reuse the established provider/helper boundaries. Local plans are not active server journeys, and confirmed commute summaries are partial device-local history, not a complete account report.

## Exact files

Production edit: `apps/web/components/trips.tsx`.
Create proposed new test file: `apps/web/components/trips.test.tsx`.

Read-only context:

- `apps/web/components/modal.tsx`
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

1. Introduce a small local close-removal handler or equivalent shared state reset within Trips, and use it consistently for cancellation and successful completion.
2. Clear the previous error when opening a removal confirmation. Do not change removePlan, persistence, scheduling or the meaning of deletion.
3. Add the new Trips component suite with controlled usePlanning/useLocalClock and a stubbed JourneyWorkspace. Render two valid local plans.
4. Fail removal for plan A, choose Keep plan, open B, and assert no stale alert and no extra removePlan call. Cover failed-then-successful retry followed by reopening, plus explicit dialog close. Assert the current attempted ID and unchanged visible plan data on failure.

## Acceptance criteria

- A new confirmation never starts with a different attempt's error.
- Cancellation performs no deletion; failure keeps the correct dialog open; success closes and clears feedback.
- No storage/provider API changes and no deletion without the existing confirmation action.
- The new regression demonstrates the old behavior before the fix and passes afterwards.
- Existing affected tests remain passing with no ignored assertions or relaxed timeouts.
- Only the two owned files change, and every timer/listener/global mock is cleaned up.

## Scope, safety and error handling

In scope: the described component change and focused regression tests.

Out of scope: auth/session changes, database/schema work, provider configuration, LIVE publication, new dependencies, shared-helper refactors, native changes, persistence format changes and unrelated UI cleanup. Leave `todo.md`, `BUILD_STATUS.md`, both earlier handoff batches and other agents' implementation files unchanged.

Batch 02 owns planning-provider and use-local-clock. Read/mock their public contracts only; do not edit those files or their tests. Keep the shared Modal unchanged.

Use synthetic data only. No private payload logging, location collection, server requests or new retention. Keep errors plain text rendered by React, preserve authored form values on failure, and never claim a failed write succeeded. Respect account validation and existing fail-closed behavior.

## Tests, accessibility and performance

Implement every scenario described above using the existing Vitest/Testing Library setup. Add the jsdom environment annotation to a new component test. Reuse the native-dialog showModal/close stub pattern from existing component tests rather than adding a package. Mock only boundaries; render the real subject component. Use labelled/role-based queries and bounded event-driven waits.

Fail a local removal, cancel through Keep plan, then open another plan. Verify only the current plan is named and no old error remains.

Check desktop and narrow layout, keyboard focus, labels and relevant error states. Record any browser or assistive-technology checks not performed as unverified. Synthetic tests do not prove actual Android or authenticated staging behavior.

No new background loops or unbounded fixtures. Do not claim numerical performance gains without measurement.

## Validation commands

Run from `D:\Pras\routiqo`:

```powershell
pnpm exec prettier --write apps/web/components/trips.tsx apps/web/components/trips.test.tsx
pnpm exec prettier --check apps/web/components/trips.tsx apps/web/components/trips.test.tsx
pnpm exec eslint apps/web/components/trips.tsx apps/web/components/trips.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/components/trips.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

The root runs the full suite, lint, types, formatting, secret scan and web build once after integration; see README. Coordinate build timing with the active preview and other agents. No backend build or deployment is required for this scope.

## Dependencies and delivery

No credentials, infrastructure, device access or unresolved product decision is needed. Existing dependencies are assumed installed. The precise UI recovery behavior in this handoff is the bounded proposed direction; do not misrepresent it as an already-implemented feature.

If investigation reveals that the task requires a larger redesign, preserve a reproducer and document the reason instead of expanding scope. Report actual tests/checks and manual limitations. Do not stage, commit, push or deploy; the user will bring implementation back for review.
