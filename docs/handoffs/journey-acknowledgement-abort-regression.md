# Handoff: verify journey acknowledgement rollback after an asynchronous IndexedDB abort

Prepared: 2026-09-20. Inspected repository baseline: `38fa630`.
Repository: `D:\Pras\routiqo`. All paths below are relative to that root.
Status: implementation handoff only. No test or application implementation was made while preparing this file.

## 1. Objective and selection

Add deterministic integration regression coverage proving that a journey acknowledgement does not become successful until its IndexedDB transaction commits. Abort the transaction after its real `put` request succeeds, then prove that both queued work and cached snapshots survive and the same acknowledgement can be retried successfully.

This is exactly one bounded testing task, drawn from the incomplete `todo.md` item under **First release — journey reliability and quality**:

> Test delayed responses, duplicate commands, network flaps, interrupted writes and unavailable storage across accounts/devices.

Only the browser interrupted-write/account-isolation portion is selected. Do not mark that entire checklist item complete. Device, network-flap and other broader verification remains pending.

Value: protects against silently losing a pending start/finish when storage fails after a write request appears successful. This is a commit-boundary regression test, not a cosmetic cleanup or a test that merely duplicates pure queue logic.

Why selected: the adapter, fixtures and development dependency already exist; the documented atomicity rule is settled. No credentials, server, device, migration, auth change, infrastructure or product decision is required. Most LIVE/publication/reporting candidates have unresolved authority/privacy gates; real OAuth/provider/device/operations candidates require external setup.

## 2. Current and expected behavior

Current production behavior is already intended to be correct:

- `apps/web/lib/journey-storage.ts` resolves its private `transaction` helper only from `tx.oncomplete` and rejects from `tx.onabort`.
- `acknowledgeBrowserJourney` checks the current head lease, computes a validated snapshot and successful queue settlement, then writes one combined account record.
- `tests/browser-journey-storage.test.ts` tests invalid acknowledgement data and a **synchronously throwing** `IDBObjectStore.prototype.put` spy in `preserves both parts after failed writes or invalid responses`.
- It also tests successful acknowledgement across new connections, stale acknowledgement and account isolation. Inspection found no test aborting the actual transaction from the successful write request's event, before transaction completion.

Expected deliverable: test coverage of that missing asynchronous abort boundary, without changing runtime behavior. A successful request event must not be treated as a committed acknowledgement. After abort, the operation rejects and rereading through public storage functions returns the full original partition. A subsequent unmodified retry with the still-current lease succeeds exactly once.

This is a coverage gap, not a confirmed production bug. If the new test exposes a real adapter defect, preserve the reproducer and report the exact failure; do not expand into a production refactor or weaken the test to finish this handoff.

## 3. Applicable guardrails and architecture

Use progressive disclosure; do not reread unrelated backend/social subsystems. Reviewed sources for this handoff:

- `AGENTS.md`: account isolation, durable work, meaningful tests, no weakening checks.
- `docs/product/ROUTIQO_MASTER_CONTEXT.md`: journey-first utility, offline reliability, separation of commutes/trips and privacy.
- `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`: offline/idempotency, bounded work, error visibility, frontend and failure tests.
- `docs/development/CODEX_ORCHESTRATION.md`: narrow scoped worker execution, proportional validation, root-owned final review; no extra agents merely to fill roles. A Sol high worker can implement this handoff; root reviews its final diff and evidence. No architecture delegation is needed.
- `docs/development/CODEX_WORKFLOW.md` and `docs/quality/CODE_REVIEW.md`: focused scope, substantive assertions, review actual results and limitations.
- `docs/architecture/ARCHITECTURE.md`, `FRONTEND_ARCHITECTURE.md`, `OFFLINE_ARCHITECTURE.md`, `DATA_ARCHITECTURE.md`: shared pure helpers; platform-specific persistence; local plans, durable journey writes and ephemeral LIVE are distinct.
- `docs/features/journey/WEB_JOURNEY_STORAGE_SPEC.md` and `LOCAL_JOURNEY_SNAPSHOTS_SPEC.md`: atomic account record, rollback, current-lease acknowledgement, canonical timestamps, retirement and bounded history.
- `docs/adr/0006-journey-outbox.md` and `0012-journey-transport-and-local-acknowledgement.md`: stable command identities, transactions, no network inside persistence, pinned fake-indexeddb for synthetic integration tests. Their early integration-status notes are historical; current status is in BUILD_STATUS.
- `docs/security/SECURITY.md`, `THREAT_MODEL.md`, and `docs/privacy/DATA_RETENTION_AND_DELETION.md`: preserve owner partitions and deletion rules; synthetic data; no secrets/location logging. Relevant threats are T02 account isolation, T12 disclosure, T13 replay/races and T19 retained/deleted state.
- `docs/quality/TESTING_STRATEGY.md` and `PERFORMANCE.md`: real behavior at the selected boundary, bounded deterministic tests, no unmeasured device claims.
- `docs/design/ROUTIQO_UI_UX_SYSTEM.md`: truthful error/offline states, accessibility and existing design tokens remain unchanged. No rendered surface is modified by this task; UI redesign, skills installation and visual QA are not part of this test-only scope.

