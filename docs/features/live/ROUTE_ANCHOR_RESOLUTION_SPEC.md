# Provider-backed route anchor resolution

Status: implemented and verified as a default-off internal resolver. This does not mount
HTTP registration, bind a journey context, issue a signal grant or publish data.

> **Direction brief (2026-09-25):** Anchors become Spots. Update (ADR 0067, proposed): Spots ahead are matched on the device against a device-only journey route, reusing this spec's 100 m route match and 1,000 m endpoint exclusion; this server resolver stays with the archived route-binding code, default-off. The Spot catalog (about 150–200 Spots, new `routiqo-spots/1` format) is defined in [SPOTS_SPEC.md](../spots/SPOTS_SPEC.md). See [PRODUCT.md](../../PRODUCT.md).

## Trusted input and configuration

Route Update owns an immutable curated anchor catalog. Each anchor has a nonnil
UUID, finite geographic longitude/latitude, and a nonempty closed set of Quick
Signal categories. The catalog has a nonnil version UUID and 1..512 distinct
anchors. No actor, traveller endpoint, route geometry or membership is stored in
this catalog. ADR 0042 adds optional curated display metadata under
`ANCHOR_DISPLAY_METADATA_SPEC.md`; it does not change resolution or API output.
All records, results and errors redact diagnostics, including any label.

Load a strict UTF-8 JSON catalog from a server-configured local file, at most
256 KiB, with exactly version and anchors; each anchor requires id, longitude,
latitude and categories, with only the optional displayLabel field added by ADR
0042. Explicit null labels are invalid; missing labels preserve older catalogs.
Reject unknown/duplicate/missing/null required fields, trailing
JSON, duplicate IDs/categories, invalid coordinates and category values, and
oversized input with a cause-free generic configuration failure. Read with a bound,
not readAllBytes before checking size. No request-selected file path or URL.
The operator reviews public anchor locations and catalog contents before use.

Production wiring is separately default off via
ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=false. When true, require the existing
web-auth/routing setup and ROUTIQO_LIVE_ANCHOR_CATALOG_PATH. Missing/invalid catalog
fails startup closed. Use the existing configured region-guarded Valhalla provider;
no new dependency, PostGIS migration, demo catalog, Mapbox or permissive fallback.
Require every configured anchor to fall inside the same configured routing region.
Reuse configuration parsing intentionally rather than silently choosing bounds.

## Resolution rule

The internal service accepts a RouteRequest and selected alternative index 0..2.
It obtains fresh routes itself through RouteProvider; it never accepts caller
geometry or a caller-supplied matching anchor list. Only Valhalla identity is
accepted. Validate input before provider work; reject ambient transactions before
making provider calls. Keep all geometry in memory, without logs or persistence.
Do not hold account/journey/database locks while requesting a provider.

Require 0..3 provider alternatives and a valid selected index for a nonempty
response. Empty routes yield an explicit no-route result; an absent requested
alternative conflicts/denies without silently selecting another. Preserve existing
region and bounded provider error semantics with private diagnostics.

For the selected provider geometry, match only curated anchors within 100 metres
of an actual provider vertex, using spherical great-circle distance with mean
Earth radius 6371008.8 metres. Exclude anchors within or exactly 1000 metres of
either requested endpoint or either selected geometry endpoint. Handle longitude
wrap and clamp floating point roundoff; validate all geometry through existing
domain/provider guards. A vertex-only rule is intentionally conservative: do not
infer a match along a long straight segment, invent intermediate geometry, choose
the nearest off-route anchor or expand the radius when nothing matches.

These distances are an explicit relevance/minimization heuristic, not physical
presence proof, accessibility proof, or an anonymity guarantee. Grade separation,
parallel roads, sparse geometry, endpoint inference and real regional route quality
remain pilot validation concerns. No public aggregate is authorized by matching.

Return catalog version and a bounded immutable mapping of matched anchor IDs to
their configured category sets, with an explicit distinction between no route and
route with no eligible anchors. No coordinates, route geometry, endpoints, provider
instructions or actor data in the result. More than 128 matches denies the whole
result rather than silently truncating relevance. Output is an internal snapshot,
not a client capability or durable journey association. No per-request cache.

## Verification and next integration

Test catalog immutability/redaction, strict bounded loader, missing/default-off
configuration, region constraints, correct production provider composition,
alternative validation, no-route versus no-match, exact matching and endpoint
exclusion boundaries, dateline/polar finite-distance handling, sparse vertices,
duplicate/multiple categories, overflow of the 128-match cap and no fallback on
provider failure. Test a real Valhalla adapter against a bounded local synthetic
HTTP fixture, including out-of-region rejection; production has no synthetic data.
Prove ambient transactions make zero provider requests. No external live provider
verification or geographic quality claim can be inferred from fixtures.

Create ADR0031 and update current mapping/Live/status/security/threat/config setup
records, with sample flag false and no real catalog. Run focused and full relevant
Java checks/bootJar and secret/diff checks. No frontend source change is needed;
leave the running port3000 preview and its generated next-env.d.ts untouched.

ADR 0032 now authenticates and budgets the initiating actor, performs fresh
journey/consent/context authority checks after provider work and fences superseded
binding attempts. The next grant integration must revalidate catalog category
eligibility before issuance.
Do not mount this resolver directly as a public authorization endpoint or claim
durable binding, moderation, cache revocation or cohort publication is complete.
