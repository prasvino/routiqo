# Routiqo

Routiqo tells you what your journey is like right now, from people who were just there: how bad the toll queue is, which highway eatery is good today, whether the bus is crowded. It is a live, human layer on top of the journey, built around three ideas — **Journey**, **Spots** and **Ask Ahead** — and it runs on what people choose to post or tap, not on tracking their movements. See [docs/PRODUCT.md](docs/PRODUCT.md).

A reduced-scope dry run with early testers is planned for Diwali 2026, and the public pilot for the Pongal 2027 exodus from Chennai along GST Road and its southern branches. Android is the primary client; web serves route guides and planning. Spots, posts, voice notes and Ask Ahead are **not built yet**.

Today the repository contains a runnable web preview (curated discovery, local trip/commute planning, private journey and journal flows), an Android app with journeys, planning, explicit route planning, history and journals (emulator-verified; physical-device checks pending), and default-off backend foundations that the Spots model reuses. A separate default-off official-alert pilot shows Chennai district NDMA warnings during an authenticated active journey. It is not a production deployment.

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

- Web Home, Explore (curated; demoted in favour of future route guides), Trips, Profile, privacy page, search, categories, destination details, bookmarks, editable local plans, next departures and JSON backup/restore.
- Expo screens reuse catalog, planning validation, and tokens; SQLite stores local plans.
- Shared lifecycle outbox handles durable pending actions and account partitions. Web dispatch is mounted on Trips; native authenticated transport and reconnect dispatch remain pending.
- Java core/realtime/workers boot applications. Core API has opt-in authenticated journey endpoints and default-off signal, abuse-budget, expiry and moderation foundations (built for the archived private LIVE flow and reused for Spots) with PostgreSQL/Flyway ownership, retry and concurrency tests. Its default-off provider-alert endpoint is separate. Spots, posts, voice notes, Ask Ahead, Spot chat and route guides remain to be built.
- Local PostGIS, Redis, and S3-compatible services; versioned OpenAPI and generated client.
- Google verification, account mapping and revocable sessions plus opt-in browser auth endpoints are tested. Browser transport uses HttpOnly cookies, CSRF/origin checks and database rate limits. Web Google UI exists; real OAuth configuration, staging lifecycle verification and native transport are still pending.

Private trip journal title/notes and browser editing exist, but authenticated device QA, media and sharing remain pending. Real alert coverage for the pilot corridor, authenticated staging QA, route guides, Spot chat, push reminders and deployment remain pending. Admin is a restricted foundation shell. Local drafts are not live journeys. Device testing requires Android tooling.

Backend verification requires Docker for disposable PostgreSQL tests. Default API preview remains database-independent; see [database profile setup](docs/development/LOCAL_SETUP.md) for opt-in persistence and protected endpoints. Archived private LIVE, V3 summary and official-alert endpoints remain default off; nothing publishes traveller-derived output.

Start with [PRODUCT.md](docs/PRODUCT.md), [AGENTS.md](AGENTS.md), the [release checklist](todo.md), the [build plan](docs/development/BUILD_PLAN.md) and [verification notes](docs/quality/BUILD_STATUS.md). Superseded material is in [docs/archive](docs/archive/README.md); previous Wayfind references are historical.