Execution flow to preserve:

1. Caller supplies explicit account, lease, server response and time to `acknowledgeBrowserJourney`.
2. `transaction` validates account shape, opens `routiqo-journeys-v1` version 1, and starts a strict `readwrite` transaction on `accounts`.
3. `store.get(account)` reads the combined account record. Retired accounts are rejected before mutation.
4. The callback reads/validates outbox and snapshots. Only the current head's lease may acknowledge; a stale lease returns false.
5. `recordJourneyResult` validates the server result; `settleJourneyCommand(..., 'success', ...)` removes only that head.
6. `stored` serializes and validates both structures, and `store.put` queues their combined replacement under the account key.
7. Only transaction completion resolves the public operation. Abort rejects; `finally` closes the database connection. Later public reads open new connections.

Do not replace this path with mocked storage functions. There are no HTTP requests in it, and the supplied account is not evidence of authentication.

## 4. Exact files and symbols

| File | Role / allowed treatment |
|---|---|
| `tests/browser-journey-storage.test.ts` | Primary implementation file. Reuse `IDBFactory`, `IDBObjectStore`, `pending(owner)`, synthetic `account`/`other`/`id`/`lease`/`response`, and existing beforeEach/afterEach cleanup. Add focused tests here. |
| `apps/web/lib/journey-storage.ts` | Read only: `transaction`, `stored`, `acknowledgeBrowserJourney`, `readBrowserJourneyPartition`, `mergeBrowserJourneyHistory`, `updateBrowserJourneyOutbox`. |
| `packages/shared/src/journey-outbox.ts` | Read only: claim/settle and current-lease semantics. |
| `packages/shared/src/journey-snapshots.ts` | Read only: canonical response validation and strict acknowledgement lifecycle. |
| `tests/browser-dispatch.test.ts` | Adjacent regression suite; do not modify the dispatcher or its tests for this task. |
| `tests/journey-restoration.test.ts` | Adjacent regression suite; preserves latest delayed-history fixes. |
| `package.json`, `vitest.config.mts` | Read only: existing scripts, Node test environment, test discovery and fake-indexeddb 6.2.5 dependency. |
| `docs/quality/BUILD_STATUS.md` | Optional small, dated verification note after successful implementation; report synthetic limits and actual commands. Do not rewrite historical evidence. |

Default diff: the existing storage test file only, plus an optional BUILD_STATUS note. This handoff can be annotated with final results, but no new general-purpose test framework/helper module is needed.

## 5. Requirements and implementation approach

1. Recheck `git status --short`, HEAD and the relevant tests before editing. Preserve unrelated work. If equivalent coverage has since landed, report that instead of duplicating it.
2. Run the existing storage test file as a baseline.
3. Prepare account A with a valid currently leased start command using `pending`; include a nonempty prior snapshot for a different synthetic completed journey using the existing history/storage helpers. Prepare account B with its own pending work. Capture both complete partitions through `readBrowserJourneyPartition` before installing the fault injection.
4. Install a narrowly scoped `IDBObjectStore.prototype.put` spy that calls the captured original implementation with the original receiver/arguments and returns its actual request. Filter to the `accounts` store and target account key, and fire once only. In that request's success listener, synchronously call the owning transaction's `abort()` before it completes. Keep the real fake-indexeddb transaction machinery; do not fabricate success/error events or stub the acknowledgement result.
5. Track/assert that the real write request reached its success callback and that the abort was actually triggered exactly once. This prevents a passing test caused by an earlier validation failure or a spy that never ran.
6. Await a rejection from `acknowledgeBrowserJourney`, using a valid response and a valid explicit time while the existing lease remains current. The expected fallback message is `Journey changes could not be saved.`; do not assert provider details or expose private payloads.
7. Restore the spy in `finally` before any later storage calls. Reuse the suite's existing `vi.restoreAllMocks`/`vi.unstubAllGlobals` cleanup as additional protection. Close any explicit test-owned database handle if one is introduced; prefer public functions, which already close their handles.
8. Read both partitions again. Assert deep equality against both pre-abort snapshots: same queue head/lease/attempts, following work if present, and same full snapshot contents. No partial acknowledgement may persist; B must be untouched.
9. Retry the exact acknowledgement with the same current lease after removing the injected abort. Assert true, expected canonical saved journey and head removal. Assert the preexisting unrelated completed snapshot and B's partition are preserved. A repeated acknowledgement with that consumed lease must return false and leave state unchanged.
10. Review the test for false positives, real transaction rollback, isolation and leak-free cleanup. Run the commands below. Record only verification actually performed.

One or two tests can cover this sequence; do not create separate tasks for each assertion. No timers, sleeps, fake-clock advancement, real network, real accounts or device setup are needed. Do not increase suite timeouts or change global test concurrency/configuration to make the new test pass.

## 6. Boundaries and considerations

**In scope:** regression tests of the existing browser acknowledgement transaction after write-request success but before commit, read-after-abort, same-lease retry, and unaffected second-account state.

