# Atomic journey result and outbox acknowledgement

Before dispatching lifecycle writes, persist a validated server journey result in the same SQLite transaction that removes the acknowledged head command. A crash or storage failure must leave both old queue and old snapshots unchanged. Network calls remain outside transactions. Only the current lease may acknowledge; stale worker results do not write snapshots, including after account queue deletion. Account partitions are explicit and never inferred from a response body.

Use generated Journey transport shape with runtime validation of UUID, kind/status, canonical UTC dates and up to microsecond precision. Reject impossible dates, completed-before-start, active-with-completion, wrong journey/kind and active response to a completion command. Never cache unknown fields or credentials. A completed snapshot never reverts to active, and immutable kind/start/completion timestamps must agree on repeated observations.

Store a bounded version-one cache of at most100 records per account, ordered by start time/id. When full, prune oldest completed snapshots; never silently discard an active record. This is a recent lifecycle cache, not complete server history. Read corrupt/oversized/mismatched account state as an error; do not replace it with an empty cache. Local planning backups exclude this cache and outbox. Account deletion clears both partitions in one transaction; not yet wired to native authentication.

Tests: response validation and monotonic merge, capacity/pruning, exact microsecond comparison, stale acknowledgement, reopen after commit, snapshot-insert failure rollback preserving queue, wrong-account isolation, and atomic local partition removal. No dispatcher/background network or native account storage is enabled by this slice.

Native deletion additionally retains a permanent account-ID-only retirement marker in the same transaction as both deletions. Queue updates check it before running caller callbacks; late acknowledgements return false. Ordinary sign-out must not retire a partition. See `NATIVE_PARTITION_RETIREMENT_SPEC.md` for lifetime, rollback and initialization requirements.

Web recent restoration additionally checks a cached active journey that is absent from the most recent 20 server records. Fetch its owner-bound detail before committing any restoration. Missing/unavailable detail fails without changing saved work; absence from a page must never imply completion. Merge the recent page and at most one separately confirmed old journey atomically (maximum 21 records), preserving pending commands and the one-active invariant. Concurrent deletion still rejects writes through the retirement marker. This resolves an old active snapshot completed on another device without requiring the user to page through all newer journeys.

## Delayed history restoration — 2026-09-20

Recent history is a server observation, not a lifecycle command acknowledgement.
A response can have been read before another tab records completion. During the
atomic history merge, retain an already completed snapshot when a delayed active
observation has the exact same journey ID, kind and canonical microsecond start
time. Do not regress the snapshot or reject the entire otherwise valid page solely
because that older observation arrived late. This exception applies only to history
merging; command acknowledgement still requires the strict validated result.

Normalize and copy the full bounded response batch before awaiting storage. Reject
duplicate IDs, malformed records, changed kind/start and differing non-null
completion times. The transaction must preserve the complete current outbox and
one-active invariant; any conflict rolls back the entire history merge. Retirement
continues to reject late writes. No absent record proves completion, no retained
command is acknowledged by a history read, and no cross-device cache completeness
is claimed.

Acceptance: a delayed page arriving after another tab records completion preserves
that terminal snapshot and pending commands while restoring other valid records.
Cover immutable conflicts, completion-time conflicts, duplicate IDs, caller input
mutation across async storage opening, rollback, retirement and account isolation.

Compare incoming records with the original transaction snapshot, including records
pruned while processing earlier items in the same batch. Otherwise a full cache
could prune an old completion and reinsert its delayed active observation. Normal
bounded-cache pruning may still remove old completed history; it must never turn
that removal into permission to resurrect an active journey.
