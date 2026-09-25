# ADR 0068: Collapse a reported post pending review (Pongal)

Date: 2026-09-25
Status: accepted direction by the owner, 2026-09-25; not implemented; for the
Pongal 2027 launch only

## Context

ADR 0064 says report counts never hide anything automatically; a moderator
decides. At a festival peak, a small on-call rota cannot review every report
within minutes, and harassment or leaked personal details in a public post do
their harm quickly.

## Decision

This amends ADR 0064 for Spot posts only
([POSTS_AND_SIGNALS_SPEC.md](../features/spots/POSTS_AND_SIGNALS_SPEC.md)):

- A post is hidden pending review once **3 different accounts** report it as
  `unsafe`, `abuse` or `personal_data`.
- A report counts only if its account is at least 7 days old and has at least
  one completed journey. `false_alarm` and `spam` reports never collapse a post.
- The author sees "Hidden pending review"; other readers no longer see the post.
- A moderator confirms (the post stays hidden with a recorded reason) or restores
  it. Restoring marks the reports as not upheld; accounts with repeated
  not-upheld reports are restricted (ADR 0039).
- Signals are never collapsed; "No longer true" handles inaccurate signals.
- Flag `ROUTIQO_SPOTS_REPORT_COLLAPSE_ENABLED`, exact `true` only, default off.
  Off for the Diwali 2026 dry run, where the tester group is too small for the
  threshold and the on-call rota handles reports; planned on for Pongal.

## Consequences

- Three coordinated established accounts can hide a legitimate post until a
  moderator restores it. The age and completed-journey requirements, the
  not-upheld penalty and the review queue bound this.
- Report counts stay private; the collapse reveals only that a post is under
  review, not who reported it or how many did.
- Everything else in ADR 0064 is unchanged.