**Out of scope:** implementing new persistence/recovery behavior; simulating an actual browser crash/power loss; process restart/device durability; open/upgrade blocking; storage eviction; network flaps; native SQLite; real sign-in; publication/reporting; journal behavior; general testing infrastructure.

**Do not change:** any `apps/` or `packages/` production file, backend, migrations, contracts/generated models, authentication/authorization, LIVE flags, retention limits, IndexedDB schema, package manifests/lockfiles, Vitest configuration, UI/styles/design tokens or deployment configuration. Do not check off the parent TODO or claim all offline reliability is complete. Do not commit/push or deploy unless separately requested by the task owner.

Security/privacy: use the existing synthetic fixtures only. Account B asserts storage partition independence, not server authorization. No credentials, endpoints, precise location, user notes or production data. Keep retirement, stale-lease, validation and canonical timestamp assertions intact.

Accessibility/UI: no UI output changes, therefore no new focus, screen-reader, layout or motion behavior. Do not mount a fixture screen or relax existing error copy. Actual authenticated/device accessibility remains a release gate.

Performance: a small number of deterministic IndexedDB transactions; no polling or wall-clock sleeps. Reuse the pinned development-only library. No bundle/runtime overhead or dependency changes.

Error handling: a write request succeeding does not mean its transaction committed. The Promise must reject on the injected abort; retry occurs only after fault cleanup. Assert externally visible stored state, not just internal callback counts. Never swallow a rejection and treat it as successful recovery.

## 7. Verifiable acceptance criteria

- The injected fault occurs exactly once after the real target `put` request succeeds and before its transaction commits.
- A valid acknowledgement rejects for that abort; it does not resolve true or falsely remove work.
- Fresh public reads show account A's complete outbox and snapshots unchanged, including its original lease and unrelated confirmed history.
- Account B's complete partition is unchanged after both the abort and A's retry.
- Removing the fault and retrying the same still-current lease succeeds, saves the normalized server result and consumes only the expected head. Repeating the consumed lease returns false without changing data.
- Existing retirement, stale lease, invalid result, delayed history, pruning, dispatch and account-isolation tests continue passing.
- No production code, dependency, schema, auth, infrastructure or UI change is included. No unhandled rejection, leaked spy or hung connection remains.
- The result is described as synthetic transaction-abort coverage, not real browser/device crash-durability verification.

## 8. Validation commands

Run PowerShell from `D:\Pras\routiqo`. Use the installed repository toolchain: Node `>=22.13.0 <25`, pnpm 10.34.4. No installation/upgrades are needed for this task.

Required baseline and focused validation:

```powershell
Set-Location D:\Pras\routiqo
git status --short
pnpm exec vitest run tests/browser-journey-storage.test.ts --maxWorkers=2
# After editing:
pnpm exec prettier --write tests/browser-journey-storage.test.ts
pnpm exec prettier --check tests/browser-journey-storage.test.ts
pnpm exec eslint tests/browser-journey-storage.test.ts --max-warnings 0
pnpm exec vitest run tests/browser-journey-storage.test.ts tests/browser-dispatch.test.ts tests/journey-restoration.test.ts packages/shared/src/journey-snapshots.test.ts --maxWorkers=2
pnpm typecheck
pnpm secrets:check
git diff --check
git diff --stat
git diff -- tests/browser-journey-storage.test.ts docs/quality/BUILD_STATUS.md
```

Optional integration validation if requested by the reviewer or a new failure justifies it:

```powershell
pnpm check
# If default parallel testing suffers unrelated contention:
pnpm exec vitest run --maxWorkers=2
```

BUILD_STATUS records 488 tests/52 files passing at the inspected baseline with two workers, after a default-concurrency Xcode dependency test timeout. Do not mask failures or increase assertion timeouts; report command/result distinctions accurately.

Build command for reference: `pnpm --filter @routiqo/web build`. A production web build, backend suite, Android export and rendered UI run are **not required for this test-only diff**. If a reviewer requests a web build, coordinate with any running Next dev server first because both use the same output directory; do not stop unrelated processes. No build claim should be made unless it was actually run.

## 9. Assumptions, risks and completion report

- Assumption: current intended behavior remains `tx.oncomplete` success / `tx.onabort` rejection, and the installed fake-indexeddb emits real request events. Verify through the test; do not implement a replacement database simulator.
- Main test risk: aborting too early merely repeats the existing synchronous failure test; aborting via a later timer can miss the commit. Abort synchronously inside the real request success event and assert that path ran.
- Main isolation risk: a prototype spy can affect fixture setup or subsequent tests. Install it after setup, scope it to A/store, and restore in finally.
- No unresolved product or architecture question blocks the chosen coverage task. A newly discovered production defect is a separate decision, not authorization to redesign the adapter.
- Synthetic rollback/reopen checks do not prove physical disk durability, browser eviction behavior, Google authentication or real-device recovery.

At completion report changed files, the exact abort boundary tested, command/results, any failures and scope limitations. Keep the broader TODO pending. Root review must confirm assertions would catch premature success and partial/lost acknowledgement, rather than approving from a green test count alone.
