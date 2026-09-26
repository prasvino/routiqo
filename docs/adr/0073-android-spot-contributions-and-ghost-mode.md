# ADR 0073: Spot contributions on Android: offline queue and Ghost Mode

Date: 2026-09-26
Status: accepted for the Diwali 2026 dry run; implemented default-off (step 3c) with
[POSTS_AND_SIGNALS_SPEC.md](../features/spots/POSTS_AND_SIGNALS_SPEC.md)

## Context

The server accepts signals, posts, votes, delete, reports and blocks on Spots.
These are ADR 0071 and ADR 0072, and they are default-off. Step 3c adds the
Android side:
- the controls;
- an offline queue for contributions made without signal;
- Ghost Mode.

PRODUCT.md requires two things of Ghost Mode:
- it stops **all** sending;
- it takes priority over reconnect and outbox replay.

It also requires that sign-out and account deletion clear queued social items.

## Decision

1. **Separate client flag.**
   - `EXPO_PUBLIC_ROUTIQO_SPOT_CONTRIBUTIONS_ENABLED` must be exactly `true`, on
     top of `EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED`.
   - With it off, the app creates, reads and sends nothing new. The Spot detail
     then shows content without controls.
2. **Only signals and posts are queued.**
   - They go to `spot_outbox_v1`, one row per account, separate from the journey
     outbox, so neither waits behind the other.
   - The queue holds at most 20 entries and 32 KiB. A new signal replaces a
     queued one for the same Spot and category.
   - Entries are sent strictly in order, one at a time. Sending needs all of:
     - online;
     - in the foreground;
     - signed in as the queue's account;
     - Ghost Mode off;
     - the entry's journey known on the server.
   - Retries reuse the `clientKey`, so they are exact replays.
   - Outcomes:
     - an accepted send removes the entry;
     - a refusal removes it with one message;
     - a failure backs off (60 s on 429);
     - a 401 pauses the queue until the next sign-in.
   - Entries past their base life on the device clock are dropped unsent.
   - Waiting items are shown as "Waiting to send".
   - Votes, delete, report and block are online-only single requests:
     - a report reuses its `requestId` for the same item and reason until it is
       accepted or refused;
     - none of them is queued.
3. **Ghost Mode is device-wide and stored on the phone** (`ghost_mode_v1`).
   - It stays on across restart, sign-out and account switches. This is the
     safer default: a new account on the same phone does not start sending.
   - An unreadable state counts as on.
   - Turning it on:
     1. marks it on in memory;
     2. aborts the send in flight;
     3. in one SQLite transaction, stores the flag and deletes every queued
        contribution.
   - While it is on, storage refuses new queue entries, and every sending
     control is disabled.
   - "Delete my post" stays available, because it only removes the traveller's
     own content.
   - Reading Spots and journey commands are unaffected.
   - A request the server already accepted cannot be recalled. The switch's text
     does not claim otherwise.
4. **Clearing.**
   - Sign-out clears the leaving account's queue.
   - Sign-in and restore clear every other account's queue.
   - Account deletion clears it inside the existing partition clean-up.
   - "Clear local data" clears all queues.
5. **Client text checks mirror `PostText.java`**: the same trim, length, character
   classes and contact pattern, with the same test vectors. The author sees the
   reason before anything is queued; the server stays authoritative.

## Consequences

- The Kotlin transport accepts the six Spot write paths. It is checked only
  statically until an Android build runs; see NATIVE_ANDROID_PENDING.md.
- The native driver does not pass response headers up, so the queue uses a
  fixed 60 s wait on 429. The server's report `Retry-After` is not read. A
  report over the limit says "You have reached the report limit for now."
- Later Ghost Mode scope (Spot passage, Ask Ahead) plugs into the same switch.
