# Task: journal acknowledgement rollback after write-request success

Prepared 2026-09-20 against HEAD `38fa630`. Repository: `D:\Pras\routiqo`.
Status: handoff only. No new implementation has been performed for this task. All paths are repository-relative.

## Objective, value and TODO source

Add one focused integration regression proving that a journal draft is not discarded and its confirmed cache is not advanced if acknowledgement storage aborts after its real IndexedDB write request succeeds but before commit. Prove that retrying the exact mutation after removing the fault succeeds once.

This is a bounded slice of `todo.md`, First release — journey reliability and quality: “Test delayed responses, duplicate commands, network flaps, interrupted writes and unavailable storage across accounts/devices.” It also supports the existing exact-replay/account-isolation goal. Do not mark those broad TODO items complete.

Value: protect unsent private journal text from a falsely successful local acknowledgement. Unlike a synchronous put exception, this tests the distinction between request success and transaction commit. This path uses a separate journal database and adapter from journey lifecycle storage, so the prior journey acknowledgement test does not cover it.

Unblocked: fixtures, schema, API and library already exist; no production changes, credentials, server, device, infrastructure or decisions are needed.

## Current and expected behavior

`tests/browser-journal-storage.test.ts` already tests stale acknowledgement, mismatched response rollback, successful current acknowledgement, cross-tab draft replacement and a synchronous storage exception during draft save. It does not currently inject a transaction abort after a successful acknowledgement put request.

Expected runtime behavior is already implemented: transaction abort rejects the acknowledgement, leaving the original local draft and confirmed journal intact. An exact retry with the same mutation and valid server response succeeds. Repeating it after the draft was consumed returns false without changing the confirmed journal.

This is test coverage, not a confirmed production bug. If the behavior fails under a valid injector, preserve the reproducer and report the defect. Production repair requires separate review; do not alter the storage design to complete this task.

## Architecture and execution flow

- `apps/web/lib/journal-storage.ts` owns `routiqo-journal-v1` version 1, object store `accounts`, one combined record per account.
- Records hold validated drafts and confirmed journals (at most 20 each, 1 MiB serialized UTF-8). Ordinary logout preserves them; account retirement prevents later resurrection.
- `acknowledgeBrowserJournalDraft(account, journeyId, expectedMutationId, response)` checks the still-current draft. A stale or absent draft returns false.
- A current response must match the journey, title, notes and `draft.expectedVersion + 1`. `mergeJournal` preserves lifecycle/version invariants.
- It atomically stores the confirmed response and removes only that journey's matching draft in one account record.
- The private transaction helper resolves on `tx.oncomplete`, rejects on `tx.onabort`, and closes its database connection in finally. The default abort message is `Journal changes could not be saved.`
- `readBrowserJournal` and `listBrowserJournals` read through new connections. They must observe rolled-back or committed state, never the intermediate replacement.

No network request occurs inside these functions. A synthetic account argument is storage scoping, not proof of authentication.

## Exact files and ownership

**Edit only:** `tests/browser-journal-storage.test.ts`.

Reuse its existing `IDBFactory`, `IDBObjectStore`, fixtures `account`, `otherAccount`, `journeyId`, `firstMutation`, `secondMutation`, and helper functions `id`, `journal`, `draft`. Its afterEach already restores mocks and unstubs globals.

**Read only:**

- `apps/web/lib/journal-storage.ts`: acknowledgement, cache, draft save, reads and transaction helper.
- `packages/shared/src/trip-journal.ts` and `packages/shared/src/trip-journal.test.ts`: validation and journal version semantics.
- `tests/browser-journey-storage.test.ts`: optional example of the recently reviewed asynchronous-abort test. It contains uncommitted work; do not edit, move, extract or commit it.
- `package.json`, `vitest.config.mts`: pinned fake-indexeddb 6.2.5 / Vitest, Node environment and scripts.

The other handoff owns `tests/browser-dispatch.test.ts`; no shared code changes are needed. Do not edit BUILD_STATUS concurrently; return verification evidence to the integrating agent. No general fault-injection utility module is needed for this small local test.

## Functional requirements and implementation steps

1. Check current diff and read the existing journal tests. If equivalent coverage appeared since this handoff, report it instead of duplicating it. Run this file as a baseline.
2. Seed account A with an eligible completed-trip confirmed journal using `cacheBrowserTripJournal(account, journal())`, then save its draft with `saveBrowserJournalDraft(account, draft(), null)`.
3. Also seed a different journey with a confirmed journal and unsent draft in A using existing helper parameters and distinct synthetic mutation ID. This verifies acknowledgement does not clear unrelated authored work. Seed B with a confirmed journal and draft as an isolation sentinel.
4. Capture the target `readBrowserJournal` result and complete `listBrowserJournals` results for A/B before installing fault injection. Create a valid version-1 server response matching the target draft title/notes and its completed journey using `journal(journeyId, 1, local.title, local.notes)`.
5. Save the original `IDBObjectStore.prototype.put`. Install a one-shot spy that calls that original with its original receiver/arguments and returns the actual request. Restrict injection to the journal database (`this.transaction.db.name`), `accounts` store and account A key. Do not fake a request or mock the exported storage functions.
6. Register a success listener on that actual write request. In the success callback, synchronously abort its owning transaction. Track/assert that the real request succeeded and abort fired once. No later timer: it could fire after commit. No synchronous exception from put: that would test the wrong boundary.
7. Call `acknowledgeBrowserJournalDraft` with the valid response and exact current mutation. Assert rejection with `Journal changes could not be saved.` and verify the success/abort counters. Restore the spy in finally before additional storage operations.
8. Read A and B again using public functions. Assert full equality with both original lists and the original target result: old confirmed version/content and exact unsent draft including mutation ID/expectedVersion/title/notes remain. The unrelated A draft and all B state must survive.
9. Retry the same acknowledgement after removing the fault. Assert true; only the target draft disappears, confirmed version becomes 1 with matching content and canonical lifecycle timestamps. Compare against the canonical journey from the pre-abort read rather than raw fixture timestamps. Unrelated A entry and B remain unchanged.
10. Repeat the consumed acknowledgement. Assert false and exact unchanged post-success A/B results. Restore all spies, await all operations, run validation and report evidence.

