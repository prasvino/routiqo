# Task: browser journey retry/backoff integration regression

Prepared 2026-09-20 against HEAD `38fa630`. Repository: `D:\Pras\routiqo`.
Status: handoff only; do not assume these new tests already exist. Paths are repository-relative.

## Objective and TODO source

Implement one focused test-only task: exercise a transient journey POST failure through the real browser dispatcher and IndexedDB adapter, then verify persisted backoff, exact command retry and FIFO completion.

Source: `todo.md`, First release — journey reliability and quality: “Test delayed responses, duplicate commands, network flaps, interrupted writes and unavailable storage across accounts/devices.” This covers only synthetic connection-failure recovery; leave the broad TODO unchecked.

Value: catch wiring regressions that drop offline work, retry too early, change the journey identity or send completion before start is acknowledged. It is unblocked and requires no production, schema, auth, infrastructure or dependency changes.

## Current / expected behavior

`tests/browser-dispatch.test.ts` currently covers competing dispatchers, another-account denial, authentication blocks and session-verification failure. Pure shared tests cover transient settlement, but this browser integration file does not exercise journey POST failure followed by persisted-backoff recovery and completion ordering.

Runtime behavior is already intended to be correct. Expected deliverable is regression coverage, not a new retry algorithm or a UI feature. A failed POST returns `deferred`, preserves the command and records a future retry time. Before that time, dispatch returns `idle` without a journey POST. At that time, the same start is retried, acknowledged, and only then may the queued completion run.

## Architecture / actual execution flow

- `apps/web/lib/journey-dispatch.ts`: `dispatchBrowserJourneyOnce(accountId)` builds the browser port and calls shared `dispatchJourneyOnce`.
- `packages/shared/src/journey-dispatch.ts`: verifies account, claims the durable head, rechecks account, sends, then acknowledges or settles. Work is FIFO and only the current lease settles.
- `apps/web/lib/journey-storage.ts`: `updateBrowserJourneyOutbox` serializes mutations in IndexedDB; `acknowledgeBrowserJourney` atomically saves the validated snapshot and removes the head.
- `apps/web/lib/browser-journeys.ts`: `sendBrowserJourney` gets CSRF, POSTs a copied command, applies a 12-second operation deadline and validates the result. Network exceptions yield transient delivery.
- `packages/shared/src/journey-outbox.ts`: `enqueueJourneyCommand`, `claimJourneyCommand`, `settleJourneyCommand` implement bounded FIFO, leases and randomized backoff. Read the recorded `nextAttemptAt` in tests rather than duplicating its formula.
- Browser port uses `Date.now`, fresh `crypto.randomUUID` leases and `Math.random` jitter. Importing the adapter does not schedule work.

Existing HTTP shapes: start POST `/api/v1/journeys`, JSON `{ id, kind }`; completion POST `/api/v1/journeys/<journeyId>/complete`, JSON `{}`. Both include `X-Routiqo-Account`, same-origin cookies, no-store, redirect refusal and CSRF. Session and CSRF fetches are separate from journey POSTs. This test must preserve, not alter, those boundaries.

## Files and ownership

**Edit only:** `tests/browser-dispatch.test.ts`.

**Read only:** the four production files above, `packages/shared/src/journey-snapshots.ts`, `tests/browser-journeys.test.ts`, `packages/shared/src/journey-dispatch.test.ts`, `packages/shared/src/journey-outbox.test.ts`, `package.json`, `vitest.config.mts`.

Reuse the test file's existing synthetic `accountId`/`journeyId`, fresh `IDBFactory`, global fetch stubs and public storage functions. Its beforeEach already queues a start. Its afterEach currently unstubs globals; ensure any new Date spy is restored in finally or add `vi.restoreAllMocks()` to cleanup in this same test file.

Do not edit `tests/browser-journey-storage.test.ts`: it has unrelated reviewed but uncommitted work. The separate journal task owns a different file. No shared helper extraction and no shared BUILD_STATUS edit from parallel workers; report evidence to the integrating agent.

## Requirements and implementation steps

1. Read current status/diff and run this test file as a baseline. Preserve unrelated uncommitted work.
2. In one focused integration test (split only for clarity), append a completion behind the already queued start using `updateBrowserJourneyOutbox` and `enqueueJourneyCommand`. Seed a second synthetic account's outbox as an isolation sentinel; snapshot it before dispatch.
3. Control only `Date.now` with a mutable, valid fixed millisecond value. Keep real timers for IndexedDB and transport; do not call `vi.useFakeTimers`, sleep or wait for real backoff. Change the test clock only between fully settled operations, never during an in-flight 12-second transport deadline.
4. Stub only global `fetch`. Return synthetic account/CSRF responses using existing fixture patterns. Reject the first **journey start POST** with a synthetic network error; do not fail authentication. Later POSTs return valid active/completed Journey responses with matching IDs/kind and consistent start/completion timestamps. Unknown URLs should fail the test instead of returning permissive success.
5. Dispatch once. Assert `deferred`; read fresh persisted state. Both commands remain in order, the head has one attempt, no lease and no block, retry time is greater than the controlled current time, and no journey snapshot was acknowledged. Second-account state is unchanged.
6. Set time to the persisted retry time minus one. Dispatch again and assert `idle`, deep-equal partition, and no additional journey POST or completion. A session lookup may occur; do not incorrectly assert zero total fetch calls.
7. Set time to the persisted retry time. Dispatch once, returning a valid start response. Assert `acknowledged`, a normalized active snapshot and only the original completion remaining. Compare the first failed start POST and its retry: same path, parsed body ID/kind and account header. No regenerated journey ID or extra start command.
8. Dispatch once more with a valid completion response. Assert completion POST occurs only after start acknowledgement, its body is `{}`, the queue is empty and the snapshot is completed with canonical timestamps. A final dispatch is idle and creates no new journey POST. Account B remains unchanged throughout.
9. Restore all global stubs/spies on every path; await all operations. Run validation and report actual results.

