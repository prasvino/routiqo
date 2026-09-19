# ADR 0048: private browser contribution authority and recovery

Status: accepted for the private browser integration, 2026-09-19. No public
publication approval or backend feature activation.

## Context

ADRs 0045–0047 provide exact-context choices/issuance and terminal stopping.
Mounting those clients requires separating permission to create a new observation
from the owner's ability to stop a known command after route or sharing changes.
A cancelled HTTP request cannot establish whether the server accepted it.

## Decision

Only a fresh successful route bind exposes contribution authority through a
synchronous getter/subscription owned by the existing route planner. The workspace
owns consent invalidation. Current account, journey, route selection and exact
context/revision/consent must still match at every asynchronous boundary. A context
read establishes no association with the displayed route. Choices and stopped/
passenger intent are checked explicitly; issuance and acceptance each run once.

JourneyWorkspace owns a separate account-scoped, memory-only recovery coordinator.
It survives contribution panel remounts, Ghost, route replacement and completion.
At most five known commands, including terminal notices, may be retained. Unknown
issuance creates no invented handle. Uncertain acceptance/stop blocks new issuance;
capacity never silently evicts recovery. Object-identity operation tickets and
phase checks prevent late acceptance from replacing a stopping/stopped result.

Stop uses a separate cancellation scope and confirms the current account. It does
not require current sharing or prepared-route authority. Same-account auth refresh
hides and preserves memory; account change/logout clears it. Receipt metadata has
bounded local retention; command handles remain available after metadata expiry.
No observation/route payload enters storage, journal, backup, analytics or outbox.

Warn before ordinary departure while commands remain. Warnings coexist with the
journal's unsaved-edit guard and preserve each other's router/history state.
Unload warnings are best effort. Explicit local removal is distinct from Stop;
there is no cross-page/reload recovery promise or inference from an empty list.

## Consequences and evidence

The first private interface offers explicit Stop/Retry Stop rather than replaying
an uncertain observation. Existing server authorization, quotas, expiry and feature
flags remain authoritative. No native or public LIVE capability is added.
The lifecycle contract and acceptance cases are maintained in
`../features/live/BROWSER_QUICK_SIGNAL_UI_PLAN.md`; rendered evidence and limitations
belong in `../quality/PRIVATE_QUICK_SIGNAL_UI_QA.md`, with final suite/build evidence
in `../quality/BUILD_STATUS.md`. Real OAuth, regional services/catalog, assistive
technology and device verification remain release dependencies.
