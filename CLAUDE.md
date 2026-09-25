@AGENTS.md

# Claude Code notes

`AGENTS.md` above is the shared project guide for every coding agent. Its product invariants, stack boundaries, security/privacy rules and verification requirements apply to Claude unchanged.

The **Codex orchestration** section does not apply to Claude. That covers the GPT-6 Astra/Sol/Luna routing and `docs/development/CODEX_ORCHESTRATION.md`. Claude follows `docs/development/CLAUDE_WORKFLOW.md` instead, which keeps the same gates. Wherever a shared doc says "Codex", read it as "the coding agent". Leave the Codex files in place: both toolchains are kept on every branch.

## Current state

- `docs/PRODUCT.md` is the product source of truth (Journey, Spots, Ask Ahead), derived from `docs/direction-brief.md` (2026-09-25). Superseded material is in `docs/archive/` with a note on why; do not implement from it.
- `docs/quality/BUILD_STATUS.md` is the only record of **verified** behavior.
- `docs/validation/*_PENDING.md` are the open-gate ledgers.
- `docs/validation/IMPLEMENTATION_RESUME.md` holds the latest handoff (Phase 1 of the pilot path).
- `todo.md` is the release checklist.
- New capabilities ship default-off. Archived capabilities (private LIVE consent/route preparation, public LIVE, V3 community summaries) keep their code but stay disabled; no stored private report becomes public through a migration.

## Repository map

| Path | What it is |
|---|---|
| `apps/web` | Next.js 16 consumer web app, currently the most complete client; in the pilot it serves route guides and planning. See `apps/web/AGENTS.md` for Next 16 notes. |
| `apps/mobile` | Expo 55 / React Native 0.83 Android app, the **primary pilot client**, with a Kotlin safe-HTTP module in `modules/`. |
| `apps/admin` | Separate Next.js admin/moderator app (restricted shell). |
| `packages/shared` | Planning, outbox, dispatch, routing, journal and backup domain logic, with tests. |
| `packages/api-client` | Generated OpenAPI types (`schema.d.ts`). Never edit by hand. |
| `packages/design-tokens` | Shared tokens (`index.ts`, `tokens.css`). |
| `backend/core-api` | Java 25 / Spring Boot modular monolith: `identity`, `journey`, `journal`, `routing`, `routeupdate`, `publiclive`, `privacy`, `moderation`, `verification`. Each has `api/application/domain/infrastructure`. `publiclive`, `verification` and the V3 summary code are archived capabilities (default-off); `routeupdate` signal storage and `moderation` are reused for Spots. |
| `backend/realtime`, `backend/workers` | Separately deployable startup code only. |
| `backend/core-api/src/main/resources/db/migration` | Versioned Flyway migrations (`V<n>__<name>.sql`). Add the next unused version; never edit an applied one. |
| `contracts/openapi/core.yaml` | The HTTP contract. Regenerate the client after any change. |
| `tests/` | Cross-package Vitest suites (browser/native transport, storage, contracts). |

## Commands

On Linux and in the cloud, use `pnpm` (pinned 10.34.4, Node ≥22.13). On Windows the README uses `npx.cmd --yes pnpm@10.34.4 <script>`.

```sh
pnpm install --frozen-lockfile
pnpm dev:web            # http://127.0.0.1:3000   (dev:admin → 3001, dev:mobile → Expo)
pnpm check              # contracts:check + format:check + typecheck + lint + test
pnpm build              # web/admin production builds + Android JS export
pnpm vitest run tests/native-routing.test.ts   # focused Vitest run
pnpm contracts:generate # after editing contracts/openapi/core.yaml, then pnpm contracts:check
pnpm secrets:check
pnpm infra:up           # PostGIS :5442, Redis, S3-compatible storage (Docker)
(cd backend && ./gradlew check)             # Java tests; PostgreSQL tests need Docker
(cd backend && ./gradlew :core-api:test --tests '*JourneyService*')
```

`pnpm backend:check` and `pnpm backend:dev` call PowerShell (`scripts/backend.ps1`). Outside Windows, call `./gradlew` directly. CI (`.github/workflows/verify.yml`) runs `pnpm check`, `pnpm build`, `pnpm secrets:check` and `gradlew check bootJar`.

## Claude-specific tooling

- The UI review and refinement skill is `.claude/skills/routiqo-ui-quality/SKILL.md`. The Codex copy lives under `.agents/skills/`.
- Cloud sessions have Chromium pre-installed for Playwright. Do not run `playwright install`. There is no Android SDK or emulator, so record device checks as pending. Pilot phase gates need physical Android phones (Phase 1: a full journey on 3+ phones).