Do not mock `dispatchJourneyOnce`, storage mutations, command settlement or the browser transport module. The point is integration across these boundaries. Use public reads through new connections to inspect durable state.

## Security, privacy, UI and performance

Synthetic UUIDs and synthetic CSRF fixture strings only; no credentials, user locations or production data. Account B verifies local partition isolation, not real OAuth/server authorization. Header assertions must not imply the header authenticates a user.

No UI/styles/accessibility behavior changes. Preserve the truthful distinction between queued and confirmed work; do not add a screen or claim rendered/device QA. No new timers, polling, unbounded loops or dependencies. Assertions should target persisted outcomes, identity and ordering, not exact incidental session-call counts or randomized delay values.

## Scope and guardrails

In scope: this browser POST-failure/backoff/FIFO integration regression only.
Out of scope: automatic reconnect event UI, browser offline emulation, lost-response server simulation, lease-expiry redesign, native dispatch, real account testing, authentication changes and production fixes.

Do not modify any `apps/`, `packages/`, backend, migration, contract, dependency, config, UI or infrastructure file. If a test exposes a real production defect, retain the reproducible test and report it for separate review. Do not silently expand scope or relax an assertion/timeout.

Applicable repository sources: `AGENTS.md`; `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md` (offline, performance, tests); `docs/development/CODEX_ORCHESTRATION.md` (one scoped worker, root integration); `docs/quality/CODE_REVIEW.md`, `TESTING_STRATEGY.md`, `PERFORMANCE.md`; `docs/architecture/FRONTEND_ARCHITECTURE.md`, `OFFLINE_ARCHITECTURE.md`; `docs/features/journey/DISPATCH_SPEC.md`, `OUTBOX_SPEC.md`, `WEB_JOURNEY_STORAGE_SPEC.md`, `BROWSER_JOURNEY_TRANSPORT_SPEC.md`; `docs/adr/0006-journey-outbox.md`, `0012-journey-transport-and-local-acknowledgement.md`; `docs/security/SECURITY.md`, `THREAT_MODEL.md` (T02/T12/T13); and `docs/design/ROUTIQO_UI_UX_SYSTEM.md` for unchanged error/offline and accessibility principles. Prefer current BUILD_STATUS over historical integration-status sentences in old specs.

## Acceptance / required tests

- Actual adapter chain returns deferred → idle before due → acknowledged start → acknowledged completion → idle.
- Failed and premature attempts retain pending work; completion cannot overtake the start.
- Retry reuses exact start identity/body and expected account header.
- Snapshots change only on validated acknowledgements; final queue is empty.
- A separate account is unchanged.
- No sleep, fake IndexedDB timers, production edits, leaked spies or increased test timeout.
- Existing targeted tests pass. This is synthetic transport/storage testing, not physical device or authenticated release verification.

## Commands

PowerShell, from repository root; existing Node >=22.13.0 <25 and pnpm 10.34.4. No installs/upgrades.

```powershell
Set-Location D:\Pras\routiqo
git status --short
pnpm exec vitest run tests/browser-dispatch.test.ts --maxWorkers=2
# After editing:
pnpm exec prettier --write tests/browser-dispatch.test.ts
pnpm exec prettier --check tests/browser-dispatch.test.ts
pnpm exec eslint tests/browser-dispatch.test.ts --max-warnings 0
pnpm exec vitest run tests/browser-dispatch.test.ts tests/browser-journeys.test.ts packages/shared/src/journey-dispatch.test.ts packages/shared/src/journey-outbox.test.ts --maxWorkers=2
pnpm typecheck
git diff --check
git diff -- tests/browser-dispatch.test.ts
```

Integrator may run `pnpm secrets:check` and `pnpm exec vitest run --maxWorkers=2` once after both handoffs, avoiding duplicated broad runs. `pnpm check` is the existing all-in-one validation. Web build command is `pnpm --filter @routiqo/web build`, but build/backend/device/UI checks are not required for this test-only diff. Never claim checks that were not run.

## Dependencies, risks and completion

No unresolved product decisions or external dependencies. Risks: failing the wrong HTTP request; asserting no fetch when an account preflight is valid; moving the clock during an active deadline; leaving a Date spy installed. The steps above avoid these false results.

No commit/push/deployment unless separately requested. Report changed file, exact recovery sequence, commands/results and limitations. Do not mark the broad TODO complete. Root reviews the assertions and final diff before integration.
