# Durable journey outbox

## Original foundation scope
Shared lifecycle command state machine and native SQLite persistence, ready for later authenticated sync. No dispatcher, HTTP write, account UI or upload is enabled. Existing local plans/backups are separate and unchanged. This is not full offline journey support.

## Acceptance
- Versioned, validated, bounded queue per verified account; no credentials, route labels, coordinates or notes stored in commands.
- Stable command identity derives from journey UUID and start/complete action. Duplicate enqueue is harmless; changed start kind conflicts.
- FIFO ordering preserves start-before-complete and one-active-journey transitions. A blocked head stops later actions until reconciled.
- Claim and acknowledgement persist atomically. A 30-second lease supports recovery after process death; stale acknowledgements cannot remove a reclaimed action.
- Transient errors retry with bounded exponential backoff and jitter; authentication errors wait for explicit session restoration; conflicts/permanent rejection remain blocked for future reconciliation UI.
- Queue corruption, unsupported versions, account mismatch, full queue and failed writes throw without replacing durable state.
- Test real SQLite file reopen, rollback, account isolation, queue capacity and crash/retry behavior. Expo adapter is typechecked/exported; real native runtime testing remains required.

## Ownership and reconciliation
The account identifier must come from verified identity when the dispatcher is implemented. It is a storage partition, not authentication. Never send another account's pending work after sign-out/account switching. Retain pending work in its partition until that owner resumes or explicitly discards it; account deletion must clear that partition. No automatic dropping on authentication or conflict errors. Future transport must reconcile server state before resolving permanent conflicts; no force-retry helper is provided for them.

## Durability boundary
SQLite exclusive transactions perform read/validate/transform/write with parameterized queries. A command is accepted only after commit. Network calls must occur outside transactions; claim before send, settle afterwards. Lost responses retry the same journey identity against the server's idempotent lifecycle. Acknowledged command removal does not yet save a local server journey snapshot; snapshot-plus-ack integration is required before user-facing sync.

## Limits
100 commands per account, 256 KiB serialized input, canonical lowercase UUIDs, integer millisecond scheduling. Backoff starts at 1 second and caps at 5 minutes with 50–100% jitter. Lease identifiers must be fresh UUIDs per claim. Persisted blocked reasons are fixed codes, never server text. Web durable outbox, authenticated transport, connectivity/background scheduling, logout/deletion hooks and reconciliation UI remain subsequent work.
## Implemented extensions

`LOCAL_JOURNEY_SNAPSHOTS_SPEC.md`, `DISPATCH_SPEC.md`,
`WEB_JOURNEY_STORAGE_SPEC.md` and `WEB_JOURNEY_CONTROLS_SPEC.md` supersede the
historical pending statements above. The authenticated web Trips workspace mounts
foreground/reconnect dispatch and confirmed-result reconciliation. Snapshot writes
and command acknowledgement are atomic. Native storage is implemented, but native
authenticated transport and device verification remain pending. Route estimates
and map resources are separate from these queues; see `MAPS_NAVIGATION_SPEC.md`.
