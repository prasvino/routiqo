# V3 community traffic summary — staging implementation evidence

Date: 2026-09-23. Status: **implemented behind disabled production flags; not deployed or approved for production**. The owner authorized V3 implementation and staging evaluation, while withholding acceptance of its different privacy contract. ADR 0053/0054 person-level research remains preserved and disconnected. No V18 intent, V21 claim or V22 frozen Share is migrated into V3.

## Demonstrable slice

V23 stores fresh purpose-bound traffic candidates, exact request identity and a daily successful-submission debit. Owned Share, Stop and account recovery use browser session/account/CSRF guards; Stop remains available after journey completion. V24 stores one catalog-versioned canonical terminal decision per anchor/five-minute window, including `NO_OUTPUT`, and an independently expiring projection. The bounded PostgreSQL publisher takes session advisory ownership before its `REPEATABLE READ` snapshot, obtains database evaluation time in its first snapshot statement, and applies the provisional 12 accounts/10 agreeing/80% rule. Late or uncommitted candidates cannot reopen a terminal window. Current owned-journey readers receive relevant coarse moments only; reporting uses an opaque visible reference. Internal grant-gated suppression is audited separately from the terminal decision. Independent maintenance removes expired candidate, projection, debit, report and suppression-audit rows while preserving decision tombstones.

The active-journey web surface mounts deliberate Share, exact retry, owner recovery, future-sharing Stop, a community-only canonical list and report controls under its own default-off flag. It distinguishes community reports from official alerts and says accepted candidates are only considered, not necessarily published. OpenAPI and generated TypeScript contracts cover the new transport. [Staging configuration and rollback](../development/LOCAL_SETUP.md#v3-community-traffic-staging-evaluation-adr-0055) name the separate API, publisher, maintenance and web flags; every example is false.

## Verification performed

| Check | Result | Boundary |
|---|---|---|
| `backend/gradlew.bat :core-api:check` | Passed: **514 tests / 85 suites**, zero failures, errors or skips | Real PostgreSQL Testcontainers used by integration suites, including the final server-time and deletion cases. |
| Focused V3 publisher and reader integration | Passed: **16** publisher/reader/report PostgreSQL cases and two controller cases | Includes production DB-time expiry and feed response-time expiry boundaries. |
| `pnpm check` | Passed: **586 tests / 68 files**, contracts in sync, formatting, TypeScript and lint | Includes V3 proxy, client, mounted workspace, monotonic clock-skew and component interaction tests. |
| `pnpm --filter @routiqo/web build` | Passed, with no QA fixture route in the production route list | Build did not enable V3 flags. |
| `pnpm secrets:check` and `git diff --check` | Passed; no leaks or whitespace errors reported | The diff check emitted Windows line-ending notices only. |
| Local browser pass against temporary simulated-transport fixtures | Share consent gate, accepted-for-consideration copy, visible report, Stop wording, recovery and offline clearing observed in the real browser. A second rendered pass with the final `serverTime` feed displayed the canonical row and cleared it offline; 320 px viewport had no horizontal overflow, action buttons measured 44 px high | Fixtures were removed before build. They did not use real OAuth, backend session, road catalog or provider data. |

Focused PostgreSQL cases include duplicate/debit and unrestricted-vs-suspended real JDBC Share; empty terminal windows; 512-anchor bounded batch drain; two publishers; an uncommitted Share; Stop before/after the input snapshot; Ghost Mode, journey completion, restriction and account deletion before the snapshot; expired authority at the production database-time boundary; catalog drift; rollback/retry; reader relevance and expiry; guessed report references; grant-gated suppression; and cleanup. An independent adversarial review found and prompted corrections to the real restriction-state check, publisher evaluation time, empty-window sealing, catalog drift, worker capacity and UI expiry/retry handling.

## Synthetic utility, not pilot evidence

The deterministic [utility exploration](V3_COMMUNITY_UTILITY_2026-09-23.md) ran 10,000 synthetic windows per scenario. At 8 accounts/window it produced **0%** visible summaries; at 12 accounts with independently 90%-correct reports, **89.00%**; at 25 accounts with 65%-correct reports, **8.49%**. Ten coordinated false accounts among 12 produced **100% wrong visible summaries** in that model. With 15 repeat commuters and a 12-success daily cap, only the first 12 of 24 attempted windows can reach the 12-account floor. These are model outputs, not local road density, accuracy, output latency, privacy protection or production readiness.

## Exact remaining staging and production work

1. Provision real Google OAuth, reviewed regional Valhalla/Photon services and route-anchor catalog; run a consented authenticated staging browser/device journey across two accounts/devices. No such credentials or regional service dataset were available for this pass.
2. Connect the internal audited suppression command to independently authenticated, controlled moderator operations and provision finite operator grants. The admin app is currently a shell; exposing suppression on a consumer endpoint would be unsafe. Review report handling, blocks and appeals.
3. Run actual database backup/restore and failover drills, monitor publisher/cleanup backlog and latency at the intended catalog/replica scale, and approve bounded data, audit and backup retention. The in-process concurrency/rollback tests are not a restore drill.
4. Collect consented Chennai/OMR density and independently observed road-condition ground truth; measure coverage, output age, incorrect/false-reassuring results, repeat-commuter depletion, collusion and user understanding. The provisional 12/10/80% rule must not be lowered merely to populate the UI.
5. Complete product/privacy and security review of disclosure, participation-inference risk, after-snapshot withdrawal, block policy, moderation and retention. Obtain the owner's **separate explicit production acceptance** of V3's non-DP privacy contract. Keep API, publisher and client production flags off until [the decision checklist](V3_PRODUCTION_DECISION_CHECKLIST.md) is closed.

The server independently rejects expired projections and supplies a response-time clock sample. The browser conservatively derives a monotonic deadline for delivered rows; a slow device wall clock cannot extend their display. Real device suspend/resume, network-delay and clock-skew QA remains necessary. Community reports are uncertain, and no output does not mean clear roads.
