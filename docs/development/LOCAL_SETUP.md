# Local development prerequisites

Checked on 2026-09-06, Windows/PowerShell. Project: `D:\Pras\routiqo`.

| Tool | Observed state | Action |
|---|---|---|
| Node.js | 22.21.1 available | Retain; verify package-specific engine requirements when locking dependencies |
| npm | 10.9.4 available | Can bootstrap the project-local package manager |
| Git | 2.52.0.windows.1 available | Ready |
| Docker Desktop/engine | Engine responds with 29.1.3 | Ready for Compose-managed local services |
| Java | JDK 25.0.4.1 installed; JAVA_HOME may still reference 21 | Use scripts/backend.ps1, which selects installed JDK 25 locally |
| pnpm | Not on PATH; Corepack exists | Pin in package.json and bootstrap during foundation setup |
| Gradle | Not required as a system installation | Add a pinned Gradle wrapper compatible with Java 25 |
| Android Studio / SDK | Not found at standard checked paths; adb/emulator not on PATH | Needed for local Android emulator/native builds; may be installed elsewhere |

## JDK 25

Install a JDK, not only a JRE. Keep JDK 21 if other projects depend on it. After configuring JAVA_HOME and PATH, open a fresh terminal and verify:

```powershell
java -version
javac -version
$env:JAVA_HOME
```

Both version commands should report 25 for Routiqo development. Avoid changing other projects' build configuration.

## Android development

Android Studio supplies the Android SDK, platform tools, and emulator/device tooling. Install the SDK/platform versions selected by the eventual Expo/native build rather than guessing versions in advance. A physical Android device can substitute for an emulator.

Native Mapbox integration will require a compatible development build; do not assume Expo Go supports arbitrary native modules. Exact Expo/Mapbox compatibility will be checked when that integration is selected.

## Managed by the project

The foundation will pin pnpm, TypeScript/React/Next/Expo and Java dependencies, add a Gradle wrapper, and run PostgreSQL/PostGIS, Redis, and local S3-compatible storage through Docker Compose. Separate host installations of these services are unnecessary.

AWS credentials, Mapbox credentials, AI providers, and push configuration are not needed to initialize the repository. Introduce provider configuration at the phase that needs it; keep secrets out of source control and client bundles. Native iOS builds require macOS/Xcode or a suitable cloud build workflow later.

## Next foundation acceptance criteria

1. Pin workspace tools and dependency versions.
2. Add minimal buildable mobile, web, admin, core-api, realtime, and worker shells.
3. Add local Compose services bound to loopback with development-only credentials.
4. Add initial OpenAPI contract/client generation, shared design tokens, and quality checks.
5. Verify builds/tests and render the client shells, recording environment limitations honestly.


## Current backend database modes

The pinned workspace, Gradle wrapper and Compose stack are implemented; the earlier foundation checklist above is historical. Use `npx.cmd --yes pnpm@10.34.4` when pnpm is not on PATH.

The default `preview` profile serves health/catalog without a database. The `persistence` profile wires the internal journey service and runs versioned Flyway migrations. It does not expose journey writes. Set only `persistence` as the active profile, not both profiles.

For an explicitly selected local database, supply these environment variables in the backend terminal:

- `SPRING_PROFILES_ACTIVE=persistence`
- `ROUTIQO_DATABASE_URL=jdbc:postgresql://127.0.0.1:5442/routiqo` for the supplied Compose instance
- `ROUTIQO_DATABASE_USER` and `ROUTIQO_DATABASE_PASSWORD` matching that database (local development values are in docker-compose.yml)

Then run `npx.cmd --yes pnpm@10.34.4 backend:dev`. Startup applies migrations to the selected database; review the URL before running. Missing credentials or migration errors fail startup. Hikari and transaction/statement timeouts are bounded. Remove the active-profile variable to return to default preview.

`backend:check` now requires Docker for isolated PostgreSQL Testcontainers integration tests. It creates disposable test containers and does not use the Compose database or volumes. It fails if Docker is unavailable; no silent test skipping. First run may download container images. CI's Java job runs the same Gradle checks on a Docker-capable hosted runner.

## Google verification configuration

The internal Google verifier is opt-in. For database-independent verification setup use `SPRING_PROFILES_ACTIVE=preview,google-auth`; for account persistence use `persistence,google-auth` plus the database variables above. Set `ROUTIQO_GOOGLE_CLIENT_ID` to the intended Google OAuth client ID. It is an identifier, not a client secret. No key-fetch URL override is accepted. Missing/invalid client configuration fails startup; provider failures reject verification.

Google OAuth consent-screen/client registration and authorized web origins are still needed for real login. Do not put client secrets in web/mobile bundles or paste them into task messages. Session exchange and web login are implemented; setting the client ID alone does not configure the remaining backend and proxy settings. Phone OTP is deferred.

## Opt-in browser auth HTTP

Use profiles `persistence,google-auth,web-auth` with the existing database/Google variables and:

- `ROUTIQO_WEB_ORIGIN`: one exact origin, no trailing slash/path. Production requires HTTPS.
- `ROUTIQO_AUTH_RATE_SECRET`: a random secret of at least 32 characters, shared by all replicas, supplied externally.
- `ROUTIQO_AUTH_SECURE_COOKIES`: defaults true. Set false only for explicit local HTTP with localhost/127.0.0.1 origin.

Browser requests use the implemented same-origin API proxy; wildcard CORS is not enabled. Configure the web server with `ROUTIQO_GOOGLE_CLIENT_ID`, `ROUTIQO_AUTH_API_URL` (the backend origin) and the same `ROUTIQO_WEB_ORIGIN`. HTTP tests exercise the routes directly without a real Google account. Default preview remains unchanged.

GET /api/v1/auth/csrf sets its HttpOnly cookie and returns a masked token. Send the cookie plus X-XSRF-TOKEN and the exact Origin on POST challenge/exchange/logout, using application/json. HTTPS session/binding/CSRF cookie names have __Host- prefixes; loopback development names do not. Never expose session credentials to JavaScript.

Rate limits use the socket peer, not forwarded headers. A reverse proxy therefore shares a peer bucket until trusted forwarding is explicitly configured. Cleanup runs in bounded batches; measure backlog/capacity before launch. Sessions expire after 15 minutes; bounded refresh rotation supports a maximum 12-hour lineage.

## Opt-in private routing

Add `routing` to `persistence,google-auth,web-auth` and supply `ROUTIQO_MAPBOX_TOKEN` only to the backend process through external secret configuration. Missing token fails startup when this profile is active. Never use a `NEXT_PUBLIC_` variable for this token. The web proxy uses the same backend configuration as authentication.

The endpoint is POST `/api/v1/routes`, with mode `driving`, `walking` or `cycling`, and two longitude/latitude coordinate arrays named `origin` and `destination`. It requires the browser session, CSRF, exact origin and matching account header. It returns private Mapbox estimates without creating journeys or presence. POST `/api/v1/routes/places` accepts a `query` for temporary city/street/address lookup using the same token and guards. Trips now includes an authenticated place-selection and route-estimate form, with an optional one-time current-location origin. Map rendering and navigation remain pending. No real Mapbox request has been tested; automated tests use synthetic transport responses.
