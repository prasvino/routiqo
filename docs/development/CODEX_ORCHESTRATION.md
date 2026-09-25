# Routiqo Codex Orchestration

Use this guide for substantial implementation tasks. GPT-6 Astra plans and orchestrates, GPT-6 Sol implements, and Astra reviews the integrated code. GPT-6 Luna handles super basic, bounded work. Use the available models and delegation only when the active Codex session rules and the user's instructions allow them.

The user's request controls scope. This document does not authorize extra features, deployment, purchases, or external communication. Product, architecture, privacy, and security requirements remain in their canonical documents.

## Read and scope

1. Read `AGENTS.md`, `docs/product/ROUTIQO_MASTER_CONTEXT.md`, and `docs/development/ROUTIQO_CODEX_ENGINEERING_GUARDRAILS.md`. Read the UI system for user-facing work.
2. Inspect the affected code and load only relevant feature specs, ADRs, contracts, and tests. Check `docs/quality/BUILD_STATUS.md` for known verification limits.
3. Write concise acceptance criteria and a focused feature spec before substantial implementation. Identify the data, authorization, privacy, offline, and failure boundaries affected.
4. Keep planned capabilities distinct from implemented behavior. Do not turn historical Wayfind material or old handoffs into current requirements.

## Model responsibilities

- **Astra, root:** understand the request, define scope and acceptance criteria, make architecture and risk decisions, assign bounded implementation, integrate results, review the final code and evidence, and own the final response.
- **Sol, implementation:** trace the affected code, implement the complete scoped change, add meaningful tests, run focused checks, and return the diff summary, test results, and unresolved risks. Sol may perform test and fix loops without a new planning handoff for each correction.
- **Luna, super basic work:** handle mechanical, low-risk tasks with clear inputs and an obvious result, such as finding a file, summarizing a small document, or making a trivial isolated edit. Escalate when requirements, architecture, privacy, security, or behavior are ambiguous. Do not route substantial coding or final review through Luna.

Use normal reasoning effort for clear work. Increase it for ambiguity, cross-domain design, security, location privacy, concurrency, or data migration. Reasoning effort does not replace tests or review.

Work in coherent phases:

1. Trace existing behavior and define the smallest complete change.
2. Change implementation, contracts, migrations, tests, and documentation together where applicable.
3. Run focused checks, fix failures, and broaden verification only for remaining concrete risk or required gates.
4. Astra reviews the final diff, test evidence, and relevant rendered UI; Sol addresses findings and Astra verifies the correction. Report what passed, what failed, and what was not run.

Do not present scaffolding, fixtures, disabled integrations, or an untested UI shell as a production feature.

## Delegation decision

For substantial implementation, use Astra as root and Sol for a bounded implementation task when delegation is available and allowed. Keep the agent tree shallow. If delegation is unavailable, the active agent follows the same planning, implementation, and review gates directly; do not leave the user's task unfinished solely to create a model handoff. The root retains architecture, integration, security/privacy judgment, and final verification.

Give Sol the exact scope, affected boundary, acceptance criteria, relevant invariants, and a concise expected output. Avoid overlapping edits and repeated repository discovery. Astra reviews every result and verifies the integrated change; agent completion is not verification. Use Luna only when the task is genuinely basic and the handoff costs less than doing it directly.

Astra reviews every substantial Sol implementation against the acceptance criteria, changed code, tests, and applicable threat constraints. For high-risk changes, use a separate independent Astra review when available and justified; the root still resolves findings and owns the final decision. Give the reviewer the final diff and evidence without claiming the change is already correct.

## Risk and review gates

For authentication, authorization, location, presence, Live publication, blocking, moderation, retention, or cross-user data, read `docs/security/SECURITY.md`, `docs/security/THREAT_MODEL.md`, and `docs/quality/CODE_REVIEW.md`. Apply their current gates. Critical and High findings block completion; Medium findings need correction or an explicit root disposition.

For privacy-sensitive social and location changes, the root explicitly checks server-side transformation, consent and Ghost Mode, endpoint protection, expiry, anti-enumeration, authorization, blocking, and multi-replica correctness. For journey writes, check durable recovery, idempotency, reconciliation, and reconnect behavior. Add meaningful tests at the affected boundary.

For UI, follow `docs/design/ROUTIQO_UI_UX_SYSTEM.md`; inspect the rendered result, interactions, accessibility, small screens, and relevant empty/error/offline states. Do not skip a failing security, privacy, or architecture check to finish a task.

## Completion

Before finishing, confirm the requested behavior and acceptance criteria, review changed files for unrelated edits, run relevant lint/type/build/tests, update contracts and ADRs when required, and state remaining limitations. Keep secrets and unnecessary precise location out of outputs. Record actual command results and any blocker rather than claiming unrun checks passed.

Default route: **Astra plans and orchestrates → Sol implements and tests → Astra reviews and verifies.** Luna handles only super basic bounded work. Keep handoffs proportional to the task and obey active session rules.
