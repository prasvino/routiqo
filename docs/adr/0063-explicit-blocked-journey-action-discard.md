# ADR 0063: Explicit discard of a blocked journey action

Status: accepted, 2026-09-25.

## Context

ADR 0006 and OUTBOX_SPEC keep conflicting or rejected journey commands blocked. There is no automatic drop and no force retry, and the server must be reconciled first. The existing reconcile path handles only the case where the server already applied the action. In every other case the head command blocks the device's queue indefinitely.

## Decision

Allow the traveller to **explicitly discard** a blocked head command. This is allowed only after a successful, fresh server read shows that the action has not been applied and cannot apply as queued:
- the journey is absent for the account;
- a start's journey exists with a different kind;
- a finish's journey is still active.

A start's dependent finish for the same journey is discarded with it. The rule lives in shared code, and storage applies it in one transaction with head re-validation. After a discard, the client re-reads recent server history.

## Alternatives

- **Automatic discard on conflict.** Rejected: it would silently lose intent and contradicts ADR 0006.
- **Force retry.** Rejected: the server already refused the action, and the outbox spec forbids a force-retry helper.
- **Rewrite the command to match the server** (for example, change a start's kind). Rejected: that would invent an action the traveller did not take.

## Consequences

A blocked device can recover without clearing all local data. The traveller loses only the unsent action they confirm, and never a server record or a confirmed snapshot. Native Android controls remain to be added using the same shared rule.
