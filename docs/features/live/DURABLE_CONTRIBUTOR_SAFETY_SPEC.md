# Durable contributor safety authority

Status: implemented private persistence and signal enforcement; no operator API.
This slice persists contribution suspension only; assessments remain pure values.

## Bounded scope

Persist the UNASSESSED/SUSPENDED subset of ContributorAssessment in moderation-owned
latest state per account. Reuse AccountWriteAuthority for mutations and a trusted
transaction participant for reads inside JourneyWriteAuthority. No new public
or admin endpoint, user-role model, evidence publication or blanket account ban.
Private retained receipt recovery and withdrawal remain available during suspension.

Unknown assessment is not independent evidence. An unsuspended UNASSESSED actor
may continue existing private ingestion; an ASSESSED actor still cannot bypass
the publication gate. SUSPENDED denies both new grants and new acceptance.
Suspension is checked after current account/journey locks; recheck server time
after any blocking safety read. A concurrent suspension and acceptance serialize
through the same target-account lock. Stale grants cannot override suspension.
Bind every new grant to the current internal restriction revision and require
equality on new acceptance. Persist this server-only revision on the grant;
consume preserves it. Suspend followed by unsuspend cannot revive an unused
pre-suspension grant. This does not add a client-controlled authority field.
V14 adds a nonnegative grant restriction_revision default zero for existing rows;
initial absent restriction is revision zero. Retained exact replay remains first.
Previously accepted receipts are still private and must not be used for future
public output without a separately approved current-authority/revocation design.

## Ownership and storage

One live_contribution_restriction row per actor, account-deletion cascade, monotonic
revision and restricted boolean only. Do not persist assessment references or
times in this phase: their operator audit and physical-retention rules are pending.
Missing row maps
to explicit UNASSESSED revision zero only while under current enabled-account
authority; database failure never maps to missing. Enforce identity/state/revision
constraints in V14, including maximum revision requiring restricted=true.
Stored revisions are strictly positive; only row absence represents revision zero.
The latest revision is retained until account deletion; no per-action history,
precise location, report content or assessment metadata enters this table.

Mutation methods are trusted internal application interfaces, like existing raw
consent/context participants. They cannot be called from API packages. Operator
authentication, least-privilege authorization, durable decision audit and report
reference authorization are prerequisites to a future operational moderation
endpoint. Tests of this internal service do not establish an operator workflow.

## Acceptance

Actual PostgreSQL composition, absent/default state, exact/stale intent ordering,
saturation, no resurrection after unsuspend,
new grant/acceptance denial, retained replay/withdrawal allowance, rollback,
pre-suspension unused grant denial after unsuspend, fresh post-unsuspend issuance,
independent-adapter suspension-versus-acceptance race, post-lock time, missing
account, account-deletion cascade and redacted failures. Application/domain
architecture tests must prevent API bypass of trusted moderation mutation.

Do not implement durable report or block stores under this slice: those require
separate bounded retention, authorized target references and cross-account lock
order design. Leave public publication disabled and document the remaining gates.
