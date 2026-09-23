# Native Android authenticated journey slice

Status: implemented with Android debug/emulator smoke evidence, 2026-09-24.
Real configured-service and physical-device validation remain in the pending ledger.
Native server access is opt-in through
`native-auth`; production deployment remains a separate release decision.

## Acceptance

- A fixed configured HTTPS API origin is required. Android native HTTP enforces TLS,
  no redirects, no cookies, bounded timeouts and response bytes. It sends an opaque
  bearer only on the native API namespace. Responses and failures are redacted.
- Sign-in obtains a fresh one-use server challenge before each Google credential
  request, binds the Google ID token to its nonce, exchanges the challenge, and
  commits only the validated Routiqo session to SecureStore. Cancellation, account
  changes and storage failures never expose a verified account.
- Cold start verifies the stored session with the server. Renewal is coordinated
  through the same vault; logout invalidates server lineage and clears the vault.
  Offline mode never treats a cached identity as authenticated.
- Native journey routes use their own bearer-only security chain. Every request
  derives ownership from the server session and verifies the exact account header;
  foreign and missing objects have identical 404 behavior. Browser cookie/CSRF
  behavior is unchanged. Start and completion retain existing durable idempotency.
- The mobile Trips screen can explicitly start/complete a journey and inspect
  owner-scoped history. SQLite commits each pending command before network work;
  foreground reconnect uses bounded dispatch and exact lease acknowledgement.
  A restored session unblocks only authentication failures. Local plans are not
  automatically uploaded, migrated or promoted to journeys.
  A journey owner verified earlier in the same running process may continue saving
  local commands through an outage after the bearer expires. This is local
  continuity only: expired credentials cannot authorize server requests, and a
  cold start requires server verification before exposing that account partition.
- MapLibre renders an explicit regional basemap preview only with a configured,
  reviewed HTTPS style under Routiqo control. No default public tile server,
  stranger location, GPS watcher, or journey route geometry. Unconfigured/offline
  states remain readable without the map. Expo Go is unsupported.
- Tests cover native HTTP isolation, owner access, transport path/origin/size
  limits, session lifecycle races, outbox replay/reconnect and map configuration.
  Native redirect/TLS behavior still needs real-device staging verification.
  Android debug build plus emulator or device interaction is required before calling
  the native feature tested. Record external OAuth, regional assets and signing
  prerequisites in the pending ledger.

## Rollout

The native client requires explicit build-time configuration and the server
`native-auth` profile. Missing settings fail closed with local planning still
available. No V3 community traffic flag is changed. Rollback removes the native
profile/client configuration; durable journey rows and account-partitioned pending
commands remain for safe reconciliation.
