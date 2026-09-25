# Pilot moderation runbook

> **Stub, pending Phase 2 specs.** This outlines how moderation should work for
> the Pongal pilot so that the Phase 2 specs (posts and signals, voice notes,
> aliases and rooms, Ask Ahead) and the report-intake work can fill it in. Nothing
> here is implemented unless [`BUILD_STATUS.md`](../quality/BUILD_STATUS.md) says
> so. Product rules come from [`PRODUCT.md`](../PRODUCT.md).

## Scope

Moderation covers user content on the corridor:

- Spot posts (short text) and one-tap signals, including "Still true?" abuse;
- voice notes;
- Spot chat and the festival route room;
- Ask Ahead questions and answers;
- route guides, once published (Phase 3).

Every item has **Report** and **Block**. Moderators can **hide** content quickly.
Authors can delete their own content; account deletion removes it.

## What exists and is reused

All of this is built behind default-off flags and was never run with real
operators. It was built for V3 traffic summaries and must be re-scoped to content.

| Piece | Source | Pilot use |
|---|---|---|
| Admin app sign-in | `apps/admin`, ADR 0056: separate admin origin, separate Google OAuth audience, separate sessions; admin sign-in never creates an account | Reuse as the moderator login |
| Moderator queue | ADR 0056: bounded queue, audited dismissal/suppression, cleanup | Re-scope from traffic summaries to posts, voice notes, chat and Ask Ahead |
| Audited restrictions | ADRs 0039/0041: finite action-specific grants, exact revisions, atomic minimized audit, per-operator debit | Restrict an abusive account's contributions |
| Blocks | ADR 0040: durable directed blocks, ordered account-pair locks | Extend to Spot content, chat delivery, room subscription, Ask Ahead recipient selection and replay |
| Grants | ADRs 0056/0057: finite, exact-account, short-lived grants; out-of-band root; issue/revoke audit | Simplified (below) |

## Still to build (Phase 2)

Specified in [PILOT_MODERATION_SPEC.md](../features/spots/PILOT_MODERATION_SPEC.md)
(ADR 0069) for Spot posts and signals: report intake and queue keyed by Spot item
reference, hide, restore, clear signals, restrict, audited alias lookup,
post-expiry evidence under ADR 0064 retention, renewable admin sessions, shift
grants and the urgent-report alert. Still to specify with their features: voice
notes (playback through short-lived signed URLs, never public links), Spot chat,
the festival room and Ask Ahead.

## Report reasons

At minimum:

- business promotion or fake review;
- false alarm (for example a fake "accident" post);
- abuse or harassment, including Tamil and Tanglish;
- personal data (phone numbers, number plates, home location, faces later);
- spam;
- other, with a short note.

"Still true?" and expiry are the first defence against false alarms; moderation is
the second.

## Access for the pilot

Lighter than the V3 staging process, still finite and audited:

- A **small named rota** of moderators, each with an existing Routiqo account
  under the same Google subject used for admin sign-in.
- **Shift grants** of 1–12 hours, issued at the start of each shift and revoked
  at its end: `spots_review` and `spots_hide` for every rota moderator,
  `spots_restrict` and `spots_alias_lookup` for leads (ADR 0069).
- **Two grant administrators** (`spots_grant_admin`), for example the owner and
  one lead, so each can grant the other queue access; nobody can grant
  themselves. Both rows are created **out of band** by a named database
  operator, never by a migration, startup, fixture or API; record who authorized
  each, the account UUID, reason and expiry.
- Every rota member has Google 2-Step Verification on, checked at onboarding and
  recorded in the rota list.
- Revoke grants at the end of each shift, at the end of each window and when a
  rota member leaves.

## Configuration (borrowed from the archived V3 runbooks)

Still valid mechanics from
[`V3_MODERATOR_STAGING_RUNBOOK.md`](../archive/development/V3_MODERATOR_STAGING_RUNBOOK.md)
and
[`V3_OPERATOR_GRANTS_STAGING_RUNBOOK.md`](../archive/development/V3_OPERATOR_GRANTS_STAGING_RUNBOOK.md):

- Use a separate HTTPS admin origin and a separate Google OAuth client audience
  from the consumer app; the Google project owner authorizes the origin and
  enforces the reviewed MFA policy for moderators.
- Current flag names are V3-specific: backend `ROUTIQO_V3_ADMIN_ENABLED`,
  `ROUTIQO_ADMIN_ORIGIN`, `ROUTIQO_ADMIN_GOOGLE_CLIENT_ID`; admin app the same
  three plus `ROUTIQO_ADMIN_API_ORIGIN`; grant console
  `ROUTIQO_V3_GRANT_ADMIN_ENABLED` and `ROUTIQO_V3_GRANT_ADMIN_MAINTENANCE_ENABLED`.
  Repository examples keep them false. Renaming or re-scoping them is part of the
  Phase 2 spec.
- The admin UI has no account directory or Google-subject search; obtain account
  UUIDs through the controlled rota list.
- Verify with flags off first, then one at a time; test denial for consumer
  sessions, expired grants and revoked sessions; exact and conflicting retries.
- Grant and moderation audit stays minimized: no Google subject, precise
  location, report body or contributor identity beyond what the action needs.

## On-call rota (dry run and Pongal)

- Cover the whole dry-run weekend and the Pongal event window, with extra cover
  on the peak departure and return days.
- Targets: urgent reports decided within a median of 5 minutes and at most 15
  minutes while staffed; other reports within 2 hours. Drill before 5 November:
  a moderator hides a reported test post within 2 minutes, from a phone.
- Suggested Diwali pattern (5–7 November): 6-hour shifts around the clock, two
  moderators on evening and night departure peaks, one otherwise, and one lead
  on call throughout. Adjust once the tester count is known.
- Urgent-report alerts go to one private team chat through the count-only
  webhook (ADR 0069); whoever is on shift acknowledges in the handoff log.
- Moderators need Tamil and English; at least one per shift reads Tanglish well.
- Keep a shared handoff log outside the app (who is on, open escalations), with no
  personal data copied into it.

## Escalation

1. **Rota moderator:** hide content, dismiss unfounded reports, block on request.
2. **Lead:** restrict an account's contributions; handle repeat offenders and
   business promotion patterns; close or extend the festival room if needed.
3. **Owner / security:** credible threats, doxxing, legal requests, suspected
   account compromise, or a moderation tool fault. Turn off the affected flag
   (for example chat or voice uploads) rather than leave harm visible.

Appeals for the pilot are handled by a lead through a simple contact path; the
process is set before the dry run.

## Rollback

Disable the consumer feature flag first (chat, voice or posts), then the admin
flags if needed. Revoke issued grants or let them expire. Keep audit and expiry
maintenance running for the reviewed retention period. Hide decisions already
committed stay in effect.
