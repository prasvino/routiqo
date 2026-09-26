# ADR 0075: Spots moderation, server (step 4a)

Date: 2026-09-26
Status: accepted; implemented default-off (step 4a). Implements the server part of
[ADR 0069](0069-pilot-moderation-access-and-alerting.md) and
[PILOT_MODERATION_SPEC.md](../features/spots/PILOT_MODERATION_SPEC.md). Step 4b
adds the admin UI, the urgent-report webhook, metrics and the Android
"Hidden by a moderator" label.

## Context

Report and Block exist on the server ([ADR 0072](0072-spot-report-and-block.md)),
but nobody could act on a report. The admin plumbing built for the archived V3
traffic summaries was reused:
- Google sign-in on a separate audience and origin;
- `admin_auth_session`;
- `moderation_operator_grant`;
- the CSRF and origin guard;
- the audited contribution restriction.

For a small rota over a multi-day festival window it had four gaps (ADR 0069):
- 15-minute sessions with no renewal;
- 4-hour grants;
- sign-in limited to `traffic_*` holders;
- no Spots queue.

## Decision

1. **Flags** (exact `true`, default off):
   - `ROUTIQO_ADMIN_ENABLED` owns the admin base (sign-in, sessions, the
     `/api/v1/admin/**` chain).
   - The archived V3 paths now need it and their own V3 flags.
   - `/api/v1/admin/spots/**` needs `ROUTIQO_SPOTS_ADMIN_ENABLED` plus the Spots API
     and contribution flags.
   - `/api/v1/admin/spot-grants/**` needs `ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED`.
   - Paths without their flag are denied by the chain before any handler.
   - Admin retention maintenance runs with the admin flag.
2. **Permissions and sign-in:** `spots_review`, `spots_hide`, `spots_restrict`,
   `spots_alias_lookup` and `spots_grant_admin` join the grant table, under the
   same 24-hour database cap. Any current Spots or V3 grant signs an enabled
   account in; each action checks its own permission.
3. **Sessions:**
   - They last 15 minutes. `POST /auth/session/renew` (CSRF) extends one to 15
     minutes from now, capped at `absolute_expires_at`, which is 8 hours from sign-in.
   - The database enforces the cap with a CHECK.
   - Every renewal rechecks that the session is unrevoked and unexpired and that the
     account is enabled with a current grant.
   - The session read returns `absoluteExpiresAt` for the 4b warning.
4. **Shift grants:**
   - A current `spots_grant_admin` issues or revokes the four queue permissions for
     60–720 minutes, with reasons `shift_start`, `coverage_change`,
     `security_response` or `error_correction`.
   - Nobody grants themselves, and `spots_grant_admin` is never issued by the API;
     the two grant admins are created out of band.
   - Unlike V3, a grant admin may receive queue permissions from the other.
   - A live grant must be revoked first, and an exact retry replays without
     extending it.
   - Every issue, revoke and review is audited for 30 days.
5. **Queue:**
   - `GET /spots/reports?cursor=` (`spots_review`) returns open report groups, 20
     per page.
   - Urgent groups come first: any `unsafe`, `abuse` or `personal_data` report. The
     queue index is new; ADR 0072's index counted only `unsafe` and `abuse`.
   - Within a tier the newest report comes first, on a keyset cursor.
   - Rows carry:
     - the Spot's English and Tamil names;
     - for a post: its text, type and alias; for a summary: its category and value;
     - times and state;
     - per-reason counts and vote counts;
     - `openSince`.
   - Rows never carry a reporter, author or account identifier, or a position.
   - Groups whose evidence is gone close as `CLOSED_EVIDENCE_UNAVAILABLE`.
   - Each page read is audited (count only, 30 days).
6. **Decisions:**
   - The endpoint is `POST /spots/reports/{reportRef}/…` with
     `{requestId, reason, reportVersion}`. It is addressed by the report group, not the
     spec's `items/{ref}`, because one summary can have several incidents.
   - `reportVersion` is the queue item's latest report sequence. If a newer report
     has arrived, the decision is 409 and the moderator refreshes, so no report is
     ever closed unseen.
   - An exact `requestId` replays; a changed retry is 409. Identical double taps
     serialise on the operator's account lock and replay.
   - Order inside the transaction:
     1. the operator's account lock;
     2. the grant (`FOR SHARE`, held to commit) and the session recheck, before any
        replay or existence answer;
     3. item, then group. Clear-signals first takes a per-Spot advisory lock, so two
        clears never deadlock.
   - 10 decisions per minute per operator, audited 30 days.

   | Action | Permission | Effect |
   |---|---|---|
   | dismiss | `spots_review` | Closes the group and rules its reports not upheld |
   | hide | `spots_hide` | Post: hidden and its highlight deleted. Summary: the incident's still-active signals are hidden. The reports are upheld |
   | restore | `spots_hide` | Clears the hide and rules the reports not upheld. An expired item stays expired |
   | clear-signals | `spots_hide` | Summary only: ends every current signal for that Spot and category |

   A group reopens when a new report arrives.
