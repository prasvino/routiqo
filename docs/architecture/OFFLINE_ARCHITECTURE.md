# Offline architecture
Current: validated versioned local planning persistence, browser localStorage and native SQLite. Storage errors are visible; corrupted payloads do not silently become trusted state.

Authenticated journey commands use account-partitioned durable queues and snapshots
in web IndexedDB and native SQLite. The web Trips workspace dispatches in the
foreground and on reconnect, with confirmed-result reconciliation and visible
retry/block states. Native storage is implemented; authenticated native transport
and device verification remain pending. See `docs/features/journey/OUTBOX_SPEC.md`.
Each retryable write needs an idempotency key, expiry policy, conflict resolution,
and user-visible pending/failed state.

Calculated web routes are a separate, in-memory resource. Loaded directions survive
connection loss while their view remains open. Network requests are cancelled on
disconnect and restarted only by explicit action; reconnect does not recalculate.
These routes are not durable journey snapshots or downloaded offline navigation.
Mapbox SDK browser caches have a separate disclosed lifecycle; they are not an
application-managed offline package. See `docs/features/journey/MAPS_NAVIGATION_SPEC.md`
and ADR 0019 for provider boundaries and remaining native download/rerouting work.
Do not sync stale route incidents into expired rooms. Presence is ephemeral and must not be replayed from an offline queue. Ghost Mode must take priority over reconnect.
