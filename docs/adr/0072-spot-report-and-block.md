# ADR 0072: Report and Block on Spot items

Date: 2026-09-26
Status: accepted for the Diwali 2026 dry run; implemented default-off (step 3b) with
[POSTS_AND_SIGNALS_SPEC.md](../features/spots/POSTS_AND_SIGNALS_SPEC.md)

## Context

Step 3a ([ADR 0071](0071-spot-contributions-storage-and-lifetimes.md)) lets
travellers post and signal on Spots. The spec requires two things before
contributions reach testers:
- **Report** on every post and signal summary;
- **Block** from any post.

Two earlier designs already cover the mechanics:
- [ADR 0064](0064-v1-reporting-protocol.md) covers reports
  (receipt first, 7/30-day retention split, 10 per 24 h, severity-first queue);
- [ADR 0040](0040-internal-durable-block-policy.md) covers block edges.

Both were built for archived capabilities. The moderator queue and hide are
step 4. The Android buttons are step 3c.

## Decision

1. **Default-off.**
   - Both endpoints sit behind the existing write flags
     (`ROUTIQO_SPOTS_API_ENABLED` and `ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED`, exact
     `true`).
   - The endpoints are:
     - `POST /spots/items/{ref}/reports` with `{requestId, reason}`, which returns
       202;
     - `POST /spots/items/{ref}/block-author` with `{}`, which returns 204.
2. **What can be reported and blocked.**
   - A report can target a current post (its ref) or a current signal summary
     (its summary ref).
   - Block targets current posts only. Signals are unattributed, so blocking from
     a summary would tell the blocker that a particular account signalled.
   - Neither works on the viewer's own content (403). A summary counts as the
     viewer's own only when every current signal in it is theirs.
   - An unknown, expired or deleted item returns 404.
3. **A report targets one incident.**
   - A summary ref is permanent per Spot, category and value, so it is not itself
     an incident. A summary report covers the signals current at that moment. Its
     incident starts at the earliest of those signals.
   - For a post, the incident is the post, starting at its creation.
   - One reporter can report each incident once. A later incident on the same
     summary can be reported again and is counted separately.
4. **ADR 0064 mechanics in new tables (migration V31).** The V3
   `community_traffic_*` tables and code are not reused.
   - `spot_report` holds the reporter row, which cascades on account deletion.
     It is kept 7 days. Its primary key is (reporter, requestId), and it is
     unique on (reporter, item, incident).
   - `spot_report_group` is reporter-free, with one row per incident. It holds
     per-reason counts, `latest` and a review sequence, and it is kept 30 days
     after the latest report.
   - **Receipt first:** an exact replay returns the original receipt, even after
     the quota is spent or the item has expired. A changed replay returns 409, and
     so does reporting the same incident twice. Once the 7-day reporter row is
     gone, a replay counts as a new report.
   - **Quota:** 10 reports per account per rolling 24 h, counted under the
     account row lock. `Retry-After` says when the oldest report leaves the
     window.
   - **Attempt limit:** 20 attempts a minute, whatever the outcome, so failing
     probes cannot hammer the account lock.
   - **Reasons:** `false_alarm`, `abuse`, `spam`, `personal_data`, `unsafe`.
   - **Queue order:** an index puts `unsafe` and `abuse` first for step 4.
   - Report counts are never public. The receipt is only
     `{receivedAt, receiptExpiresAt}`.
   - **Account deletion:** removes the reporter rows and deliberately keeps the
     group counts, as the spec says. There is no decrement trigger.
5. **Evidence is kept a fixed 30 days.**
   - A report records the reported post, or the signals current in the summary,
     in `spot_report_evidence`. They are kept for 30 days from the *first*
     report naming them, and later reports never extend that.
   - Maintenance does not purge an evidence row's post or signal before then.
     Readers still hide expired items, so nothing reappears.
   - **Highlights:** a reported post never becomes one. Until moderation (step 4)
     can decide that a report is not upheld, any report blocks promotion.
6. **Block hides the alias in its room only.**
   - The spec asked for a blocked author's posts and votes to be hidden from the
     blocker *everywhere*. Review showed that this would work as an oracle:
     1. A blocker blocks one alias.
     2. They re-read the corridor.
     3. From the difference they learn that author's posts under other aliases,
        and which items the author voted on. This defeats "aliases never link
        posts across rooms" and could give away a stranger's route.
   - Instead the server records the post's alias in its room (a Spot on one
     Kolkata day) in `spot_hidden_alias` for the blocker. The activity read then
     leaves out that alias's posts in that room, before the 10-post limit. The
     alias already identifies the same author in the room, so nothing new is
     revealed.
   - Votes, signal summaries, highlights and the author's posts in other rooms
     are unaffected. The traveller blocks again where needed.
   - Hidden-alias rows go once their room can hold no readable post (3 days after
     yesterday). They cascade with the blocker's account and never store the
     author's account.
   - The server also records the account-level block edge through a new
     idempotent `DurableBlockPolicyService.ensureBlocked`:
     - It runs under the existing account pair lock, and repeating it does not
       change the revision.
     - Its limit of 100 edges applies; a full list returns 409, and nothing is
       hidden.
     - Later features (Ask Ahead recipient selection, chat) use this edge.
     - A disabled author gets no edge but the same 204, so nothing is revealed.
   - The response never names the author, and the author is not told.
   - Rate limit: 10 blocks per account per minute.
   - Hiding more than one room needs a separate privacy review.
7. **No unblock in the pilot.** The pilot UI offers no unblock. The policy
   service keeps its revision-checked `unblock` for later work (a settings list).
   Until then an unblock is done on request by an operator.

## Consequences

- Step 4 reads its queue from `spot_report_group`, one row per incident. It must
  add hide and restore, and an "upheld" state that lifts the highlight exclusion
  for restored posts.
- `live_block_edge`, named for the archived LIVE capability, is now also the
  account-level block store. The name is kept to avoid migrating an applied table.
- A traveller who keeps meeting the same person must block in each room. This
  is the price of not linking aliases.
