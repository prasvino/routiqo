# Refresh departure labels at actual minute boundaries

Status: ready for bounded implementation, **not implemented**. Repository inspection: 2026-09-20, baseline `1b06ad8` with another agent actively editing the prior reliability batch.

## Objective and user value

Upcoming/earlier departure labels track the existing minute-based planning rules with less stale UI.

This is a production improvement **plus required tests**, supporting `todo.md` → journey reliability and quality. It does not complete the authenticated release QA gate.

## Existing behavior and execution flow

useLocalClock initializes once and refreshes with a 60-second interval measured from mount time. A page mounted at 12:00:45 does not refresh again until 12:01:45. Scheduling intentionally treats the current departure minute as upcoming only until that minute ends.

## Expected behavior and functional requirements

Replace the drifting interval with one rescheduled timeout aligned to the next wall-clock minute. Refresh immediately on visibility resume and recompute the next boundary. Suspend the scheduled callback while hidden. Keep the hook's Date | null contract and all scheduling rules unchanged.

## Exact scope and file ownership

Edit existing production file:

- `apps/web/components/use-local-clock.ts`

Create the following test file (proposed new file at inspection time):

- `apps/web/components/use-local-clock.test.tsx`

Read-only context:

- `apps/web/components/next-plan.tsx`
- `apps/web/components/trips.tsx`
- `docs/features/journey/UPCOMING_PLANS_SPEC.md`

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

1. Keep the initial null render so server/client hydration behavior remains stable.
2. Within the existing effect, maintain at most one timeout. Calculate its next delay from the current clock each time (60,000 minus the current millisecond remainder); refresh and rearm on each visible tick.
3. On visibilitychange clear the existing timer. On visible state refresh immediately and rearm; hidden state does not create another timeout. Cleanup removes the listener and timer.
4. Use a small hook test harness, fake time and a controlled visibilityState getter. Verify a mount at :45 refreshes at :00, no extra visible ticks, hidden suspension, immediate resume, wall-clock jump recalculation and unmount cleanup. Include StrictMode so replay does not leave duplicate timers.

## Acceptance criteria

- A plan's displayed status can refresh at the minute boundary instead of up to 59 seconds late.
- At most one timer exists per mounted hook; no hidden timer remains; resume derives fresh time.
- The hook causes no network calls or storage writes and never starts/completes a journey.
- The new regression fails against the previous production behavior and passes with the bounded fix.
- Existing affected tests still pass; all test/global/timer mocks are restored.
- Only owned files change, with no dependencies, schema, auth or infrastructure modifications.

## Boundaries, risks and error handling

In scope: the specified production fix, its local tests and targeted verification.

Out of scope: broad cleanup, refactoring adjacent components, enabling LIVE or Google login, shared storage algorithms, API/auth changes, database migrations, region/provider configuration, mobile code and deployment. Do not edit `todo.md`, `BUILD_STATUS.md`, shared guardrails or any previous handoff; the root integrates status evidence after review.

Browser throttling still prevents exact real-time guarantees. Do not promise alarms, background execution or reminders. Do not change shared scheduling helpers or mobile code.

Keep failures recoverable and truthful. Do not swallow errors by pretending success, reset saved content or weaken validators merely to make tests pass. If the required fix expands beyond this boundary, retain the focused reproducer and document the concrete issue for review instead of expanding the task.

## Privacy, accessibility and performance

Use only synthetic plans, destinations and identifiers. No real browser profile exports, credentials, private text logging, network upload or location collection. Render text through React; do not introduce HTML injection. Keep existing privacy disclosures, plain labels, focus behavior and status/error roles.

Use small fixtures, bounded event-driven waits and scoped mock cleanup. No new polling service, automatic server request or performance claim. Preserve existing visual tokens and layout; do not add decorative motion.

## Required tests and manual verification

Implement all scenarios in the approach above. Use Vitest and Testing Library already installed; begin component tests with the existing jsdom environment annotation. Reuse repository fixture patterns. Mock the external boundary, not the production function/component being verified. Fake timers are appropriate for controlled clock/URL cleanup tests, not arbitrary wait-based synchronization.

Check Home and Trips near a departure boundary, then hide/resume the tab. Verify no automatic plan mutation.

Inspect the rendered affected flow at desktop and narrow widths when feasible, including error state and keyboard behavior. Record any browser/screen-reader check not performed as unverified; jsdom does not establish native dialog focus or device accessibility.

## Commands

From repository root:

```powershell
pnpm exec prettier --write apps/web/components/use-local-clock.ts apps/web/components/use-local-clock.test.tsx
pnpm exec prettier --check apps/web/components/use-local-clock.ts apps/web/components/use-local-clock.test.tsx
pnpm exec eslint apps/web/components/use-local-clock.ts apps/web/components/use-local-clock.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/components/use-local-clock.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

Before integration the root runs `pnpm exec vitest run --maxWorkers=2`, `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm secrets:check` and `pnpm --filter @routiqo/web build` once for the combined production batch. Coordinate build timing with other work; do not disturb a running preview. Do not repeatedly run full builds per task.

## Dependencies, assumptions and delivery

No external credentials, device access or unresolved architectural decision is required. Existing local dependencies are assumed available; do not upgrade/install packages as part of the task. The specific normalization/feedback choices above are the scoped implementation direction, not claims that every detail was already specified elsewhere.

Deliver the production diff, tests, actual check results and remaining manual limitations. Do not commit, push, deploy or mark release gates complete. The user will bring the completed batch back for review.
