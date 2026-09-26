# ADR 0071: Spot contributions: storage, lifetimes and flags

Date: 2026-09-26
Status: accepted for the Diwali 2026 dry run; implemented default-off (step 3a) with
[POSTS_AND_SIGNALS_SPEC.md](../features/spots/POSTS_AND_SIGNALS_SPEC.md)

## Context

Step 3 adds public one-tap signals and short posts on Spots. The spec defines the
behaviour but leaves a few implementation choices open:
- how the rules stay default-off;
- how a vote on a signal *summary* works;
- which statuses mark the new refusals;
- how activity stays within its 128 KiB cap once it carries posts;
- where budgets live.

The owner split the step into three PRs:
- 3a: server writes, votes, aliases, lifetimes, expiry;
- 3b: Report and Block;
- 3c: Android.

## Decision

1. **Separate write flag.**
   - `ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED` (exact `true`) enables the write
     endpoints, and only together with `ROUTIQO_SPOTS_API_ENABLED`. Reads can
     reach testers before writes.
   - Physical expiry has its own flag, `ROUTIQO_SPOTS_MAINTENANCE_ENABLED`.
2. **New tables in migration V30**: `spot_signal`, `spot_post`,
   `spot_signal_group`, `spot_contribution_key`, `spot_vote`, `spot_alias`,
   `spot_highlight` and `spot_contribution_ledger`.
   - Every account-owned row cascades on account deletion.
   - No coordinates are stored.
   - The archived private Quick Signal tables are untouched.
3. **Summary refs.**
   - Each (Spot, category, value) has one stable opaque ref in
     `spot_signal_group`.
   - A "Still true" vote on a summary extends every current signal in that group,
     using the spec's formula for each signal.
   - "No longer true" from two accounts expires the group's signals at once, with
     no revival. Accounts that have a current signal in the group count as its
     authors, so they cannot be either of the two.
   - Votes count only while cast after the group's oldest current signal, so old
     votes never carry over to new signals.
4. **Writes** run inside `JourneyWriteAuthority.withOwnedJourney` (account row,
   then journey row; ADR 0025).
   - Idempotency comes first: an exact `clientKey` replay returns the stored
     receipt, a changed one returns 409.
   - Next the restriction check, then server time sampled once.
   - Then the capture rules, the journey-active-at-capture check, the rolling
     ledger budget, and the insert, all in one transaction. New accounts (under
     24 h, read through `identity.application.AccountAgeReader`) get the lower
     tier.
5. **Offline-started journeys.**
   - A journey started offline gets its server start time when the start command
     arrives.
   - Contributions captured up to 30 minutes before that server start are
     therefore accepted.
   - Captures after the journey's server completion are refused.
6. **Statuses** (empty bodies):
   - 404: unknown Spot, ref or journey, or a category the Spot doesn't allow.
   - 409: an idempotency conflict, a journey not active at capture, or no active
     journey for a vote.
   - 410: too old on arrival ("Too old to post; it wasn't sent.").
   - 422: links, e-mail addresses or phone numbers in a post.
   - 403: a restricted account, or a vote on one's own content.
   - 429 with `Retry-After`: rate limited.
7. **Activity within 128 KiB.**
   - Each Spot shows at most its 10 newest posts.
   - If the response would still exceed the cap, the oldest posts across the
     response are dropped and those Spots are marked `postsTruncated`.
   - Signal summaries always stay.
   - The Android transport cap and contract bound are unchanged.
8. **Aliases** come from a versioned word list,
   `backend/core-api/src/main/resources/spot/alias-words-v1.txt`. Version `1-draft`
   is written by the coding agent and must be approved by the product owner and
   reviewed by a Tamil speaker before the write flag is turned on anywhere.

## Consequences

- **Report and Block are not in 3a**, and neither are moderator hide or report
  retention. Until 3b, maintenance purges items 24 hours after they expire or end.
  3b must hold back rows that open reports still need, within the ADR 0064
  retention, and add "no upheld report" to highlight promotion.
- **The ledger stores which Spot and category an account signalled, for 25 hours**,
  because the per-slot cooldown needs it. It holds no position and no route.
- **The 30-minute offline-start tolerance slightly widens "journey active at
  capture"**, in exchange for not dropping honest offline contributions.
- **Summary votes are shared.** Every current signal with the same value is
  extended or expired together, which matches how the summary is shown.
- **An account's own earlier vote counts only inside the current window.** A vote
  cast before the group's current signals is treated as absent, so voting again
  takes effect.
- **Serialization.** Summary votes lock the group row `FOR NO KEY UPDATE`, which
  lets new signals reference it. First posts in a room are serialized with a
  transaction advisory lock on the room. Highlight promotion is serialized across
  replicas and considers each expired post once.
- **Deleting a post also deletes any highlight made from it.**
