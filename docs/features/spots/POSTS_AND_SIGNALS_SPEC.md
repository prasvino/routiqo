# Posts and signals on Spots

Status: proposed, 2026-09-25. Not implemented. Phase 2 of the pilot path; the
parts marked **Diwali** are needed for the 5–7 November 2026 dry run. Product
rules: [PRODUCT.md](../../PRODUCT.md) (Spots, lifespan table, Offline rule,
Guardrails, Decisions §3–4). Catalog, activity read and refresh:
[SPOTS_SPEC.md](SPOTS_SPEC.md).

Scope (**Diwali**): public one-tap signals and short text posts on a Spot,
per-room aliases, per-type lifetimes, "Still true?" and "No longer true",
highlights, delete my post, Report and Block, moderator hide, rate limits,
Ghost Mode for contributions, the offline queue and account deletion. Not in
scope: voice notes, Spot chat and the festival room, Ask Ahead, post-passing
prompts, photos, web.

## What exists and what is reused

Private Quick Signals (ADRs 0024–0047) are built and stay archived, default-off.
Their tables carry consent generations, a 15-minute maximum, server-issued
90-second grants and a required route context, which do not fit public,
offline-capable Spot content. This spec therefore adds **new tables** and reuses
the proven patterns, not the archived tables:

- exact-fingerprint idempotency with replay and conflict (ADR 0024);
- account-first transaction order and a server-time recheck after locks
  (ADR 0025);
- one current signal per account, Spot and category via a partial unique index;
- fixed-minute and rolling-ledger budgets (ADR 0037);
- `SKIP LOCKED` expiry maintenance on its own flag (ADR 0036);
- restriction checks with revision fencing (ADR 0039), block edges (ADR 0040),
  audited operator actions (ADR 0041);
- the report protocol: opaque references, receipt-first retry, 7/30-day
  retention split, 10 reports per 24 h, severity-first queue (ADR 0064);
- the display-label and journal text validators for text rules.

## Signals

| Category | Values | Spot kinds (default) | Base life | Max with "Still true?" |
| --- | --- | --- | --- | --- |
| `traffic` | `moving`, `slow`, `stopped` | toll, junction, rest_area | 60 min | 2 h |
| `queue` | `under_5`, `5_to_15`, `15_to_30`, `over_30` (minutes) | toll, bus_stand, fuel, temple | 60 min | 2 h |
| `food` | `good`, `avoid` | eatery | 24 h | 36 h |
| `fuel` | `available`, `long_queue`, `none` | fuel | 24 h | 36 h |
| `restroom` | `usable`, `busy`, `avoid` | restroom, eatery, fuel, rest_area | 24 h | 36 h |

- A signal is allowed only for a category listed on the Spot in the current
  catalog.
- One current signal per account, Spot and category. A new signal replaces the
  author's previous one in that slot; replacing does not add a report.
- Signals are shown **as summaries, not attributed**: per category, the most
  reported current value, report counts per value and the latest time
  ("Slow · 3 reports in 20 min"; conflicting values are both shown:
  "Slow · 2, Moving · 1"). No alias, account or individual time is shown for a
  signal.

## Posts

- Short text on a Spot: 1–200 Unicode code points after trimming, one paragraph,
  letters, marks, numbers, punctuation, symbols, spaces and emoji; no control
  characters. Tamil, English and Tanglish are all accepted.
- A post has a type that sets its life: `traffic` (traffic or incident, 90 min /
  3 h) or `place` (food, fuel, restroom and general tips, 24 h / 36 h). The app
  picks the type from the Spot kind and lets the author switch it.
- **Business spam friction:** posts containing a URL, e-mail address or a phone
  number pattern (7 or more digits, allowing spaces and dashes) are rejected
  with "Links and phone numbers aren't allowed in posts."
- Posts are shown with the author's per-room alias, the capture time ("12 min
  ago"), the "Still true?" count and the viewer's own vote, newest first, at most
  10 per Spot in an activity response.
- **Delete my post:** the author can delete their own post at any time. It
  disappears from reads immediately; the row is kept only as long as a report or
  moderation record needs it.

## Aliases and rooms

- For posts, a **room** is a Spot on one calendar day (Asia/Kolkata). The
  festival room, when built, is its own room.
- The server assigns an alias the first time an account posts in a room: a
  random adjective and noun from the alias word list (for example "Blue Auto"),
  never derived from the account ID.
  If the pair is taken in that room, a random number from 2 to 99 is added. The
  alias is stable for that room and deleted when the room's last post has
  expired and no report or moderation record needs it.
- **Alias word list:** a versioned file in the repository, about 100
  adjectives and 100 nouns (about 10,000 pairs), with travel-friendly nouns
  (Auto, Bus, Lorry, Mango, Kite). The product owner approves it and a Tamil
  speaker reviews every change, which goes through a pull request. It excludes
  words that are offensive or insulting in English, Tamil or Tanglish (including
  animals used as insults, such as donkey or monkey); anything about caste,
  religion, gender, bodies or politics; and colours with political or religious
  meaning in Tamil Nadu (such as saffron, black, or red-and-black pairings).
  Neutral colours and nature words are preferred.
