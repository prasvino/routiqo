# Routiqo

A privacy-first travel and commute app. The first runnable slice provides curated discovery, bookmarks, and local trip/commute planning.

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
- Shared lifecycle outbox with a native SQLite adapter handles durable pending actions, retry leases and account partitions. It is not connected to a network dispatcher yet.
- Java core/realtime/workers boot applications, public health/catalog, fail-closed protected routes, tested lifecycle and privacy rules. Internal journey persistence uses PostgreSQL/Flyway with ownership, retry and concurrency integration tests.
- Local PostGIS, Redis, and S3-compatible services; versioned OpenAPI and generated client.
- Google verification, account mapping and revocable sessions plus opt-in browser auth endpoints are tested. Browser transport uses HttpOnly cookies, CSRF/origin checks and database rate limits. Login UI, real OAuth configuration and native transport are still pending; phone OTP is deferred.

Authentication, cloud plan sync, live routing/presence, conversations, push reminders, trip journals, provider integrations, and deployment remain deferred. Admin is a restricted foundation shell. Local drafts are not live journeys. Device testing requires Android tooling.

Backend verification requires Docker for disposable PostgreSQL tests. Default API preview remains database-independent; see [database profile setup](docs/development/LOCAL_SETUP.md) to enable internal persistence with external database credentials. No public journey writes are enabled.

Start with [AGENTS.md](AGENTS.md), [build plan](docs/development/BUILD_PLAN.md), and [verification notes](docs/quality/BUILD_STATUS.md). Source requirements remain unchanged under docs/product, docs/design and docs/development; previous Wayfind references are historical.
