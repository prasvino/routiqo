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
   - Block targets posts only. Signals are unattributed, so blocking from a
     summary would tell the blocker that a particular account signalled.
   - Neither works on the viewer's own content (403). A summary counts as the
     viewer's own when every current signal in it is theirs.
   - An unknown, expired or deleted item returns 404.
3. **ADR 0064 mechanics in new tables (migration V31).** The V3
   `community_traffic_*` tables and code are not reused.
   - `spot_report` holds the reporter row, which cascades on account deletion.
     It is kept 7 days. Its primary key is (reporter, requestId), and it is
     unique on (reporter, item).
   - `spot_report_group` is reporter-free. It holds per-reason counts, `latest`
     and a review sequence, and it is kept 30 days after the latest report.
   - **Receipt first:** an exact replay returns the original receipt, even after
     the quota is spent or the item has expired. A changed replay returns 409, and
     so does a second report on the same item.
   - **Quota:** 10 reports per account per rolling 24 h, counted under the
     account row lock.
   - **Reasons:** `false_alarm`, `abuse`, `spam`, `personal_data`, `unsafe`.
   - **Queue order:** an index puts `unsafe` and `abuse` first for step 4.
   - Report counts are never public. The receipt is only
     `{receivedAt, receiptExpiresAt}`.
   - **Account deletion:** removes the reporter rows and deliberately keeps the
     group counts, as the spec says. There is no decrement trigger.
4. **Reported items are kept as evidence.**
   - **Posts:** maintenance does not purge a post while an unexpired report group
     names it.
   - **Signals:** maintenance does not purge a signal whose summary was reported
     after the signal was created.
   - Either way this lasts at most 30 days after the latest report.
   - Readers still hide expired items, so nothing reappears.
   - **Highlights:** a reported post never becomes one. Until moderation (step 4)
     can decide that a report is not upheld, any report blocks promotion.
5. **Block.**
   - The server maps the post to its author and calls a new idempotent
     `DurableBlockPolicyService.ensureBlocked`. It runs under the existing account
     pair lock, and repeating it does not change the revision.
   - The ADR 0040 limit of 100 edges applies; a full list returns 409.
   - The response never names the author, and the author is not told.
   - The activity read gets the viewer's blocked accounts through a new read
     port, `moderation.application.BlockedAccountsReader`. The query then leaves
     out those authors' posts, before the 10-post limit, and their votes. This
     applies to the blocker only.
   - Signal summaries stay because they are unattributed.
   - Rate limit: 10 blocks per account per minute.
   - Block edges cascade when either account is deleted.
   - Blocking will also apply to Ask Ahead recipient selection when that exists.
6. **No unblock in the pilot.** The pilot UI offers no unblock. The policy
   service keeps its revision-checked `unblock` for later work (a settings list).
   Until then an unblock is done on request by an operator.

## Consequences

- Step 4 reads its queue from `spot_report_group`. It must add hide and restore,
  and an "upheld" state that lifts the highlight exclusion for restored posts.
- `live_block_edge`, named for the archived LIVE capability, is now also the
  Spot block store. The name is kept to avoid migrating an applied table.
- A blocked author's votes stop counting for the blocker. As a result the
  blocker and other viewers can see different "Still true" counts.