- Aliases are stored as `(room, account) → alias` on the server only. Other users
  see only the alias; they cannot link aliases across rooms. Moderators can look
  up the account behind an alias; every lookup is audited with a reason.

## "Still true?" and "No longer true"

- Available on posts and on signal summaries (a vote on a summary confirms or
  disputes its most reported value). Authors cannot vote on their own post or on
  a summary that only they contributed to.
- One vote per account per item; a later vote replaces the earlier one.
- **Still true:** extends the item's expiry to
  `max(current expiry, min(vote time + base life / 2, created + maximum life))`.
  Vote times are server times.
- **No longer true:** when two different accounts have voted "No longer true" and
  none of them is the author, the item expires immediately. A later "Still true"
  does not revive it.
- Votes appear as counts only ("Still true · 3"); voters are never shown.

## Lifetimes and expiry

- All expiry is computed with server time. Client clocks and retries never
  extend anything.
- Capture time: offline items carry `capturedAt`. The server uses
  `effectiveCreated = min(capturedAt, receivedAt)` and rejects `capturedAt`
  more than 2 minutes after `receivedAt`. Expiry is `effectiveCreated + base
  life`, so a false early capture time only shortens an item's life. Items whose
  life has already passed on arrival are rejected with a specific status the app
  shows as "Too old to post; it wasn't sent."
- Expired, deleted and hidden items are never returned by activity reads.
- **Highlights:** when a `place` post expires with at least 2 "Still true" votes
  and no upheld report, its text becomes a highlight for that Spot, without the
  alias ("Traveller tip · 2 days ago"). At most 3 per Spot (most votes, then
  newest), kept 30 days. Signals and `traffic` posts never become highlights.
- Expiry maintenance purges expired signals, posts, votes and aliases in bounded
  batches, keeping rows that an open report or moderation action still needs
  within the ADR 0064 retention.

## Contribution rules

