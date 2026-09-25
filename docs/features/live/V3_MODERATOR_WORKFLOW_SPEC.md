# V3 community traffic moderator workflow — staging feature contract

Status: implemented behind disabled flags for isolated staging evaluation. Real staging evaluation, production V3 activation and the ADR 0055 privacy contract remain unapproved. This workflow concerns only V3 canonical traffic summaries; it does not connect the paused person-level research or the separate private report proposal.

> **Direction brief (2026-09-25):** The V3 community-traffic target is retired and archived. The separate admin login, CSRF, short session and finite permission-grant boundary are kept and simplified for the pilot moderator rota; see [PILOT_MODERATION_RUNBOOK.md](../../development/PILOT_MODERATION_RUNBOOK.md). See [PRODUCT.md](../../PRODUCT.md).

## Purpose and boundary

A moderator can review bounded reports against a canonical V3 summary, record a disposition, and suppress a still-serving summary when warranted. The workflow must not reveal candidates, contributors, receipt IDs, precise routes, or reporter identities. A report is a signal for human review, never an automatic takedown. Expired or deleted projection content is explicitly unavailable for investigation; report metadata cannot stand in for evidence.

The admin application and API use a separately configured Google OAuth audience, a separate challenge, session and cookie namespace, an exact admin origin, CSRF protection, a short session, and a default-off staging flag. A consumer session or caller-supplied operator UUID never grants access. The Google subject must resolve to an existing enabled Routiqo account; no admin sign-in creates accounts. Every read and write requires a current finite database permission. The separately gated [grant administration workflow](V3_OPERATOR_GRANT_ADMIN_SPEC.md) can issue and revoke V3 moderator permissions; real admin OAuth/MFA, out-of-band root bootstrap and supervised operation remain staging prerequisites. No role is inferred from email or domain.

## Queue and decisions

- The queue is operator-only, bounded and cursor paginated. It groups report reasons by canonical projection reference without exposing reporter IDs or raw report rows. It shows the approved coarse label/value/window only while the projection remains retained. When the projection has gone, show `EVIDENCE_UNAVAILABLE` and no road/value detail.
- A queue read requires `traffic_review` or `traffic_suppress` grant and writes a minimized operator read audit. No count endpoint, public lookup, free-text search or export.
- Dismissal needs `traffic_review`, a closed reason, request identity and current investigable projection. It records an immutable audit and closes that report group. It cannot mark unavailable evidence as unfounded.
- Suppression needs `traffic_suppress`, a closed reason, request identity, current investigable unsuppressed projection and an open report group. The canonical terminal decision is never redrawn. The effect, review disposition and suppression audit commit atomically. Exact replay is idempotent under a current grant; conflicting replay fails.
- A report arriving after a dismissal reopens review; a suppressed projection stays suppressed. An expired or deleted projection cannot be suppressed or dismissed as if reviewed. No source lifetime or backup retention is extended to keep a queue item useful.
- Operator reads and writes have separate bounded rate limits. Repeated denial or invalid inputs count against the transport gate. Responses use no-store, generic unauthorized/missing errors, and no sensitive diagnostic content.

## Acceptance criteria

1. Default-off admin routes and UI; consumer cookies/Google audience cannot authenticate. Separate admin origin, nonce/binding, CSRF, exact account identity and finite grant checks fail closed.
2. Real PostgreSQL tests cover queue isolation, paging bounds, missing/expired grant, disabled account, report after dismissal, unavailable evidence, exact/conflicting retries, concurrent suppression, rollback and no projection resurrection.
3. Admin UI supports sign-in, queue loading/empty/unavailable/error, report details without contributor identity, dismiss/suppress confirmation, uncertain-write recovery, sign-out and narrow/keyboard layouts. No synthetic report data in the shipped UI.
4. OpenAPI/generated transport, configuration and operator runbook match the implementation. Real staging OAuth, MFA policy, named grant-administrator bootstrap and operation, multi-account/device verification and retention review remain explicit prerequisites.
