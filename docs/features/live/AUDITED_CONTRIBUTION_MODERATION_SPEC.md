# Internal audited contribution moderation

Status: implemented and verified under ADR 0041. No administrative HTTP surface.
Current integration evidence is recorded in BUILD_STATUS.md.

## Purpose and non-goals

Provide a real transactional permission, effect and audit prerequisite for future
moderation. Replace the unaudited production service from ADR 0039 while retaining
its durable restriction and signal-grant revision fence. Do not treat a supplied
UUID as authentication or expose this service through consumer sessions. Public
LIVE, reporting and independent-person evidence remain separate design gates.

## Command and permission

An immutable command contains non-nil request and subject UUIDs, a nonnegative
expected restriction revision, action RESTRICT or RESTORE, and a closed reason.
Restrict reasons: SPAM_MANIPULATION, HARASSMENT, UNSAFE_CONTENT. Restore reasons:
APPEAL_UPHELD, ERROR_CORRECTION. No free text, evidence reference or source content.
The caller supplies an independently authenticated operator identity separately.
All diagnostics and persistence failures must redact identities and command data.

The operator and subject must be distinct, enabled accounts. A database-owned
permission for the exact action must exist, with finite issuance/expiry, positive
validity of at most 24 hours and current time in the half-open validity interval.
No role is inferred from Google identity, client fields, account age or possession
of a subject UUID. Permissions cover the action across enabled subjects; they do
not establish case assignment or target-scoped authorization.

## Atomic execution

1. Validate immutable inputs before entering the authority boundary.
2. Reject an ambient transaction. Acquire both enabled account locks in database
   UUID order through EnabledAccountPairAuthority.
3. Lock/read the required grant, retained operator/request receipt and bounded
   operator debit rows. Sample UTC microsecond time after those blocking reads.
4. Require a current grant for every request, including retries. A retained exact
   fingerprint returns the original receipt without a new charge or effect.
   Changed fingerprints fail; logically expired receipts cannot authorize replay.
5. For new commands, require the exact current restriction revision for either
   action. Reject terminal revision and any invalid domain transition. Restore
   results in UNASSESSED. Restrict advances the revision even if already restricted.
6. Enforce retained capacity and the rolling operator budget. Atomically replace
   the restriction, charge one budget slot and append the immutable receipt.
   Any failure rolls back all three.

No network calls, nested authorities or asynchronous work occur in this transaction.
The target lock serializes with issuance, acceptance, deletion and other operators.
The operator lock serializes its commands and grant administration. Direct grant
provisioning/revocation must first lock the enabled operator account, then its
grant row, on the same connection/transaction. No grant writer is exposed here.
Grant-row locking additionally prevents concurrent updates from passing an action
that is already checking the row. Do not use transaction-start SQL timestamps as
fresh post-lock time. Missing authority is denial, not an empty permissive state.

## Retry, quota and lifecycle

- Fingerprint: subject, expected revision, action and reason under operator/request,
  enforced for the retained receipt horizon. After cleanup, reuse of that request
  ID with a different intent may execute as a new authorized command; lifetime
  uniqueness is not provided. Exact revisions still prevent reapplying the original
  successful effect. Future callers must use fresh IDs for new intents.
- Receipt: original successful result and fixed first-receipt/expiry times; no
  permission renewal, evidence access, current-state read or repeated action.
- Receipt retention: exactly 720 elapsed hours (30 days), independent of database
  session timezone or daylight-saving transitions. Both account FKs cascade deletion. Logical
  expiry denies replay independently of physical cleanup. No resolution extends it.
- Capacity: at most 1,000 physically retained receipts/operator; reject at capacity,
  including when expired rows await cleanup. Never evict unexpired audit records.
- Budget: at most 20 new actions in the preceding rolling hour per operator across
  targets, actions and sessions. A separate 20-slot ledger stores only operator,
  slot and action time; target deletion and receipt purge cannot reset it. Future
  rows deny new commands. Exactly-one-hour-old debits are reusable; exact retries
  consume nothing. Operator deletion cascades the ledger.
- Cleanup: indexed, bounded leaf-only deletion of expired receipt rows using
  SKIP LOCKED, maximum 500 per call, its own short transaction and no account-lock
  acquisition. Expired debit slots also support bounded leaf-only cleanup, which
  must lock each candidate and recheck its captured time before deleting it.
  The optional scheduler is specified separately in
  `MODERATION_EXPIRY_MAINTENANCE_SPEC.md` and remains disabled by default.
  Debit slots are bounded and reused;
  they do not depend on cleanup to enforce the budget.

The audit is atomic database evidence of a command, not tamper-proof storage or
proof the reason was truthful. No queue/read/export endpoint is introduced.
Account deletion intentionally removes identifying audit, and backup erasure is
not established by SQL cascade. Operational policy must address that tradeoff
before use. This slice does not retain report evidence for investigation.

## Required verification

Use real disposable PostgreSQL tests for missing/wrong/expired/future permissions,
disabled accounts, exact and changed retries, expiry after a lock wait, grant
revocation ordering, competing operators at one revision, account deletion,
atomic rollback on audit failure, capacity and rolling limits, budget survival
after subject deletion, cleanup/retry safety and saturation. Verify redacted
failure paths and no ambient transaction joining.

Architecture checks must prohibit API access to internal command/grant/audit
boundaries and prevent production restriction writes outside the audited owner.
Signal issuance/acceptance depend on a read-only restriction interface. Preserve
the existing signal suspension and restoration-fence tests using a test-only
setup helper, never a production unaudited service bean.

## Remaining release work

Independent admin authentication, recent strong authentication, grant provisioning
and revocation tooling, transport request limits (including denied/replayed requests),
case/target authorization, auditable queue reads, reporting
authority and investigation retention, alerts, appeals and production cleanup /
backup capacity remain pending. No REST route, public flag, operator seed, database
role assignment or user-data mutation is authorized by adding this implementation.
