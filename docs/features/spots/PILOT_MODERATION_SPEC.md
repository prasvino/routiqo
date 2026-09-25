# Pilot moderation

Status: proposed, 2026-09-25. Not implemented. Phase 2 of the pilot path; the
parts marked **Diwali** are needed for the 5–7 November 2026 dry run. Decision
record: [ADR 0069](../../adr/0069-pilot-moderation-access-and-alerting.md).
Content rules: [POSTS_AND_SIGNALS_SPEC.md](POSTS_AND_SIGNALS_SPEC.md). Operating
procedure: [PILOT_MODERATION_RUNBOOK.md](../../development/PILOT_MODERATION_RUNBOOK.md).

Scope (**Diwali**): the moderator queue for Spot posts and signal summaries,
moderator actions (dismiss, hide, restore, clear signals, restrict and restore an
account, audited alias lookup), moderator access and grants for a small named
rota, admin sessions long enough for a shift, alerting on urgent reports, audit
and retention, and the phone-friendly admin workflow. Later: voice notes, Spot
chat and the festival room, Ask Ahead and route guides join the same queue when
their specs land; report collapse pending review is Pongal only (ADR 0068).

## What exists and what changes

Built for the archived V3 traffic summaries, default-off, never run with real
operators ([ADR 0056](../../adr/0056-v3-moderator-staging-boundary.md),
[ADR 0057](../../adr/0057-v3-operator-grant-administration.md),
[ADR 0064](../../adr/0064-v1-reporting-protocol.md)):

| Piece | Keep | Change |
| --- | --- | --- |
| Admin app (`apps/admin`), separate origin and Google audience, challenge/nonce sign-in, CSRF, origin and fetch-metadata checks, hardened proxy | Yes | New Spots paths in the proxy allowlist; UI for Spot items |
| Admin session (`admin_auth_session`, 15 min) | Session model | Renewable within an 8 h absolute limit (below) |
| Session allowlist (current grant required) | Yes | Accept the new Spots permissions, not only `traffic_*` |
| Grants (`moderation_operator_grant`, exact-account, issue/revoke with audit) | Yes | New permissions; shift grants up to 12 h; two grant admins |
| Report intake and queue (ADR 0064: opaque refs, receipt-first retry, 10 per 24 h, 7/30-day retention, severity-first cursor, `CLOSED_EVIDENCE_UNAVAILABLE`) | Mechanics | New tables keyed by Spot item `ref`; five reasons |
| Restrictions (ADR 0039/0041, reasons, 20 actions per hour, audit) | Yes | An HTTP boundary and UI for leads |
| Blocks (ADR 0040) | Yes | Used by the user-facing block action in the posts spec |
| Alerts, metrics | None exist | Count-only urgent-report alert and moderation metrics |

The V3 tables, permissions and flags stay with the archived code, default-off.

## Roles and permissions

New grant permissions (added to the `moderation_operator_grant` check; the V3
ones stay):

| Permission | Allows | Who |
| --- | --- | --- |
| `spots_review` | Read the queue and item detail; dismiss reports | Every rota moderator |
| `spots_hide` | Hide and restore items; clear current signals | Every rota moderator |
| `spots_restrict` | Restrict and restore an account's contributions (ADR 0039 reasons) | Leads |
| `spots_alias_lookup` | Reveal the account behind an alias or signal contributor, audited | Leads |
| `spots_grant_admin` | Issue and revoke the permissions above for other accounts | Two grant admins |

- Admin sign-in succeeds only for an enabled Routiqo account holding at least
  one current Spots or V3 permission; a Google identity alone is never enough.
- **Two grant admins** (for example the owner and one lead), created out of band
  by a named database operator with the authorization recorded (as today). Each
  can grant the other queue permissions; nobody can grant themselves anything,
  and a grant admin cannot issue `spots_grant_admin`.
- **Shift grants:** the API issues grants of 1–12 hours (database limit stays
  24 h). Grant admins issue each moderator's grants at the start of their shift
  and revoke them at the end or when someone leaves. Issuing while a grant is
  live is still denied (revoke first); exact retries replay without extending.
