# Archive

Material moved here on 2026-09-25 when the [direction brief](../direction-brief.md)
reset Routiqo to Journey, Spots and Ask Ahead (see [PRODUCT.md](../PRODUCT.md)).
Files keep their original relative paths under `docs/archive/` and their git
history (`git log --follow`). Each file starts with a one-line archive note.

Archived documents are **historical records, not requirements**. Do not implement
from them. Code they describe stays in the repository, default-off; archiving a
document neither deletes nor enables code. No stored private report, consent or
receipt may become public through a migration.

**Revisit rule:** threshold, cohort, collusion and differential-privacy material
is revisited only if aggregate traveller counts return to scope, and only after a
separate privacy review approved in [PRODUCT.md](../PRODUCT.md).

Architecture decision records are **not** moved. They stay in
[`docs/adr/`](../adr/) with a status note, as decision history.

## What was archived and why

### Product source of truth

| File | Why |
| --- | --- |
| `product/ROUTIQO_MASTER_CONTEXT.md` | Product model ("the route is the social graph", Living Route, traveller counts, server-side presence pipeline) replaced by `PRODUCT.md`. Engineering sections (stack, modules, contracts, offline, media, auth, admin, testing, observability, CI) moved to `architecture/ENGINEERING_CONTEXT.md`; kept product guardrails moved to `PRODUCT.md`. |
| `todo.txt` | Older long-form roadmap built around the LIVE list and treating Android as secondary. `todo.md` is the release checklist. |

### Passive presence, thresholds, cohorts and public LIVE publication

These designs protected either server-side presence (passive location) or
published aggregates of chosen Quick Signals, where the existence of a summary
could reveal whether one hidden person reported. The pilot has neither: it
collects no continuous location, and posts and signals are openly public under a
per-room alias, author-deletable and expiring.

| File | Why |
| --- | --- |
| `features/presence/PRESENCE_SPEC.md` | Presence leases, 10-actor minimum, Same Situation cohorts |
| `features/live/ROUTIQO_LIVE_SPEC.md` | LIVE list with Live Moments; replaced by Journey/Spots/Ask Ahead. Its honest-empty-state, bands-not-ETAs and server-time expiry rules carry into `PRODUCT.md` |
| `features/live/COHORT_PUBLICATION_DESIGN.md` | Cohort threshold publication |
| `features/live/PUBLIC_LIVE_PRIVACY_PROTOCOL_SPEC.md` | Person-level differential-privacy protocol (threshold 22) |
| `features/live/TRAVELLER_PUBLIC_LIVE_SPEC.md` | Traveller-derived public LIVE |
| `features/live/BROWSER_PUBLIC_SHARE_V2_SPEC.md` | Irreversible Share |
| `features/live/PUBLIC_LIVE_PRIVACY_DECISION.md`, `features/live/ADR0055_PILOT_MEASUREMENT_PROTOCOL.md` | ADR 0054 vs 0055 decision document (recorded as ADR 0065) and the ADR 0055 pilot measurement protocol, both merged from main the same day and overtaken by the brief. ADR 0065 stays in `docs/adr/` as partly superseded; its no-anonymity-claims, no-identity-collection and fail-closed principles carry into PRODUCT.md |
| `features/live/COMMUNITY_TRAFFIC_SUMMARY_SPEC.md` | V3 12/10/80% community traffic summary (brief: archive) |
| `features/live/PILOT_PERSON_IDENTITY_SPEC.md` | Person registry supporting the DP person bound |
| `features/live/VERIFIED_CONTRIBUTOR_AUTHORITY_SPEC.md` | Dual-review verified-person authority for publication. Open question "should Spot passage require sign-in" may want a lighter version later |
| `instructions/2026-09-public-live-protocol-implementation.md` | Implementation plan for the DP public LIVE protocol |
| `validation/PUBLIC_LIVE_PENDING.md`, `validation/PUBLIC_LIVE_PROTOCOL_REVIEW_2026-09-23.md` | Public LIVE gate ledger and review. The review's finding that 10–12-report thresholds produce almost no output at pilot density supports the brief's reasoning |
| `validation/V3_COMMUNITY_UTILITY_2026-09-23.md`, `validation/V3_PRODUCTION_DECISION_CHECKLIST.md`, `validation/V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md`, `validation/V3_STAGING_TRIAL_PENDING.md` | V3 community summary staging trial. Its moderation and real-identity rows were carried into the Phase 2 moderation plan (`development/PILOT_MODERATION_RUNBOOK.md`) |
| `development/V3_MODERATOR_STAGING_RUNBOOK.md`, `development/V3_OPERATOR_GRANTS_STAGING_RUNBOOK.md` | V3-specific moderator and grant runbooks (traffic review/suppress). Replaced by the simpler pilot moderation runbook |

### Per-journey consent and private contribution flows

The brief replaces heavy per-journey consent with one clear opt-in for Spot
passage and a simple Ghost Mode. Posting is an explicit act and needs no separate
consent ceremony.

| File | Why |
| --- | --- |
| `features/live/CONSENT_INTENT_SPEC.md`, `features/live/DURABLE_CONSENT_SPEC.md`, `features/live/BROWSER_CONSENT_API_SPEC.md`, `features/live/BROWSER_LIVE_CONSENT_UI_SPEC.md`, `features/live/NATIVE_LIVE_CONSENT_SPEC.md` | Per-journey consent generations, intent ordering and UI (web and Android). Transport hardening and revocation-precedence ideas may be reused for the Spot-passage opt-in |
| `features/live/BROWSER_ROUTE_BINDING_UI_SPEC.md` | "Check / prepare private route" ceremony; Spots ahead should appear automatically on journey start |
| `features/live/BROWSER_QUICK_SIGNAL_UI_PLAN.md` | Web private contribution UI; recovery lessons apply to the Android Spot signal UI |
| `validation/NATIVE_LIVE_PENDING.md` | Native consent / route preparation / private Quick Signal ledger |
| `validation/IMPLEMENTATION_RESUME.md` | Handoff resuming native route preparation (ADR 0061); replaced by a Phase 1 handoff |
| `quality/PRIVATE_CONSENT_UI_QA.md`, `quality/PRIVATE_ROUTE_PREPARATION_UI_QA.md`, `quality/PRIVATE_QUICK_SIGNAL_UI_QA.md` | UI QA for the archived private flows |
| `quality/evidence/native-live-consent-2026-09-24/`, `quality/evidence/native-route-preparation-2026-09-24/` | Evidence for the archived native flows |

### Other

| File | Why |
| --- | --- |
| `quality/BUILD_STATUS_HISTORY_2026-09-19.md` | Already a historical snapshot; `quality/BUILD_STATUS.md` is current |
| `development/BUILD_PLAN_LIVE_QUEUE_2026-09.md` | The LIVE-first execution queue and L0–L5 stages removed from `development/BUILD_PLAN.md`, which now follows the five pilot phases |

## Kept, not archived

Infrastructure specs under `docs/features/live/` that the new model reuses
(signal storage, idempotent commands, abuse budgets, expiry maintenance,
anchor catalog and route matching, blocks, restrictions, audited moderation,
official alerts) stay in place with a direction-brief status note at the top.