7. **Hide is a column, not a state:**
   - `moderation_hidden_at` on posts and signals keeps the `ended_at` invariant,
     expiry and purges unchanged.
   - Hidden items vanish from every activity read at once. Only their author sees
     their own post, with `hidden: true` (a new, additive, required field in
     `NativeSpotPost`; the shared parser defaults it to `false` for older servers).
   - Hidden items can't be voted on, reported or blocked. The author can still
     delete their own.
8. **Highlights and not-upheld accounting:**
   - A hidden post, or one with evidence awaiting a decision, never becomes a
     highlight.
   - Dismiss and restore mark the evidence not upheld and put an unexpired place
     post back into promotion. A later report blocks it again.
   - Reporters are counted once per report ruled not upheld.
     `not_upheld_through` stops a later ruling from counting reports twice, or
     counting reports a hide upheld.
9. **Author lookup:**
   - `POST /spots/reports/{reportRef}/author` needs `spots_alias_lookup` and a
     reason, and returns up to 20 authors.
   - Each author has:
     - an opaque 43-character reference, stored as a SHA-256 hash, valid for 30
       minutes and usable only by that operator;
     - account age, completed-journey count, restriction state and revision;
     - reports ruled not upheld in 30 days, flagged at 3.
   - It is audited. An exact retry within 5 minutes (a lost response) issues fresh
     references without a second audit row; after that it is 409, so one audit row
     never covers a later reveal.
   - A lookup of reporters is deferred. Leads see an author's own not-upheld
     count, but can't yet look up a flagged reporter to restrict them.
10. **Restrict and restore:**
    - The endpoint is `POST /spots/accounts/restrict|restore` (`spots_restrict`)
      with `{accountRef, requestId, expectedRevision, reason}`.
    - The reference travels in the body, so it never appears in a URL, a log or
      browser history.
    - It runs through the existing audited restriction owner (ADR 0039/0041:
      exact revision, 20 per hour, 30-day audit), which now accepts `spots_restrict`
      for both actions.
    - The admin session and the reference are rechecked inside the restriction's
      own transaction.
    - A refused change is 409.
11. **Module boundaries:**
    - The spot module owns the queue, the decisions and their tables.
    - It checks grants through the `moderation.application.OperatorGrantAuthority`
      port and reads lookup context through `ModerationAccountFacts`, never moderation
      tables. `ModeratorRestrictionService` wraps the audited restriction service.
    - The ArchUnit boundaries are unchanged and pass.

## Consequences

- A compromised moderator session can last up to 8 hours instead of 15 minutes.
  This is mitigated by the idle timeout, the grant checks on every renewal and
  action, shift-bounded grants, and Google 2-Step Verification at onboarding
  (which the backend cannot verify).
- **Retention:**
  - moderation records (decisions, read audit, reporter outcomes): 30 days;
  - lookup references: 30 minutes;
  - the Spots grant audit: 30 days.
  Spot maintenance and admin maintenance purge them in bounded, `SKIP LOCKED`
  batches.
- **V3 compatibility:** V3 admin paths now also need `ROUTIQO_ADMIN_ENABLED`.
  Archived behaviour is otherwise unchanged.
- **The summary incident is approximate:** its evidence signals are those created
  between its window and its latest report, and received by then. That is exact for
  a single incident; overlapping incidents on one summary can share signals.
- **Authors of hidden signals:** a hidden signal disappears for its author too;
  only posts carry `hidden`.
- **Retention jobs:** moderation retention runs both with Spots moderation on and
  in Spot maintenance.
- **Queue index:** V32 replaces ADR 0072's queue index with the open-queue index.

## Verification

- **HTTP tests** with real PostgreSQL:
  - `AdminAccessHttpTest`: each Spots permission alone signs in; no grant, an
    expired grant or a revoked session is denied; renewal to and past 8 hours;
    grant rechecks; the database cap; closed feature paths; maintenance.
  - `AdminFlagOffHttpTest`: nothing answers without the exact admin flag.
  - `AdminSpotGrantHttpTest`: two grant admins; no self-grant; no
    `spots_grant_admin` issue; 60–720 minute bounds; live-grant conflict and
    replay; strict JSON under CSRF.
  - `AdminSpotModerationHttpTest`:
    - queue order and field set;
    - hide, restore and dismiss with activity effects;
    - summary hide and clear;
    - reopen and evidence-unavailable closure;
    - permissions, replay, conflict and rate limits;
    - lookup, references, restrict and restore.
- **Persistence and wiring tests:**
  - `SpotReportPersistenceTest` covers highlight rulings.
  - The native Spot tests cover `hidden`.
  - The V3 admin tests pass with the new flag.
- **Independent review:** no blockers. Fixed before merge:
  - decisions blind to newer reports (now `reportVersion`);
  - lookup replay re-identification (5-minute window);
  - replays skipping the grant check;
  - restriction session recheck in one transaction;
  - a clear-signals deadlock;
  - incident membership by receipt time;
  - reports racing a hide (row locks);
  - DST-safe grant-audit CHECKs.
- **Not verified:**
  - real moderator accounts and Google sign-in;
  - the admin UI (4b);
  - the 2-minute phone hide drill before the dry run.