- Grant reasons: `shift_start`, `coverage_change`, `security_response`,
  `error_correction`.
- **MFA:** Google-side only. Rota members must have Google 2-Step Verification
  on, checked at onboarding and recorded in the rota list; the backend cannot
  verify it from a consumer Google ID token.

## Admin session

- The session stays 15 minutes, and the admin app renews it while the moderator
  is active (`POST /api/v1/admin/auth/session/renew`, CSRF-protected), up to an
  **8-hour absolute limit** from sign-in. After that, or after 15 minutes of
  inactivity, the moderator signs in again.
- Renewal checks that the session is unrevoked and that the account still holds a
  current grant; a revoked or expired grant ends the session at the next request.
- Logout revokes the session; sessions and challenges are purged by the existing
  maintenance job, moved from the V3 flag to the admin flag.

## Queue

- `GET /api/v1/admin/spots/reports?cursor=` (page of 20; `spots_review`).
- Groups are per reported item. Order: **urgent first** — any `unsafe`, `abuse`
  or `personal_data` report, or a collapsed item (Pongal) — then `false_alarm`
  and `spam`; newest report first within a tier.
- Each row: Spot name (English and Tamil), item kind (post or signal summary),
  for posts the text, type and alias; for summaries the category and current
  value; capture and expiry times; state (active, hidden, collapsed, expired,
  deleted by author); counts per reason; "Still true" and "No longer true"
  counts; time since the first unhandled report.
- No reporter identity, account IDs, Google subjects or positions are shown.
  Report free text does not exist (reasons only).
- Items that expired or were deleted stay reviewable while ADR 0064 retention
  keeps their evidence; afterwards the group closes as
  `CLOSED_EVIDENCE_UNAVAILABLE`.
- Every page read is audited (operator, time, item count; 30 days).
- The admin app refreshes the queue every 30 s while visible, one request in
  flight, and shows the number of urgent items in the page title.

## Actions

All actions: exact `requestId`, idempotent replay, conflict on a changed retry,
lock order item → report group → grant, minimized audit kept 30 days, rate limit
10 per minute per operator.

| Action | Endpoint (`/api/v1/admin/spots/...`) | Permission | Effect |
| --- | --- | --- | --- |
| Dismiss | `reports/{ref}/dismiss` | `spots_review` | Closes the group; item unchanged |
| Hide | `items/{ref}/hide` | `spots_hide` | Removes the item from all reads at once; author sees "Hidden by a moderator"; reason `abuse`, `spam`, `false_alarm`, `personal_data`, `unsafe` |
| Restore | `items/{ref}/restore` | `spots_hide` | Undoes a hide or a collapse; reason `not_upheld` or `error_correction`; records the reports as not upheld |
| Clear signals | `signals/{ref}/clear` | `spots_hide` | Expires all current signals for that Spot and category (for coordinated false signals); reason `false_alarm` or `spam` |
| Look up author | `items/{ref}/author` | `spots_alias_lookup` | Returns an opaque account reference, account age, completed-journey count, restriction state and recent not-upheld report count; never e-mail, name or Google subject. For a summary, returns the contributing accounts (at most 20). Reason required. |
| Restrict / restore account | `accounts/{accountRef}/restrict` · `/restore` | `spots_restrict` | ADR 0039/0041 with reasons `spam_manipulation`, `harassment`, `unsafe_content` / `appeal_upheld`, `error_correction`; exact revision required |

- Account references returned by a lookup are opaque, per-lookup tokens valid for
  30 minutes, so account IDs never appear in the admin UI or browser history.
- Hidden items never become highlights. Restoring a hidden item that has since
  expired does not revive it.
- Reporters whose reports are marked not upheld three times in 30 days are
  flagged in the lookup result; a lead decides whether to restrict them.

## Urgent-report alert