One comprehensive test is sufficient; two are acceptable if clearer. Use public storage methods for setup/inspection. Keep the real fake-indexeddb transaction lifecycle. Avoid sleeps, fake timers, polling, raw database schema edits and extra test dependencies.

## Technical, security and UX constraints

Retain mutation identity, expected-version checks, canonical timestamp validation, current-draft comparison, bounded caches and retirement. Never auto-rebase a draft or infer a new mutation ID from a retry.

Use synthetic journal titles/notes and UUIDs only. Do not load actual user journals, credentials or locations. Local partition isolation is not authorization testing. Relevant threats: T02 account isolation, T12 private content disclosure, T13 stale/replayed mutation and T19 retention/deletion. The test must not expose content through new logging.

No UI/style change, so no new focus, keyboard, screen-reader, motion or visual behavior to validate. Do not add a demo page or claim rendered/authenticated/device QA. Error semantics remain truthful: a rejected local acknowledgement cannot be treated as proof the server rejected the remote write.

Small deterministic transactions only; no network, background loop or runtime bundle overhead. Restore the prototype spy even on failed expectations. Database connections created by the adapter close in its finally; any extra test-owned handle must also close.

## Scope and reviewed guardrails

In scope: journal acknowledgement asynchronous transaction-abort regression, preservation of target/unrelated drafts, account isolation and exact retry.

Out of scope: journal UI/conflict redesign, server transport retries, database opening/upgrade/eviction tests, real browser crash durability, native journals, new persistence behavior or broad fault-injection infrastructure.

Do not change production files under `apps/` or `packages/`, backend, schemas/migrations, API contracts, auth, feature flags, dependency/lockfiles, Vitest config, UI/tokens or infrastructure. Do not modify the prior uncommitted journey test or the parallel dispatcher test.

Applicable references: `AGENTS.md`; `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`; `docs/development/CODEX_ORCHESTRATION.md` (scoped worker/root integration); `docs/quality/CODE_REVIEW.md`, `TESTING_STRATEGY.md`, `PERFORMANCE.md`; `docs/architecture/FRONTEND_ARCHITECTURE.md`, `OFFLINE_ARCHITECTURE.md`; `docs/features/journey/WEB_JOURNAL_STORAGE_SPEC.md`, `TRIP_JOURNAL_SPEC.md`; `docs/security/SECURITY.md`, `THREAT_MODEL.md`; `docs/privacy/DATA_RETENTION_AND_DELETION.md`; `docs/design/ROUTIQO_UI_UX_SYSTEM.md` (unchanged error/offline and accessibility principles). Read only applicable sections and use BUILD_STATUS for current readiness.

## Verifiable acceptance criteria

- The real target put success event is reached, followed by exactly one transaction abort; a valid acknowledgement rejects rather than returning success.
- Fresh reads show target draft and prior confirmed journal unchanged, including version, mutation and authored text.
- An unrelated draft/journal in A and B's entries remain identical after abort and retry.
- Exact retry returns true, advances only the matching confirmed journal and removes only its draft.
- Repeating the consumed mutation returns false and changes no stored state.
- Existing journal validation, stale-result, capacity, retirement and cross-tab tests pass.
- No production/schema/auth/dependency changes, leaked spies, unhandled rejections, sleep or timeout increases.
- Results are explicitly synthetic IndexedDB verification, not real device/browser crash testing.

## Validation commands

PowerShell at repository root, with existing Node >=22.13.0 <25 and pnpm 10.34.4; no installation/upgrades.

```powershell
Set-Location D:\Pras\routiqo
git status --short
pnpm exec vitest run tests/browser-journal-storage.test.ts --maxWorkers=2
# After editing:
pnpm exec prettier --write tests/browser-journal-storage.test.ts
pnpm exec prettier --check tests/browser-journal-storage.test.ts
pnpm exec eslint tests/browser-journal-storage.test.ts --max-warnings 0
pnpm exec vitest run tests/browser-journal-storage.test.ts packages/shared/src/trip-journal.test.ts --maxWorkers=2
pnpm typecheck
git diff --check
git diff -- tests/browser-journal-storage.test.ts
```

Integrator can run `pnpm secrets:check` and `pnpm exec vitest run --maxWorkers=2` once after both handoffs; `pnpm check` is the broader existing check command. For reference, web build is `pnpm --filter @routiqo/web build`; it and backend/device/rendered checks are not required for this test-only change. Record commands actually run; do not copy prior pass counts as new results.

## Assumptions, risks and completion

No unresolved product decision or external credential is needed. The main risks are an injector that never reaches write success, aborting after commit, or a prototype spy leaking into setup/other tests. Counters, synchronous event abort, scoping and finally cleanup address these risks.

If the correct test exposes an existing production defect, report it with the reproducer rather than changing production or relaxing assertions. Do not claim all interrupted writes are covered by this one boundary.

No commit/push/deployment unless separately requested. Return changed file, exact assertions, commands/results, any failures and remaining limits to the integrating agent. Keep broad TODO items pending.
