# Authorization
Foundation denies all undeclared protected paths, independent of client-provided IDs.
Future authorization applies at object/action/subscription level using verified actor identity. A valid socket is not permission to subscribe to arbitrary rooms. Room location claims are not proof of membership. Admin access requires separate policy and audit.
Tests must cover cross-user journey access, expired rooms, block enforcement and revoked sessions.

## Pilot rooms, Spot chat and Ask Ahead

Planned for the pilot (see [`docs/PRODUCT.md`](../PRODUCT.md)); not implemented
unless [`BUILD_STATUS.md`](../quality/BUILD_STATUS.md) says so.

- Spot chat and festival route room subscriptions are authorized per room on the
  server for the current account, and rechecked on reconnect and replay. A valid
  socket or HTTP session, a Spot ID, or a client location claim is not
  permission to subscribe. Expired rooms deny new subscriptions and replay.
- Blocks, account restriction, Ghost Mode and hidden or expired content apply to
  subscription, delivery and replay, not only to REST reads.
- Ask Ahead questions are offered only to accounts with a current Spot-passage
  opt-in that recently passed or posted at that Spot, and never to accounts
  blocked by or blocking the asker. No endpoint lets a caller name or list
  recipients.
- Only the author may delete their own post, voice note, signal, question,
  answer or route guide; authorship is checked on the server, never inferred
  from an alias.
- Tests must also cover subscription to expired or unauthorized rooms, blocked
  users in shared rooms, Ask Ahead offers to non-opted-in or blocked accounts,
  and deletion by a non-author.

## Pilot moderator authority

- Moderators can hide posts, voice notes and chat items, and restrict accounts.
  Every action is authorized server-side against a current grant and recorded
  in minimized audit (actor, action, target class, reason, result, server time).
- The pilot uses a lighter grant process than the V3 staging runbooks: a small
  named moderator rota with time-boxed grants covering the event window, revoked
  at its end. Admin access keeps a separate admin login in the separate admin app
  and server-side checks on every action; a consumer session never grants
  moderator authority.
- Moderators see content and aliases needed for the action, not precise
  location, Spot-passage records or Ask Ahead recipient lists.

## Internal contribution moderation (ADR 0041)

The audited restriction boundary requires two current enabled accounts and an
unexpired database permission for the exact RESTRICT or RESTORE action. The
operator identity is a trusted internal caller assertion; this does not implement
administrative authentication. No consumer endpoint may call it and no operator
grant is seeded. Strong authentication, step-up and grant administration remain
release prerequisites.

Grants are action-scoped across distinct enabled targets, not case-assignment or
target-scoped permission. Both actions require exact target revisions. Retained
retries still require the current action grant and return only the original receipt.
The same transaction commits the effect, audit and independent operator debit.
Signal ingestion has read-only access to restriction state. Grant administration
must lock the enabled operator account before the grant, matching the action
boundary's ordered account-pair-first protocol.
