# ADR 0044: Expected-context private signal issuance

Date: 2026-09-19

Status: accepted; internal implementation tested and independently reviewed.

## Context

ADR 0043 returns observed owner choices. Existing issue-by-anchor deliberately
uses the latest authority and can issue against a replacement context retaining
that anchor. A displayed-choice interaction requires an atomic comparison before
charging issuance, not a read followed by an unrelated legacy write.

## Decision

Add an explicitly named internal catalog-aware issuance method requiring a
validated expected context ID, route revision and consent generation. Compare
all three against current locked state in the existing single owned-journey
callback after consent/context/restriction reads and before budget reservation
or grant insertion. Deny mismatches generically. Derive grant permissions from
current rows and the configured catalog, never from the expectation or a choice
snapshot. The expectation is neither an authentication credential nor proof of
what a person actually saw.

Retain legacy Java methods and the strict anchor-only private HTTP contract.
Future displayed-choice UI must use the new path with no fallback. Acceptance,
retained replay and withdrawal keep their current authority and recovery rules.
No new endpoint, Spring wiring, schema, provider, public output or feature flag.

## Consequences

The internal prerequisite can be verified independently. It prevents stale
displayed-context issuance when a competing authority change commits first;
it cannot revoke an earlier successful issuance retroactively. Later acceptance
still rechecks authority. Issuance remains non-idempotent and a lost response
must not trigger automatic reissuance. Browser migration and guarded choice
transport require a separately reviewed contract before UI exposure.

Implementation boundaries and required race/rollback evidence are recorded in
`../features/live/EXACT_CONTEXT_SIGNAL_ISSUANCE_PLAN.md`.
