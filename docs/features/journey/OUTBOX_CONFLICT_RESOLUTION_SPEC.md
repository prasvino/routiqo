# Resolving a blocked journey action

Status: implemented for web; native Android controls pending. Decision record: ADR 0063.

## Problem

The outbox is first-in, first-out. When the server rejects the head command as a conflict or a permanent rejection, the command is marked `blocked`, and every later start or finish on that device waits behind it (OUTBOX_SPEC). "Check server status" already clears the head when the server turns out to have applied the same action.

When the server state differs, the traveller previously saw "Your saved action remains paused until reconciliation is available" and could not continue. Examples:
- a start refused because another device started a different journey;
- a finish for a journey the server no longer has for this account.

## Behaviour

After "Check server status" succeeds and shows that the saved action cannot apply, the workspace offers **Discard this unsent action**, with an inline confirmation. The confirmation states what will be discarded, and that the server's own records and any planning notes are unchanged.

Discarding is allowed only when **all** of the following hold:
- the head command is still the one that was checked (same journey ID and action, and the same kind for a start);
- it is blocked as `conflict` or `rejected`, with no active lease;
- the server observation is fresh, from the check just made, and shows that the action has **not** been applied. That is one of:
  - the journey is not found for this account;
  - a start whose journey exists with a different kind;
  - a finish whose journey is still active.

  An observation showing the action applied is refused: it must use the existing reconcile path instead, so a discard never removes work the server kept.

Discarding removes the head. If the head is a start, it also removes any queued finish for the same journey, because that finish depends on a start that will never be sent. Every other queued action is kept, in order. Confirmed snapshots are never edited by a discard.

After a discard, the workspace re-reads recent history from the server, so the device shows the server's current journeys. Nothing is discarded automatically, offline, after a failed or stale check, or for an `authentication` block, which still needs sign-in.

## Shared rule

`discardBlockedJourneyCommand(outbox, expected, observation)` in `packages/shared/src/journey-outbox.ts` holds the rule, so Android can reuse it. It returns the new outbox and the discarded commands, or throws without changing anything. The web storage wrapper runs it inside the existing account-partition transaction. A concurrent change, retirement or account switch makes the discard fail and leaves storage untouched.

## Tests

- **Shared:** each allowed observation; refusal for an applied observation, a non-head or mismatched command, an unblocked or leased head, and an authentication block; dependent finish removal; preservation of other entries and their order.
- **Web storage:** transactional discard; refusal when the head changed during the check.
- **Workspace:** the discard is offered only after a successful check showing a non-applicable state; confirmation is required; history is refreshed afterwards; nothing is offered after a failed check.

## Out of scope

- Native Android controls.
- Merging the traveller's discarded intent into the server journey.
- Automatic resolution.
