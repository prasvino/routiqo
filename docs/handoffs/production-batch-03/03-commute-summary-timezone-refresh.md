# Refresh commute month grouping when the browser resumes

Status: ready to implement; **handoff only, not implemented**.
Inspected 2026-09-20 at `54007b6`, with batch 02 actively changing other files.

## Objective and value

Re-resolve the browser time zone on visible-tab resume and window focus. Reuse summarizeCommutes to regroup current confirmed snapshots. Keep no polling timer and do not refetch or write history. An unavailable/invalid zone must use the existing generic unavailable state instead of keeping misleading old results.

This is a small production improvement with regression tests, supporting the local planning and journey UI reliability items in `todo.md`. It does not close authenticated/browser/device release gates.

## Current behavior and architecture

CommuteSummaries resolves the browser time zone only once in a mount effect. If the device time zone changes while the app is backgrounded, the same mounted view retains its previous zone label and month grouping until remount.

Keep UI state in the existing component. Reuse the established provider/helper boundaries. Local plans are not active server journeys, and confirmed commute summaries are partial device-local history, not a complete account report.

## Exact files

Production edit: `apps/web/components/commute-summaries.tsx`.
Extend existing test file: `apps/web/components/commute-summaries.test.tsx`.

Read-only context:

- `packages/shared/src/commute-summaries.ts`
- `packages/shared/src/commute-summaries.test.ts`
- `docs/features/journey/COMMUTE_SUMMARY_SPEC.md`

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

1. Refactor the existing mount-only zone resolution into one local refresh function. Invoke at mount, on visibilitychange when visible and on window focus while visible. Remove both listeners on cleanup.
2. Retain null as initial loading state and the existing invalid-zone/error path. Setting the same zone should not create repeated work; no scheduler/shared clock dependency is needed.
3. Add component tests using the real summarizeCommutes and a narrow resolvedOptions mock that preserves explicit-zone Intl formatting. Use a confirmed commute near a UTC month boundary.
4. Resolve initially as UTC, then change the mocked browser zone to America/Los_Angeles and dispatch resume; assert displayed month/zone change while count and recorded elapsed minutes remain the same. Cover focus, same-zone events, unavailable-zone failure/recovery and listener cleanup.

## Acceptance criteria

- A visible resume reflects the current resolved zone and correctly regroups near-boundary journeys.
- Confirmed counts/elapsed duration do not change merely because the display zone changes.
- No network calls, timers, persisted copies or changed account-validation rules; invalid inputs still fail closed.
- The new regression demonstrates the old behavior before the fix and passes afterwards.
- Existing affected tests remain passing with no ignored assertions or relaxed timeouts.
- Only the two owned files change, and every timer/listener/global mock is cleaned up.

## Scope, safety and error handling

In scope: the described component change and focused regression tests.

Out of scope: auth/session changes, database/schema work, provider configuration, LIVE publication, new dependencies, shared-helper refactors, native changes, persistence format changes and unrelated UI cleanup. Leave `todo.md`, `BUILD_STATUS.md`, both earlier handoff batches and other agents' implementation files unchanged.

Refresh on resume is a scoped improvement derived from the existing browser-zone grouping requirement, not an assertion that an OS emits a dedicated zone-change event. Do not modify shared summary math, the snapshot schema or native code.

Use synthetic data only. No private payload logging, location collection, server requests or new retention. Keep errors plain text rendered by React, preserve authored form values on failure, and never claim a failed write succeeded. Respect account validation and existing fail-closed behavior.

## Tests, accessibility and performance

Implement every scenario described above using the existing Vitest/Testing Library setup. Add the jsdom environment annotation to a new component test. Reuse the native-dialog showModal/close stub pattern from existing component tests rather than adding a package. Mock only boundaries; render the real subject component. Use labelled/role-based queries and bounded event-driven waits.

Using synthetic test fixtures/browser timezone emulation, resume the view after changing zones. Check current zone label, month grouping and partial-history disclosure.

Check desktop and narrow layout, keyboard focus, labels and relevant error states. Record any browser or assistive-technology checks not performed as unverified. Synthetic tests do not prove actual Android or authenticated staging behavior.

No new background loops or unbounded fixtures. Do not claim numerical performance gains without measurement.

## Validation commands

Run from `D:\Pras\routiqo`:

```powershell
pnpm exec prettier --write apps/web/components/commute-summaries.tsx apps/web/components/commute-summaries.test.tsx
pnpm exec prettier --check apps/web/components/commute-summaries.tsx apps/web/components/commute-summaries.test.tsx
pnpm exec eslint apps/web/components/commute-summaries.tsx apps/web/components/commute-summaries.test.tsx --max-warnings 0
pnpm exec vitest run apps/web/components/commute-summaries.test.tsx --maxWorkers=2
pnpm --filter @routiqo/web typecheck
git diff --check
git status --short
```

The root runs the full suite, lint, types, formatting, secret scan and web build once after integration; see README. Coordinate build timing with the active preview and other agents. No backend build or deployment is required for this scope.

## Dependencies and delivery

No credentials, infrastructure, device access or unresolved product decision is needed. Existing dependencies are assumed installed. The precise UI recovery behavior in this handoff is the bounded proposed direction; do not misrepresent it as an already-implemented feature.

If investigation reveals that the task requires a larger redesign, preserve a reproducer and document the reason instead of expanding scope. Report actual tests/checks and manual limitations. Do not stage, commit, push or deploy; the user will bring implementation back for review.
