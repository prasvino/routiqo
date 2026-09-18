# ADR 0039: Durable private contribution restrictions

Date: 2026-09-18
Status: accepted for implementation

Persist only a moderation-owned per-account restriction and monotonic revision,
using ContributorAssessment's UNASSESSED/SUSPENDED transitions. Do not persist
operator assessments before their audit/retention authority is designed.

Use the existing enabled-account transaction boundary for trusted internal
restriction mutations. New signal issuance and new acceptance read current
restrictions through a mandatory moderation application participant in their
existing account/owned-journey transaction. Missing stored restriction is the
explicit initial unassessed state; database unavailability is not absence.
No optional permissive fallback or API access to raw restriction mutations.

The same account lock serializes suspension against private writes across replicas.
Read restriction state before the final post-lock time check. Exact retained
receipt replay and withdrawal remain private recovery and do not require permission
to submit new evidence. Clearing a suspension resets to unassessed; it cannot
restore any assessed witness state. Existing consent/context revocation remains.
Capture the current restriction revision on each server-owned command grant and
require it to equal current authority for a new acceptance. Clearing a suspension
therefore cannot revive an unused earlier grant. V14 adds the nonnegative internal
grant revision with zero as the legacy initial-state default. It is not accepted
from browser input or added to the public grant DTO. This private fence does not
establish public eligibility of old receipts or implement projection invalidation.

V14 stores only account FK, strictly positive revision and restricted boolean, with
saturating terminal restriction and account-deletion cascade. Latest revision
persists to prevent stale enable replay. No audit history, evidence hold, geometry
or new public/admin endpoint. Future operator use requires independent identity,
permission checks and durable audit; this internal boundary is not an operational
moderation console or public publication authorization.

See DURABLE_CONTRIBUTOR_SAFETY_SPEC.md for race/rollback and architecture acceptance.
