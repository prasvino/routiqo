# Claude working method for Routiqo

This replaces the earlier Codex orchestration and workflow guides.
The user's request controls scope. This document does not authorize extra features, deployment, purchases, flag activation or outside communication. Product, architecture, privacy and security requirements stay in their canonical documents (see `CLAUDE.md`).

## 1. Read and scope

1. Read `CLAUDE.md`, `docs/product/ROUTIQO_MASTER_CONTEXT.md` and `docs/development/ENGINEERING_GUARDRAILS.md`. For user-facing work, also read `docs/design/ROUTIQO_UI_UX_SYSTEM.md`.
2. Check `docs/quality/BUILD_STATUS.md`, the relevant `docs/validation/*_PENDING.md` ledger and `docs/validation/IMPLEMENTATION_RESUME.md` (if it exists) for current state and known limits.
3. Inspect the affected code. Load only the feature specs, ADRs, contracts and tests that apply.
4. For substantial work, write concise acceptance criteria and a focused spec (`docs/features/<area>/<NAME>_SPEC.md`) before implementing. The guardrails §23 template lists the sections to consider. Name the data, authorization, privacy, offline and failure boundaries the change touches.
5. For a complex change, an optional short-lived plan can go in `instructions/YYYY-MM-<change>.md`. Delete it, or mark it complete, once the work is committed.
6. Keep planned capabilities separate from implemented behavior.

## 2. Using Claude Code features

- **Plan mode / Plan subagent:** use for cross-domain design, migrations, new LIVE or privacy boundaries, or anything with an ambiguous architecture choice.
- **Explore subagent:** use for broad read-only searches across many modules, so that file dumps stay out of the main context.
- **General-purpose subagents:** use for a bounded implementation or test slice with an exact scope, the affected files, acceptance criteria and the relevant invariants. Keep the agent tree shallow and avoid overlapping edits. Run independent slices in parallel only when their files do not overlap.
- **Worktree isolation:** use it when a delegated slice might conflict with uncommitted work in the main tree.
- **Task list:** use it to track multi-phase work so that each phase's verification is visible.

The root session keeps the architecture, security and privacy judgment, integration and final verification. A subagent reporting success is not verification. Review its diff and rerun its checks.

## 3. Implement in coherent phases

1. Trace existing behavior and define the smallest complete change.
2. Change the implementation, contracts (`contracts/openapi/core.yaml` → `pnpm contracts:generate`), migrations (new Flyway version), tests and docs together.
3. Ship new server and client capabilities default-off. Follow the existing `*Configuration` / exposure-flag pattern.
4. Run focused checks, fix failures, then broaden verification to the required gates.

Do not present scaffolding, fixtures, disabled integrations or an untested UI shell as a production feature. Label synthetic QA fixtures and never commit them.

## 4. Risk and review gates

For authentication, authorization, location, presence, LIVE publication, blocking, moderation, retention or cross-user data, read `docs/security/SECURITY.md`, `docs/security/THREAT_MODEL.md` and `docs/quality/CODE_REVIEW.md` and apply their gates. Critical and High findings block completion. Medium findings need a fix or an explicit disposition.

For privacy-sensitive social or location changes, explicitly check:
- the server-side transformation;
- consent and Ghost Mode;
- endpoint protection;
- expiry;
- anti-enumeration;
- authorization;
- blocking;
- multi-replica correctness.

For journey writes, check durable recovery, idempotency, reconciliation, reconnect behavior and account-switch clearing.

For high-risk changes, run an independent review before completion: a fresh subagent given the final diff and the evidence, `/code-review`, or `/security-review`. Do not tell the reviewer the change is already correct.

## 5. Verification

- TypeScript: `pnpm check` (contracts, format, typecheck, lint, tests) and `pnpm build`. Use `pnpm vitest run <file>` for focused loops.
- Java: `cd backend && ./gradlew check` (PostgreSQL integration tests need Docker). Run a focused `--tests` filter first.
- Secrets: `pnpm secrets:check` before any commit.
- UI: run the app and inspect it with Playwright/Chromium at desktop and narrow widths, with large text and reduced motion. Exercise loading, empty, error, offline and permission states. Use the `routiqo-ui-quality` skill (`.claude/skills/`).
- Android: device and emulator checks need the Android SDK, which the cloud container lacks. Record them as pending in the ledger instead of claiming them.

Report what passed, what failed and what was not run, with the real command results.

## 6. Completion

- Confirm the acceptance criteria. Review the diff for unrelated edits, secrets and precise location data.
- Update ADRs and contracts where required. Update `docs/quality/BUILD_STATUS.md` and the relevant `*_PENDING.md` ledger with actual results. Update `todo.md` only for items that are genuinely closed.
- Claude-specific files (`CLAUDE.md`, this workflow doc, `.claude/`) stay on `feature-claude` and are never pushed or merged to `main` (see the branch policy in `CLAUDE.md`).
- Commit on the designated branch with a clear message. Do not push to other branches, deploy or enable flags unless the user asks.
- If you pause mid-feature, update `docs/validation/IMPLEMENTATION_RESUME.md` with what is done, what was verified, and the exact resume steps.
