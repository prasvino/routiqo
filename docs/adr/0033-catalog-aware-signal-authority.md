# ADR 0033: Catalog-aware signal authority

Status: accepted and implemented as an internal, default-off composition. No
public signal endpoint, UI or Live publication is enabled.

## Decision

Provider-validated route contexts carry an optional immutable catalog-version
UUID. Historical and ordinary trusted context replacements remain unvalidated and
store no version; they never infer provenance from matching anchor IDs. Bound
replacement requires a non-nil version and persists it with the context. Every
replacement still creates a fresh context identity and invalidates pending route
binding work atomically. The V12 column is nullable so existing rows remain
unvalidated, and ordinary replacement explicitly clears a prior version.

A `CatalogSignalService` is configured only with the existing default-off anchor
catalog, routing/web-auth profiles and persistence profile. It delegates to
package-private catalog-aware paths in `SignalStorageService`, preserving the one
account/journey/consent/context/grant transaction and existing storage, budget and
receipt behavior. Issuance derives the complete permitted category set from the
current immutable catalog. It requires matching context provenance and an anchor
present in both current context and catalog.

New acceptance rechecks the same context/catalog version, anchor membership and
the submitted category against current catalog content while holding existing
authority locks. A denial occurs before budget mutation, grant consumption, slot
replacement or receipt insertion. Exact retained private replay remains first and
returns its original stored outcome after catalog, consent or journey changes;
changed retained fingerprints still conflict. Current catalog state governs new
evidence and does not rewrite retained receipts.

The low-level signal service remains available to trusted internal code for
compatibility. An architecture rule forbids API packages from depending on it;
future transports must use the catalog-aware facade.

## Limits

Catalog version reuse for changed content is an operator violation even though
new acceptance also checks current category membership. Cross-replica rollout,
regional catalog operations, public authenticated signal transport, abuse and
moderation controls, revocation delivery and cohort-safe publication remain
release gates.
