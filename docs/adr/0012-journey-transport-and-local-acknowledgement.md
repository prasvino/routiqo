# ADR 0012: Journey transport and atomic local acknowledgement

Status: accepted, 2026-09-08.

Expose owner-scoped lifecycle endpoints only with the guarded web-auth profile. Browser cookies authenticate; X-Routiqo-Account must match that identity and prevents stale queued commands from following a cross-tab account switch. Stable UUIDs provide idempotent start/completion. Generated OpenAPI0.4.0 models describe the response, which excludes owner IDs and location information. Default preview remains closed.

Native SQLite commits server snapshots and queue removal together. Web IndexedDB stores the two validated structures in one account record and uses strict readwrite transactions. Stale leases cannot acknowledge or recreate deleted partitions. Preserve microsecond timestamps and reject conflicting lifecycle observations. Cache at most100 recent records, pruning only older completed records; this is not full offline history.

Single-command orchestration checks the active account before claim and send, then uses transport outcome classification and durable acknowledgement. No network work runs in storage transactions. No background loop/live journey UI is enabled yet. Planning drafts and their backups remain independent and are never automatically uploaded.

fake-indexeddb6.2.5 is a pinned development-only dependency for browser storage transaction tests. Actual browser persistence/eviction and native runtime testing remain release gates. Integration still requires dispatch wiring, reconnect/session hooks, conflict reconciliation UI and device testing.
