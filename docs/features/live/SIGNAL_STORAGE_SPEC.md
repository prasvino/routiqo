# Internal transactional signal storage

Status: implemented and tested as internal persistence under ADR 0024 and ADR 0028.
No public API, provider-based anchor validation, moderation, projection, queue
dispatch or scheduler is enabled.

Implement routeupdate-owned grant and receipt persistence plus an internal service
that composes JourneyWriteAuthority, PresenceConsentParticipant and
LiveRouteContextParticipant. Use the existing trusted synchronous transaction,
shared datasource and current independently authenticated actor. No cross-domain
repository SQL. Every new grant/acceptance requires owned ACTIVE journey, sharing
consent and current stored context. Validate time again after relevant row locks;
context expiry during a wait must deny. No network calls or nested authority entry.

## Durable identity and acceptance

Issue unpredictable server UUID commands internally from current consent/context,
requested registered anchor and a bounded set of permitted enum categories. Build
a SignalAdmission at server time; expiry is no later than now+90 seconds or context
expiry. Clients cannot select a fresh command ID or implicitly renew one. Persist
the grant's complete admission and UNUSED/CONSUMED state, independently of receipts.

Submission supplies the exact existing SignalCommandPolicy fingerprint and command
ID. Under current authenticated journey ownership, a retained receipt can return
its original outcome without a live grant/consent/context, including after journey
completion or withdrawal. Changed fingerprints conflict. Future/expired supplied
receipts deny, never refresh timestamps or resurrect evidence. A missing receipt
requires a matching unused live grant and current consent/context/admission.

New acceptance atomically consumes the grant, reserves its write budget, supersedes
the prior actor/anchor/category contribution and inserts the receipt. Failed work
rolls back all changes. Preserve the existing domain rules, explicit positive
evidence lifetime <=15 minutes and receipt retention <=24 hours and >=evidence
lifetime. Validate original durations before flooring timestamps to microseconds;
reject a zero interval. Use server time after locks, never submitted timestamps.
Receipt replay ignores newly requested lifetime settings and preserves first times.

Represent the latest contribution slot using a partial unique receipt index on
(actor, anchor, category) WHERE state='ACTIVE', rather than a separate slot table.
Category must match the closed signal value in database constraints. The key spans
journeys. Lock the grant then prior active receipt (slot); receipt state and slot
are the same row. Supersede even an expired previous active row before insertion.
No slot-to-receipt cascade or receipt-to-grant FK is allowed. Account and owned
journey FKs cascade deletion; context/consent snapshots are private immutable fields,
not FKs that let context cleanup erase retained outcomes. Raw private records and
errors must be redacted. Add V10 with finite time/lifetime/state/enum/identity checks
and indexed actor-command/slot/expiry lookups, tested only in disposable PostgreSQL.

Owner-scoped withdrawal updates only a logically retained receipt, using its
terminal domain transition. It does not renew retention or physically erase data.
Completion/Ghost/context replacement deny further acceptance via current authority;
receipt ACTIVE means lifecycle state only, never permission to publish. Retained
outcomes remain private. Durable publication invalidation is still a release gate.

## Resource budgets and cleanup

Provide database-backed internal fixed-minute budgets: at most 10 new grants and
5 new accepted signals per actor per minute, independent of journey/context. Keep
only one current bucket for each actor/action, under the account gate; clock moving
before a stored bucket start denies. Retries of retained commands do not consume
new budget. Reserve only in the successful transaction. These conservative internal
limits are not approval of public quotas: peer/request limits, anti-Sybil controls,
operator tuning and moderation remain mandatory before exposure. No process-local
budget or permissive stub is allowed. The budget table holds no per-command history.

Callable expiry maintenance is bounded to 1..100 deleted rows per call, with a
five-second transaction/query budget and no scheduler. Grant cleanup deletes only
expired grants and cannot cascade receipts. Receipt cleanup deletes only expired
receipts and must not reset/derive UNUSED grant state; after a short receipt is
purged while its consumed grant is live, replay still denies. Missing grants deny.
Use leaf-only SKIP LOCKED cleanup with exact identity/expiry rechecks and no parent
authority locks afterward. Inner/outer sanitizers precede rollback logging. No
cleanup batch may acquire grant and receipt locks in reverse acceptance order.
Document logical expiry versus delayed physical cleanup and the operating-job gate.

## Verification

Meaningful disposable database tests must cover real service wiring, owned/current
issuance, category/anchor restrictions, concurrent duplicate acceptance, changed
payload conflict, contribution replacement across journeys, explicit withdrawal,
rollback after partial work, quota enforcement across independent adapters/journey
changes, no budget charge on retry, Ghost/context/completion/delete races, expiry
after lock waits, microsecond round trips and generic diagnostics. Include retained
replay after grant cleanup; expired receipt denial; receipt purge before consumed
grant expiry; missing grant denial; locked-row cleanup/replacement safety; bounded
cleanup and account deletion. Use actual current authority services, no mock consent
or invented route validity. Record exact remaining public/operational gates.