- When an urgent report arrives and no alert was sent in the last 5 minutes, the
  backend posts a **count-only** message ("3 urgent Spot reports waiting") to one
  configured HTTPS webhook (`ROUTIQO_MODERATION_ALERT_WEBHOOK_URL`), such as the
  rota's private team chat. No item text, Spot, alias, account or link parameter
  beyond the admin app's base URL is included.
- Sent after the report commits, outside the database transaction, with a 5 s
  timeout and no retry storm (one retry); failures are logged as outcome codes
  and never affect report intake.
- The webhook URL is a secret (not in client bundles or logs). Flag
  `ROUTIQO_MODERATION_ALERT_ENABLED`, exact `true`, default off.

## Metrics

Count-only, no identifiers: queue depth by tier, time from first urgent report to
decision, decisions per action and reason, collapses and restores, webhook
failures. Exposed to operators through the admin app header and the
maintenance logs; no third-party analytics.

## Admin app workflow (phone-friendly)

- Designed for a 360 dp phone at night: rows stack; primary actions (Hide,
  Dismiss) are 48 px targets; one tap chooses a reason chip, a second tap on
  "Confirm hide" commits; no checkbox step.
- Item detail opens in place; Tamil and English text render correctly.
- Uncertain writes keep the existing "retry exact decision" recovery.
- Leads see Restrict and Look up author behind a separate "Lead actions" section.
- Session renewal is silent; the app warns 10 minutes before the 8-hour limit.
- The Google sign-in button fits narrow screens.

## Author and reporter experience (app side)

- Authors see "Hidden by a moderator" or "Hidden pending review" on their own
  item, with a short "Why?" link to the community rules and an appeal contact
  (a lead's e-mail address in Profile for the pilot).
- Reporters get a receipt ("Thanks, a moderator will review this"); they are not
  told the outcome in the pilot.
- Restricted accounts see "Posting is paused for your account" on contribution
  controls; reading still works.

## Service levels (targets, confirmed before Diwali)

- Urgent reports: median decision within 5 minutes during staffed hours, 15
  minutes at most.
- Other reports: within 2 hours.
- Dry-run readiness check: a moderator hides a test post within 2 minutes of it
  being reported, from a phone, recorded before 5 November.

## Flags

- Backend: `ROUTIQO_ADMIN_ENABLED` (admin origin, sign-in and sessions for any
  admin feature) and `ROUTIQO_SPOTS_ADMIN_ENABLED` (Spots queue and actions),
  `ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED`, `ROUTIQO_MODERATION_ALERT_ENABLED`. The
  V3 flags keep controlling only the archived V3 paths.
- Admin app: `ROUTIQO_ADMIN_ENABLED`, `ROUTIQO_SPOTS_ADMIN_ENABLED`,
  `ROUTIQO_SPOTS_GRANT_ADMIN_ENABLED` plus the existing origin and client ID
  settings. Exact `true` only, default off, fail-closed tests.

## Acceptance and evidence

- **Access:** sign-in with each permission alone; denial with none, with a
  consumer session, an expired grant and a revoked session; renewal up to and
  refusal after 8 hours; two grant admins granting each other, self-grant and
  `spots_grant_admin` issuance denied; 1–12 h bounds.
- **Queue:** tier order, cursor stability, evidence-unavailable closure, read
  audit, no identifiers in responses (field-set tests).
- **Actions:** each action's permission, effect on activity reads (hidden and
  cleared items disappear at once), replay and conflict, rate limits, audit rows;
  lookup tokens expire and never expose account IDs, e-mail or Google subject.
- **Alert:** count-only payload, 5-minute throttle, failure does not affect
  intake, secret not logged.
- **App:** phone layout at 360 dp and 200% text, reason-chip flow, uncertain
  write recovery, session warning, Tamil text rendering.
- **Evidence:** screenshots with synthetic items labelled as such; the
  2-minute hide drill recorded in the pending ledger before the dry run.

## Open questions

- Which chat channel receives the urgent-report alert (for example a private
  Telegram group or Slack channel)? The webhook is generic; the choice is
  operational.
- Rota size and shift pattern for 5–7 November (see the runbook).
