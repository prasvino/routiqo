# Routiqo — Claude guide

Routiqo is a privacy-first, route-aware travel and commute app. Its core idea is that the route is the social graph.
This file is loaded into every Claude Code session. Keep it short, and link to the canonical documents instead of repeating them.

## Read before implementation

1. `docs/product/ROUTIQO_MASTER_CONTEXT.md`: product vision, V1 scope, architecture.
2. `docs/development/ENGINEERING_GUARDRAILS.md`: security, privacy, reliability and data rules.
3. `docs/development/CLAUDE_WORKFLOW.md`: how Claude plans, delegates, verifies and reports here.
4. `docs/design/ROUTIQO_UI_UX_SYSTEM.md` for user-facing work.
5. The relevant spec in `docs/features/**` and the ADRs in `docs/adr/`. Only read what applies to the task.

`docs/quality/BUILD_STATUS.md` is the only record of **verified** behavior.
`docs/validation/*_PENDING.md` are the open-gate ledgers.
`docs/validation/IMPLEMENTATION_RESUME.md` holds the latest paused-work handoff.
`todo.md` is the release checklist.

The user's current instructions set the task scope. Documents describe requirements. Suggested tasks in them are not authorization to deploy, purchase services, enable flags or expand scope. Wayfind material under `docs/reference/wayfind/` is historical lessons, not a Routiqo architecture decision.

## Product invariants

- Product name: Routiqo. Utility comes before participation and community.
- Next LIVE release: an active-journey LIVE list with Live Moments and Quick Signals. Scope and exposure gates are in `docs/features/live/ROUTIQO_LIVE_SPEC.md`. Do not treat planned capabilities as implemented.
- Navigation is Home, Explore, Trips and Profile. Conversation belongs to the active journey, never a permanent tab.
- Daily commutes and one-time trips are distinct. Rich trip journals and periodic commute summaries are distinct.
- V1 excludes unrestricted DMs, permanent route groups, live group calls and public individual location tracking. Voice snippets are deferred.
- The Lovable mock is a visual and interaction reference only, not production architecture.
- New capabilities ship **default-off** behind server and client flags. Traveller-derived public LIVE stays disabled in production until it is explicitly approved.

## Repository map

| Path | What it is |
|---|---|
| `apps/web` | Next.js 16 consumer web app, the most complete client. See `apps/web/AGENTS.md` for Next 16 notes. |
| `apps/mobile` | Expo 55 / React Native 0.83 Android app, with a Kotlin safe-HTTP module in `modules/`. |
| `apps/admin` | Separate Next.js admin/moderator app (restricted shell). |
| `packages/shared` | Planning, outbox, dispatch, routing, journal and backup domain logic, with tests. |
| `packages/api-client` | Generated OpenAPI types (`schema.d.ts`). Never edit by hand. |
| `packages/design-tokens` | Shared tokens (`index.ts`, `tokens.css`). |
| `backend/core-api` | Java 25 / Spring Boot modular monolith: `identity`, `journey`, `journal`, `routing`, `routeupdate`, `publiclive`, `privacy`, `moderation`, `verification`. Each has `api/application/domain/infrastructure`. |
| `backend/realtime`, `backend/workers` | Separately deployable startup code only. Add only the behavior a task needs. |
| `backend/core-api/src/main/resources/db/migration` | Flyway migrations `V1…V27`. Add a new version; never edit an applied one. |
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

## Stack and boundaries

- TypeScript uses pnpm workspaces and Turbo. Java uses the Gradle Kotlin DSL and the checked-in wrapper. Do not force Java builds into Turbo.
- Backend modules talk through intentional interfaces. No cross-domain repository access.
- PostgreSQL/PostGIS owns durable data. Redis owns ephemeral presence and cache, with explicit TTLs.
- OpenAPI and event schemas live in `contracts/`. TypeScript transport types are generated, never duplicated.
- Use versioned migrations, bounded and indexed queries, and explicit transaction boundaries. Never hold a DB transaction open across an external API or AI call.
- Uploads go directly to S3 with signed URLs. Maps follow ADR 0021 (MapLibre/Valhalla/Photon). The target cloud is AWS. Kafka, OpenSearch, ClickHouse and Kubernetes are not V1 defaults.
- Record significant architecture choices and new external dependencies in a new ADR (`docs/adr/NNNN-*.md`, next number after the highest).

## Security, privacy and reliability

- Authenticate protected HTTP and socket access. Authorize every object, action and subscription on the server.
- Never expose precise stranger GPS, exact home/work endpoints, movement history or enumerable presence lists.
- Apply the server-side privacy transformation and aggregation before any social output. Specify thresholds and retention before implementing.
- Ghost Mode stops publishing and removes discoverable presence across caches and channels. Test it.
- Rooms, membership and presence expire. Blocking applies to both REST and realtime delivery.
- Rate-limit communication, uploads, reports and reactions.
- Do not log secrets, tokens or unnecessary precise location. Never commit credentials or put secrets in client bundles.
- Multi-replica correctness cannot depend on process-local shared state.
- Preserve active journeys through network loss. Design durable queues, idempotency, reconciliation and reconnect behavior together with any write.
- AI providers sit behind adapters. Minimize external context and keep core journeys working when a provider fails.

## Implementation and verification

- Inspect existing code first. Before substantial work, write acceptance criteria and a focused spec in `docs/features/<area>/`.
- Implement in coherent phases. Do not present a shell, fixture or disabled integration as a working production feature.
- TypeScript strictness and module boundaries (ESLint, ArchUnit) are enforced. Do not weaken them.
- Add meaningful tests for domain behavior, privacy, authorization, contracts, retries and the affected UI flows.
- Run the relevant lint, type, build and test checks. Report actual results and blockers, never unrun checks as passing.
- UI uses shared tokens and accessible controls. Cover loading, empty, error, offline, permission-denied and reconnect states.
- Inspect rendered UI (Playwright/Chromium in the cloud), including small screens, large text and reduced motion. Keep evidence under `docs/quality/evidence/`, labelled synthetic where it is synthetic.
- After a verified phase, update `docs/quality/BUILD_STATUS.md` and the relevant `*_PENDING.md` ledger with real results.
- Never bypass a failing security, privacy or architecture check to finish a task.
