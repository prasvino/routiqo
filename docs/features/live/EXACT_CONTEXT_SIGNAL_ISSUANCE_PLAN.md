# Exact displayed-context signal issuance — next implementation slice

Status: internal implementation tested and independently reviewed,
2026-09-19. ADR 0044 adds `issueExpectedContext` with a required immutable
`SignalIssuanceExpectation`. ADRs 0033/0035's legacy paths and current wire contract
remain unchanged. No browser caller uses the new path yet.

## Problem and selected direction

The ADR 0043 choice snapshot identifies the context and consent the owner saw.
Current `CatalogSignalService.issue(actor, journey, anchor)` issues against current
authority. If the anchor survives context replacement, the new grant can refer to
a different context from the displayed choices. That behavior is valid under the
existing contract but cannot provide the intended UI confirmation guarantee.

Prefer a server-enforced expected-context precondition over issuing a grant and
discarding it after discovering a mismatch. Check inside the existing storage
service's single owned-journey callback, before grant construction, grant budget
reservation or insertion. A separate choice read followed by legacy issuance is
not atomic and must not substitute for this check.

## Internal implementation boundary

- Add a small immutable expectation containing a nonnil context ID and exact
  nonnegative `long` route revision and consent generation. Keep diagnostics
  redacted; it contains no categories, actor, coordinates or client lifetime.
- Add an explicitly named catalog-aware expected-context issuance entry point.
  The new entry point requires a nonnull expectation; omission must never mean
  “use latest”. Preserve the old internal and HTTP call shapes in this first
  slice, documenting their existing semantics rather than silently changing them.
- Reuse current consent → context → restriction order and post-read time check
  in `SignalStorageService`. Compare all three expected values with authoritative
  state while the account/journey locks are held. A mismatch uses the existing
  generic denial boundary; never return the actual context or revision on failure.
- Preserve active owner/journey checks, on-consent, suspension/revision fencing,
  catalog provenance, anchor membership/category derivation, temporal bounds and
  existing grant lifetime. Equality is an additional condition, never authority.
  Evaluate expiry using actual post-lock Instant precision before normalizing
  persisted grant timestamps to the existing microsecond policy. Do not widen
  this change into unrelated acceptance clock behavior.
- Preserve one authority callback and the existing transactional budget/insert
  behavior. Do not add providers, nested callbacks, persistence, migrations,
  extra reads outside authority, labels in grants, caching or new quota policy.
- Acceptance, retained exact replay and terminal withdrawal keep their current
  semantics. A matching issuance does not promise validity at later acceptance;
  current authority must still be rechecked there.

## Required regression evidence

1. An exact expectation issues a grant with the expected identity/versions and
   charges the existing grant budget once; categories remain catalog-derived.
2. Wrong context ID, wrong route revision and wrong consent generation each deny
   with no grant insertion or grant-budget debit. Missing/nil/negative values fail
   before mutation. Include exact values above JavaScript's safe integer range
   and Long.MAX_VALUE; test absent and already-existing budget rows.
3. Replace a context while keeping the anchor: the old expectation denies. Turn
   consent off and back on: old generation denies. Completion, suspension,
   expiry and catalog mismatch continue to deny even if expected values match.
4. Use real PostgreSQL authority serialization to exercise replacement/revocation
   winning a lock race. A grant that wins first remains subject to subsequent
   acceptance authority; do not assert impossible retroactive issuance prevention.
5. Keep legacy issue behavior covered explicitly. Retained replay and withdrawal
   tests must remain green; no changed fingerprint or evidence renewal is allowed.
   Inject insertion failure after reservation and verify the transaction rolls
   back both the budget and grant changes.
6. Independent review, targeted tests and full core check/bootJar before claiming
   the internal prerequisite complete. Browser exposure is a separate slice.

## Subsequent browser boundary

Design the guarded owner choice read and an explicit expected-context issuance
wire contract together. Decide a new route/version or coordinated migration;
do not accept a mixed optional precondition that lets new UI silently fall back
to legacy semantics. Keep strict decimal strings, canonical UUIDs, no-store,
default-off configuration, exact proxy allowlisting, authentication/origin/CSRF
guards and durable request quotas. Record the decision in an ADR and OpenAPI
before implementation. No endpoint path or flag is approved by this plan.

Start with the existing `RouteBindingConfiguredTest` PostgreSQL facade fixture
and small expectation-model tests. Reuse its authority/race/replay helpers rather
than introducing a second transaction implementation or a large duplicate harness.

The UI must compare the returned grant to its captured choice, cancel and discard
stale account/journey/consent/context responses, observe expiry, and keep issuance
explicit. Lost issuance is not idempotent: no automatic retry or offline replay.
Existing grants expire normally; discard does not erase a charged grant. Exact
acceptance recovery and withdrawal remain separate from new issuance.

Public eligibility, cohort privacy, evidence independence, reporting, block-safe
delivery and operational moderation remain separate gates. Neither labels nor an
exact context match proves physical presence or independent human contributors.

## Current verification evidence and limits

Full core check and bootJar passed: **409 Java tests across 56 suites**, zero
failures/errors/skips. Independent production and final test review approved.

Thirteen focused tests passed across `RouteBindingConfiguredTest` and
`ExpectedSignalIssuanceTest`. New cases cover exact fields and catalog categories,
one budget charge, mismatches with existing budgets, no budget creation for stale
choices, same-anchor replacement, consent cycling, a real PostgreSQL replacement-
winning lock race, exact nanosecond expiry, participant order, one authority
callback, persisted timestamp precision, and expected-path acceptance/replay/
withdrawal. Invalid tuples, long precision and redacted diagnostics are tested.

Follow-up verification under ADR0045 completed the dedicated expected-path
consent-winning issuance race and real-budget insertion-failure rollback cases,
including absent/existing budget rows. Full416-test backend verification passed.
The original thirteen-test result above remains historical; the new guarded
choice/issuance browser boundary is specified in BROWSER_SIGNAL_CHOICES_API_SPEC.
