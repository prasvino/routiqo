# 0006 — Durable lifecycle outbox before authenticated sync

Use a shared pure TypeScript queue state machine and the existing Expo SQLite dependency for native persistence. No new production dependency or background service. A versioned table stores one bounded queue per verified account. Exclusive transactions protect read/transform/write; network work belongs outside the transaction.

Stable journey/action identities match the backend's retry-safe lifecycle. FIFO ordering prevents completion overtaking a pending start. Persisted leases recover after process interruption; acknowledgements must match the current lease. Authentication, conflict and permanent rejection retain commands instead of losing work. Transient retries have capped exponential backoff and caller-supplied jitter. Dispatchers must supply fresh lease UUIDs and fresh jitter per retry.

Avoid putting exact route labels, coordinates, notes or credentials in this queue. Existing unauthenticated planning drafts remain separate and are never automatically converted into queued commands.

Tests execute the native adapter's SQL against disposable file-backed SQLite through Node 22's built-in experimental SQLite API, including reopen and rollback. This adds no test dependency, but does not replace Expo/device verification. Existing strict TypeScript checks validate the adapter against Expo's installed types; Android export validates bundling.

Deferred integration gates: verified account/session lifecycle, authenticated transport contracts, atomic server-snapshot plus acknowledgement storage, web persistence, connectivity/background scheduling and reconciliation/discard UI. Until those gates are implemented, no user-facing queue or sync claim is made. Account switching must never send an old account's queue; account deletion must clear its partition.
