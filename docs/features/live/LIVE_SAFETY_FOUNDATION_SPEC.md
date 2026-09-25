# Live safety foundation

Status: implemented and reviewed private domain primitives. Durable contribution restrictions are implemented separately under ADR 0039; durable blocks and reports are separate slices.
Related decision: ADR 0038. No public publication or operator API authorization.

> **Direction brief (2026-09-25):** Kept. Report reasons must add business promotion (spam or fake reviews at Spots) and false alarm, and reports apply directly to posts, voice notes, chat and signals. Contributor assessment as a gate to "independent public evidence" is retired with threshold publication. See [PRODUCT.md](../../PRODUCT.md).

## Contributor assessment and suspension

Create a moderation-owned immutable state for an actor with monotonic revision.
Initial state is UNASSESSED and cannot qualify as independent public evidence.
An explicit assessed state has a non-nil opaque assessment reference and finite
issued/expiry instants, with a maximum 24-hour validity. It represents reviewed
eligibility only, never a claim of one human, location, or publication permission.
No automatic age/login-based promotion or renewal is allowed.

Suspension immediately denies new contribution eligibility and assessment use.
Unsuspension advances revision and resets to UNASSESSED; previous assessment is
not restored. Assessment cannot override a suspended state, even at the exact
revision; explicit unsuspension is required first. All assessment/unsuspend intents require exact expected
revision. A stale suspension may conservatively win; a future revision denies.
All commands reject negative or future expected revisions before mutation.
Every accepted suspension advances revision even if already suspended, unless
already saturated; repeated revocation invalidates previously captured authority.
At maximum revision, revocation must still deny permanently; no wraparound or
subsequent enabling. Immutable state and diagnostic strings must redact actor,
assessment identifiers and timestamps.

## Directional block intent, bilateral exclusion

A private directed block edge is scoped to two distinct non-nil actor UUIDs.
Initial state is unblocked, revision zero. Blocking takes precedence over stale
unblock intents; unblocking requires exact revision. Maximum revision saturates
to permanently blocked. A pair is excluded if either direction is blocked.
Both block and unblock reject negative/future expected revisions. Repeated
blocking advances revision even if already blocked, except at saturation.
Revocation never decreases a revision. Pair evaluation must validate reverse
identity correspondence, not combine unrelated edge states.
Missing/unavailable edge authority is not equivalent to an unblocked edge.
Only explicit fresh unblocked states on both directions can qualify as clear.
This primitive is not aggregate suppression, a block endpoint, or authorization
to expose the other actor's identity. Production mutations need a server-authorized
target reference; current first-release UI has no contributor directory.

## Structured report and operator case lifecycle

Use closed reason enums (misleading information, unsafe content, harassment,
spam/manipulation) without free text, reporter profiles or precise locations.
A report is bound to non-nil opaque reporter and authorized evidence-reference
IDs and a server receipt instant. Reference authorization must occur in the
application boundary; a UUID alone is never authority. Duplicate comparison is
exact on reporter-scoped non-nil request UUID, reference and reason, not timestamps.
The same reporter/request key with changed reference or reason conflicts.

Case states are OPEN, DISMISSED and ACTIONED. Resolution requires exact revision
and an explicit closed decision plus non-nil resolution request UUID. A terminal
retry is idempotent only for the same resolution UUID/decision and the original
pre-resolution expected revision. Any different terminal command conflicts.
No implicit reopen; resolution overflow denies. Cases do not carry evidence bodies
or create an indefinite moderation hold. Durable retention, operator authentication,
audited writes, report quotas and public reference issuance remain subsequent
implementation gates. Pure transitions are trusted internal operations only.

## Verification and integration boundary

Test default denial, assessment expiry/future times, stale enable/revoke races,
overflow, re-enable non-resurrection, directional/bilateral block decisions,
unknown authority denial, invalid/self/nil identities, report exact retries,
terminal conflict and redacted diagnostics. No Spring, SQL or HTTP in domain.
Do not use these primitives to claim durable blocking or operational moderation.
Future persistence must serialize transitions and check current revisions inside
the existing ordered account/journey/safety authority transaction.
