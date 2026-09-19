# ADR 0042: Optional curated route-area display metadata

Date: 2026-09-19

Status: accepted; implemented, tested and independently reviewed.

## Context

Private route preparation is now connected to the browser. Quick Signal choices
cannot use opaque anchor UUIDs as product labels. ADR 0031's catalog deliberately
contains only coordinates/categories and rejects arbitrary extra fields. Existing
route admission and catalog-authoritative signal writes must remain unchanged.

## Decision

Add one optional, bounded, plain-text `displayLabel` field to the operator-selected
catalog and immutable anchor model under `ANCHOR_DISPLAY_METADATA_SPEC.md`.
Absent labels preserve existing catalogs and internal behavior; they do not permit
a future UI to fabricate a useful name. Explicit null and malformed labels fail
closed. Labels remain redacted in diagnostics and absent from existing API DTOs,
resolved-anchor output and persisted contexts.

A future owner choice API must separately authorize current consent/context and
catalog provenance, serve only bounded relevant metadata, and define exact-context
issuance semantics. Issuing a grant is not a read-only catalog lookup. No current
flag activation, public output, schema migration or retention change follows.

## Consequences

Operators can prepare reviewed readable metadata before a contribution UI is
implemented. Unlabeled catalogs remain usable for existing private binding while
choice readiness remains gated. Label changes require a new catalog version and
consistent replica deployment. Character validation rejects the specified control,
format and separator categories; it does not eliminate all invisible or confusable
Unicode text or verify public suitability/geographic accuracy. Operator readability review and
real regional catalog validation remain release gates.
