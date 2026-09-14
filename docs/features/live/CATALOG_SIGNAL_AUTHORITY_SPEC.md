# Catalog-aware signal authority

Status: internal configured facade implemented and tested. ADR 0035 adds a
separately default-off private owner command transport through this facade; no UI,
flag activation or public publication exists.

## Context provenance

V12 adds nullable nonnil catalog_version UUID to live_route_context. Extend the
stored context envelope with optional catalog provenance, retaining a compatible
three-argument constructor for unvalidated internal contexts. Historical and
ordinary trusted replace writes have no provenance; never infer or backfill one.
Add an intentional participant operation for provider-validated replacement that
requires a nonnil catalog version. The two-transaction RouteBindingService uses it
with the resolver result version after existing authority/attempt checks. Ordinary
replace always clears provenance. Every replacement still generates a new context
ID and atomically invalidates pending binding attempts at the JDBC participant.
No context-to-receipt or grant cascade is added; expiry cleanup remains leaf-only.

## Catalog-aware facade

Add a configured CatalogSignalService (or equally clear name) under the existing
default-off anchor catalog configuration and explicit persistence profile. Require
real SignalStorageService and the same immutable server RouteAnchorCatalog as the
resolver. No order-sensitive ConditionalOnBean and no permissive default catalog.
Its issue input is actor, journey and anchor ID; derive the complete allowed
category set from the current server catalog, never from request fields. Acceptance
uses the existing exact fingerprint and trusted server lifetime parameters.

Reuse SignalStorageService transaction/receipt/budget logic through package-private
catalog-aware entry points/shared private helpers; do not duplicate persistence,
acquire nested authority transactions or introduce arbitrary policy callbacks.
Keep existing trusted low-level entry points compatible, but public signal APIs
must be forbidden from depending on that low-level service. Add a meaningful
architecture rule or equivalent enforced boundary for API-layer callers.

Within issuance's existing account/journey/consent/context transaction, require
current sharing and context plus context.catalogVersion == current catalog version,
registered anchor in both the context and current catalog, and derive nonempty
permitted categories from that catalog. Existing command lifetime and quota rules
remain. Missing provenance, removed anchor or changed catalog denies before any
grant or budget mutation; never manufacture catalog provenance from matching IDs.

New acceptance under current locks requires the same provenance/anchor checks and
current catalog permission for the submitted category, in addition to all existing
grant/admission/fingerprint/current-consent/context checks. Denied acceptance must
not consume a grant, replace an active slot, charge budget or create a receipt.
Run these checks in the existing transaction, not in a preflight outside authority.

Retained exact private replay still resolves first and returns its original stored
outcome even after catalog changes/removal, consent withdrawal or completion.
Changed retained fingerprints still conflict. Current catalog eligibility governs
new evidence, not already retained private receipts. Withdrawal continues through
the existing owner-scoped transactional path; facade may delegate it unchanged.
Do not add public outputs or change receipt/evidence TTLs or retention semantics.

## Tests and operational limits

Test V12 null/nonnil constraints, existing rows stay unvalidated, optional-domain
validation/redaction, raw replacement clearing provenance, and production-profile
provider binding storing the actual catalog version. Use real configured facade,
PostgreSQL and consent/context/signal services. Cover default-off bean absence,
configured binding-to-issue-to-accept success, exact catalog-derived categories,
unvalidated context, removed anchor, different catalog version, disallowed category,
concurrent/after-lock context or consent replacement, no partial mutation on denial,
retained replay after category/anchor removal or version change, changed retry
conflict, and direct context replacement invalidating prior catalog-aware writes.
Existing low-level regression tests remain; no mock authorization or production
synthetic catalog. Only local fixture/provider and disposable PostgreSQL are used.

Catalog versions must identify immutable reviewed content across replicas; reuse
of a version for changed locations is an operational violation. This phase checks
the configured catalog snapshot and does not implement distributed rollout or
claim globally synchronized configuration. Current categories are rechecked even
if an operator erroneously removes one without rotating version. Real regional
catalog operations, public signal publication, abuse/moderation,
revocation delivery and cohort publication remain gates.

Create ADR0033 and update current Live/context/signal/roadmap/security/threat records.
Run focused then full relevant Java checks/bootJar, secret/diff checks and independent
review. Preserve running preview and pre-existing next-env.d.ts diff; no frontend
build, dependency reinstall, user DB migration, timer, push or activation.
