# Routiqo

A privacy-first travel and commute app. The runnable web preview provides curated discovery, local trip/commute planning, private journey and journal flows, and default-off private LIVE controls. A separate default-off official-alert pilot shows Chennai district NDMA warnings during an authenticated active journey. It is not a traveller-derived public LIVE release or a production deployment.

## Run on Windows

From `D:\Pras\routiqo`:

```powershell
npx.cmd --yes pnpm@10.34.4 install --frozen-lockfile
npx.cmd --yes pnpm@10.34.4 dev:web
# Separate terminal, optional:
npx.cmd --yes pnpm@10.34.4 dev:mobile
npx.cmd --yes pnpm@10.34.4 infra:up
npx.cmd --yes pnpm@10.34.4 backend:dev
```

Web: http://127.0.0.1:3000. Admin shell: `dev:admin`, port 3001. Core API: port 8080. The backend script selects installed JDK 25 for this project without changing other projects.

## Verification

```powershell
npx.cmd --yes pnpm@10.34.4 check
npx.cmd --yes pnpm@10.34.4 build
npx.cmd --yes pnpm@10.34.4 backend:check
```

`build` produces web/admin production builds and an Android JavaScript export. It does not produce a tested Android APK. CI is configured for TypeScript and Java checks; no remote CI run has occurred.

## Implemented and deferred

- Web Home, Explore, Trips, Profile, privacy page, search, categories, destination details, bookmarks, editable local plans, next departures and JSON backup/restore.
- Expo screens reuse catalog, planning validation, and tokens; SQLite stores local plans.
- Shared lifecycle outbox handles durable pending actions and account partitions. Web dispatch is mounted on Trips; native authenticated transport and reconnect dispatch remain pending.
- Java core/realtime/workers boot applications. Core API has opt-in authenticated journey and private LIVE prerequisites with PostgreSQL/Flyway ownership, retry and concurrency tests. Its default-off provider-alert endpoint is separate from private Quick Signals. Traveller-derived public LIVE publication, realtime messaging and administration workflows remain pending.
- Local PostGIS, Redis, and S3-compatible services; versioned OpenAPI and generated client.
- Google verification, account mapping and revocable sessions plus opt-in browser auth endpoints are tested. Browser transport uses HttpOnly cookies, CSRF/origin checks and database rate limits. Web Google UI exists; real OAuth configuration, staging lifecycle verification and native transport are still pending.

Private trip journal title/notes and browser editing exist, but authenticated device QA, media and sharing remain pending. Real Chennai alert coverage and authenticated staging QA, traveller-derived public LIVE, conversations, push reminders and deployment remain pending. Admin is a restricted foundation shell. Local drafts are not live journeys. Device testing requires Android tooling.

Backend verification requires Docker for disposable PostgreSQL tests. Default API preview remains database-independent; see [database profile setup](docs/development/LOCAL_SETUP.md) for opt-in persistence and protected endpoints. Private LIVE and official-alert endpoints remain default off; no traveller-derived public LIVE publication is enabled.

Start with [CLAUDE.md](CLAUDE.md), [Claude workflow](docs/development/CLAUDE_WORKFLOW.md), [build plan](docs/development/BUILD_PLAN.md), and [verification notes](docs/quality/BUILD_STATUS.md). Source requirements remain unchanged under docs/product, docs/design and docs/development; previous Wayfind references are historical.
