# ADR 0069: Pilot moderation access and alerting

Date: 2026-09-25
Status: proposed with [PILOT_MODERATION_SPEC.md](../features/spots/PILOT_MODERATION_SPEC.md); not implemented

## Context

The admin app, sessions and grants were built for the archived V3 traffic
summaries (ADRs 0056, 0057). For a small rota moderating Spot posts over multi-day
festival windows, four limits block real use: grants are issued for at most
4 hours, sessions last 15 minutes with no renewal, only `traffic_*` holders can
sign in, and the single grant admin cannot give themselves queue access. There
is also no way to alert moderators when urgent reports arrive.

## Decision

1. **New Spots permissions**: `spots_review`, `spots_hide`, `spots_restrict`,
   `spots_alias_lookup`, `spots_grant_admin`. Admin sign-in accepts any current
   Spots or V3 permission.
2. **Shift grants of 1–12 hours** through the API; the 24-hour database limit
   stays. Grants are issued per shift and revoked at its end.
3. **Two grant admins**, created out of band, who can grant each other queue
   permissions. Self-grants and API issuance of `spots_grant_admin` stay denied.
4. **Renewable admin sessions**: 15 minutes idle, renewed while active, up to an
   8-hour absolute limit, with a grant check on every renewal.
5. **Count-only urgent-report alert** to one configured HTTPS webhook (a private
   team chat), throttled to one per 5 minutes, sent after commit, carrying no
   item, Spot, alias, account or report detail. New external dependency: the
   chosen chat service receives only counts.
6. **Audited alias lookup** returns short-lived opaque account references and
   minimal context, never e-mail, name or Google subject.

## Consequences

- A compromised moderator session is valid for up to 8 hours instead of 15
  minutes; mitigated by the idle timeout, grant checks on renewal, shift-bounded
  grants and Google 2-Step Verification required at onboarding (not verifiable by
  the backend).
- Grant admins must be available at every shift change.
- The alert channel's provider learns only that urgent reports exist and when.
- V3 permissions, tables and flags are untouched and stay archived.
