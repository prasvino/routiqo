# Web durable journey partitions

IndexedDB database routiqo-journeys-v1 stores one versioned record per verified account. Each record contains the validated outbox and recent server snapshots. A readwrite transaction reads, synchronously transforms and writes the record; callbacks must not perform network calls or return promises. Cross-tab writers serialize through IndexedDB. Acknowledgement saves result and removes command in this same record/commit. Missing, stale or cleared leases do not create records.

Invalid versions, corrupt data, wrong account IDs and oversized state throw without overwriting existing work. Errors, aborts and blocked database opening remain visible to the future caller. Connections close after each operation. Explicit account deletion removes only that account's partition; ordinary sign-out preserves pending work. This storage is separate from local planning drafts/backups.

Tests use fake-indexeddb6.2.5 as a development-only browser API implementation for transaction/reopen/race/rollback coverage. Actual browser persistence/eviction/storage-denial testing remains required before a sync UI ships. The dependency never enters the mobile or production app runtime. No dispatcher loop is mounted in this slice.
