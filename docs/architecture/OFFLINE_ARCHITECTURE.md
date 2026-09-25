# Offline architecture

Spot content follows the offline rule in [`../PRODUCT.md`](../PRODUCT.md).
Posts, signals, answers and voice notes (audio held locally until upload) made offline are queued in the
journey outbox with capture time and an idempotency key. On sync the server
accepts them only if the content type's lifetime has not elapsed since capture
(server time), and shows them with their capture time, never as new. Ghost Mode,
sign-out and account deletion clear queued social items; Ghost Mode also stops
Spot-passage sending. Read-only Spot content cached offline is labelled stale and
removed at expiry. Reconnect refreshes Spot content under current authorization.
An uncertain write is retried with the exact idempotency identity only within its
type's lifetime. Account/journey changes clear cached Spot state. Maps, saved
plans and start/finish durability continue to follow their existing policies.
The archived LIVE design disabled offline Quick Signal submission; that
default-off code is unchanged.
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
and ADR 0019 for the existing provider boundary. ADR 0021 supersedes its provider
choice with MapLibre and Routiqo-controlled services; migration remains pending.

Target map resources are separate from private route/session records. Use versioned
regional archives through our own tile endpoints for MapLibre offline packs,
subject to pinned native SDK verification. Do not scrape public OSM tiles. Direct
local PMTiles is an alternative requiring a distinct downloader; its native sources
do not support ordinary offline-pack downloads/caching. All packs must include
required style/glyph/sprite assets, coverage/version metadata, integrity validation,
size limits, resumable downloads and atomic replacement with space checks. Expose
progress, cancel, retry and delete; interrupted or corrupt packs must not be reported
ready. Record attribution and refresh policy. Keep downloaded-area metadata private;
define account switch/deletion treatment before enabling persistent map downloads.

Saved-route restoration needs a separate reviewed retention policy and explicit
account isolation. Saving basemap tiles must not silently persist exact endpoints.
Offline rerouting requires an on-device engine and routing graph; remote Valhalla
alone is insufficient. Prototype embedded routing and measure Android storage,
memory, battery, location permissions, boundary coverage and airplane-mode cold
starts before promising guidance. When unavailable, retain verified downloaded
instructions with their age/coverage and clearly state that recalculation needs
connectivity; never synthesize a replacement route.
Do not sync stale posts into expired Spots or rooms. Spot passage is never replayed after its prompt expires or Ghost Mode is on. Ghost Mode must take priority over reconnect and outbox replay.
