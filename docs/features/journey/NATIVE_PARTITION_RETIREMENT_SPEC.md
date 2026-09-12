# Native journey partition retirement

The native SQLite journey store must not recreate a deleted account's queue or
snapshots when delayed work resumes. This closes a storage prerequisite for native
authentication; it does not enable native login, account deletion UI or dispatch.

## Behavior

- Keep a permanent, local account-ID-only marker in `journey_retired_accounts_v1`.
  Initialization creates this table additively and preserves existing queues.
- `clearJourneyPartition` retires the specified account and removes its queue and
  snapshots in one exclusive transaction. Repeating it succeeds without restoring
  any data. It is only for a deleted account, never ordinary sign-out or a cache reset.
- Queue updates check retirement inside their transaction before invoking the
  caller's change callback. Retired accounts fail without writes or callback execution.
- Result acknowledgements check retirement in the same transaction as the lease
  and snapshot update. A late result for a retired account returns false.
- Validate the account identifier before starting each operation. Preserve other
  accounts and reject storage failures rather than falling back to empty state.
- There is no unretire operation. A newly created server account has a new identity.
  Markers contain no credentials, journey content or timestamps and are excluded
  from planning backups. They remain until application storage is removed.

SQLite's exclusive write transaction serializes retirement against queue writes:
if a write commits first, retirement removes it; if retirement commits first, the
write is rejected. A failed retirement rolls back its marker and both deletions,
so callers must not report successful local removal. This is logical deletion;
physical SQLite page erasure, OS backup policies and device behavior remain release
work. No network operation runs inside these transactions.

## Verification and threat coverage

Use file-backed SQLite tests for reopen/reinitialization, delayed callback rejection,
late acknowledgements, account isolation, additive initialization, repeated removal,
invalid identities, marker-write failure and deletion failure rollback. Existing
queue/acknowledgement tests must continue to pass. Native export verifies bundling,
not Android execution or secure storage.

Relevant threats: T02 (account isolation), T12 (private stored data), T13
(replay and races), T19 (retention/deletion failure), and deletion/stale-work
requirements in `SECURITY.md`. No new endpoint, permission or external provider.
