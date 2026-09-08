# Offline architecture
Current: validated versioned local planning persistence, browser localStorage and native SQLite. Storage errors are visible; corrupted payloads do not silently become trusted state.
Future active journeys require durable local state and a sync queue from their first implementation. Each retryable write needs an idempotency key, expiry policy, conflict resolution, and user-visible pending/failed state.
Do not sync stale route incidents into expired rooms. Presence is ephemeral and must not be replayed from an offline queue. Ghost Mode must take priority over reconnect.