- Contributions need a signed-in account, the Spots flags on, Ghost Mode off, no
  active restriction, and an owned journey that was active at `capturedAt`
  (checked against the journey's server start and completion times). The server
  does not check where the traveller is: Spot content is tied to the Spot, not
  to the poster's position.
- **Idempotency:** the app creates a random UUID `clientKey` per contribution.
  The server keeps `(account, clientKey)` with a fingerprint of kind, Spot,
  category or text, value and `capturedAt`. An exact replay returns the original
  receipt; a changed replay is a 409; a replay after deletion returns the
  deleted receipt.
- **Rate limits** (per account, in the database, same transaction as the write):

  | Action | Limit | New accounts (first 24 h) |
  | --- | --- | --- |
  | Signals | 20 per rolling hour; 60 s cooldown per Spot and category | 10 per hour |
  | Posts | 5 per 10 minutes, 20 per day | 2 per 10 minutes, 5 per day |
  | Votes | 60 per hour | 30 per hour |
  | Reports | 10 per 24 h (ADR 0064) | same |

  Exact replays never charge; deletions never refund. Limits are pilot defaults
  to recheck against dry-run density.

## Report, Block and moderator hide

- **Report** on every post and signal summary, with reasons: `false_alarm`
  (inaccurate), `abuse` (harassment or hate, in any language), `spam`
  (including business promotion), `personal_data`, `unsafe`. Reuses the ADR 0064
  mechanics: the reportable identity is the item's opaque random reference,
  receipt-first retry, per-reporter de-duplication, 10 per 24 h, 7/30-day
  retention, `unsafe` and `abuse` first in the queue. Report counts are not
  shown publicly.
- **Collapse pending review (Pongal only;
  [ADR 0068](../../adr/0068-report-collapse-pending-review.md)):** a post is
  hidden pending review once 3 different accounts report it as `unsafe`,
  `abuse` or `personal_data`, counting only accounts at least 7 days old with at
  least one completed journey. The author sees "Hidden pending review". A
  moderator confirms (hide) or restores it; restoring records the reports as not
  upheld, and accounts with repeated not-upheld reports are restricted. Posts
  only; signals rely on "No longer true". Flag
  `ROUTIQO_SPOTS_REPORT_COLLAPSE_ENABLED`, off for the Diwali dry run, where the
  on-call rota handles reports.
- **Block** from any post: the server maps the post to its author and records an
  account-level block edge (ADR 0040) without revealing the account. Activity
  reads then omit that author's posts and votes for the blocker everywhere.
  Blocked authors are not told.
- **Moderator hide:** a new audited permission `content_hide` on the admin app
  (ADR 0041 pattern; time-boxed grants per the
  [pilot moderation runbook](../../development/PILOT_MODERATION_RUNBOOK.md)).
  Hiding removes an item from all reads at once with a reason
  (`abuse`, `spam`, `false_alarm`, `personal_data`, `unsafe`); the author sees
  "Hidden by a moderator". Moderators can also restrict an account (ADR 0039).
  The moderator queue shows the item text, Spot, times, report reasons and the
  alias; the account is shown only through an audited lookup.

## Ghost Mode

- A switch in Profile and in Journey mode. While on, the app sends no signals,
  posts, votes or reports, disables contribution controls with "Ghost Mode is
  on", and clears the pending contribution queue. Reading Spots still works.
- Turning Ghost Mode on is local and immediate; nothing is sent. Later specs
  extend it to Spot passage and Ask Ahead.

## Offline queue

- Contributions go to a separate device queue (`spot_outbox_v1`,
  account-partitioned), not the journey outbox, so they never wait behind a
  blocked journey command and journey commands never wait behind them.
- Entry: `clientKey`, kind, Spot ID, category and value or text and type,
  `capturedAt` (device time), journey ID. At most 20 entries and 32 KiB.
- Sent in order when online and in the foreground, with backoff; a 409 conflict,
  a "too old" rejection or a permanent 4xx removes the entry and tells the
  author once. Entries whose base life has passed on the device clock are
  dropped without sending.
- Cleared on Ghost Mode, sign-out, account change and account deletion. Queued
  items are shown to the author as "Waiting to send", never as posted.

## Endpoints (native, bearer and account header, flag `ROUTIQO_SPOTS_API_ENABLED`)

| Method and path | Body | Result |
| --- | --- | --- |
| `POST /api/v1/native/spots/signals` | `clientKey`, `spotId`, `category`, `value`, `capturedAt`, `journeyId` | receipt: `ref`, `status`, `expiresAt` |
| `POST /api/v1/native/spots/posts` | `clientKey`, `spotId`, `type`, `text`, `capturedAt`, `journeyId` | receipt with `ref`, `alias`, `expiresAt` |
| `POST /api/v1/native/spots/items/{ref}/vote` | `still_true` or `no_longer_true` | item counts and new `expiresAt` |
| `POST /api/v1/native/spots/items/{ref}/delete` | `{}` | deleted receipt |
| `POST /api/v1/native/spots/items/{ref}/reports` | `requestId`, `reason` | 202 with minimized receipt (ADR 0064) |
| `POST /api/v1/native/spots/items/{ref}/block-author` | `{}` | 204 |

- Posts and signal summaries in the activity response (Spots spec) carry the
  opaque `ref`, never account IDs. Generic empty-body errors as in existing
  native endpoints; 429 with `Retry-After`.
- Admin endpoints for the queue, hide, restrict and alias lookup follow the
  existing admin session and grant model.

## Storage (new migration)

- `spot_signal`, `spot_post`: account, Spot ID, catalog version, journey,
  `client_key`, fingerprint, value or text and type, `captured_at`,
  `received_at`, `expires_at`, `max_expires_at`, state (`active`, `deleted`,
  `hidden`, `expired_early`), opaque `ref`. Partial unique index for one active
  signal per account, Spot and category.
- `spot_alias` (room, account, alias; unique alias per room), `spot_vote`
  (item, account, kind, time; one per account per item), `spot_highlight`,
  report tables following ADR 0064, a `content_hide` audit table and the budget
  ledgers.
- Every table cascades on account deletion; indexes support activity reads by
  Spot and expiry purges. Posts and signals never store coordinates.

## Account deletion

Deleting an account removes its signals, posts, votes, aliases, highlights made
from its posts and pending device queue. Reports it made follow ADR 0064
retention without the reporter identity. The deletion spec's cascade list is
updated when this is built.

## Acceptance and evidence

- **Domain:** lifetimes and caps per type; "Still true" formula at the cap;
  two-account early expiry, author exclusion and no revival; capture-time rules
  including future capture, early capture and too-old rejection; highlight
  selection and 30-day expiry; text validation including Tamil text, emoji,
  links, e-mail and phone patterns.
- **Storage (PostgreSQL):** idempotent replay and conflict, one active signal per
  slot under concurrency, rate limits and new-account tiers, deletion cascade,
  expiry maintenance with open reports.
- **Privacy:** activity responses and logs contain no account IDs, Spot-ID lists,
  coordinates or alias-to-account mappings (response field-set and log capture
  tests); alias uniqueness per room and no cross-room reuse by construction.
- **Moderation:** report reasons and queue order, hide removes from reads,
  audited alias lookup, block omits the author's content for the blocker.
- **Collapse (Pongal):** exactly 3 qualifying reports collapse a post; reports
  from accounts under 7 days old or without a completed journey, duplicates and
  `false_alarm` or `spam` reasons do not count; restore and not-upheld tracking;
  flag off keeps ADR 0064 behaviour.
- **Alias word list:** every pair is on the approved list; the list file is
  versioned and a test rejects entries outside it.
- **App:** contribution controls per Spot category, Ghost Mode clears and blocks
  the queue, offline queue behaviour and messages, "Waiting to send",
  "Too old to post", large text and screen reader labels.
- **Dry-run readiness:** the moderator rota can see and hide a test post within
  2 minutes of it being reported, recorded before Diwali.

## Open questions

- None open. Resolved 2026-09-25: collapse pending review for Pongal only
  (ADR 0068); four queue bands (under 5, 5–15, 15–30, over 30 minutes); the
  alias word list is owned by the product owner with Tamil-speaker review.
