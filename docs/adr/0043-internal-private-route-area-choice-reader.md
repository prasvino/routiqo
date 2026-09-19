# ADR 0043: Internal private route-area choice reader

Date: 2026-09-19

Status: accepted; internal implementation tested and independently reviewed.

## Context

ADR 0042 permits readable catalog metadata without exposing it. Populating a
future private choice interface through command issuance would create grants and
consume submission budgets for a read. A raw catalog or route-context lookup also
lacks the combined consent, restriction and provenance checks required for choices.

## Decision

Introduce only an internal read-only Route Update application service and immutable
minimized snapshot under `PRIVATE_ANCHOR_CHOICES_SPEC.md`. Reuse the established
owned-journey callback and consent/context/restriction participant order. Verify
matching identities and active/on/non-suspended authority, current catalog provenance,
complete labels and expiry sampled after participant reads. Deny the whole snapshot
on missing metadata; no fallback name or silent subset truncation.

Return bounded exact context/consent versions and label/category choices, with no
actor identity, geometry, endpoints, coordinates or catalog version. Keep diagnostics
redacted. Do not add Spring wiring, HTTP, caching, writes, providers, grants or budgets.

## Consequences

The application prerequisite can be tested without exposing new data. A future
browser endpoint still needs its own default-off configuration, authentication,
no-store DTO, request quotas and transport tests. A snapshot is not a capability
or durable authorization. Existing issue-by-anchor semantics do not enforce an
exact displayed-context CAS; define that coordinated write precondition or grant
mismatch policy before mounting Quick Signal UI. Public privacy, evidence independence,
reporting and anti-Sybil gates remain unchanged.
