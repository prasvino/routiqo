# Open-source routing runtime

The opt-in `web-auth & routing` configuration constructs Photon place search and
Valhalla routing together. Valhalla is always wrapped in the regional coverage
guard. There is no provider fallback or browser-supplied destination. Legacy
Mapbox adapters may remain tested source, but are no longer selected by this
configuration. Deploy backend and browser disclosure changes together.

Require `ROUTIQO_PHOTON_ORIGIN`, `ROUTIQO_VALHALLA_ORIGIN` and all four
`ROUTIQO_ROUTING_REGION_WEST`, `SOUTH`, `EAST`, `NORTH` values. Validate the complete
bundle before exposing either provider. Origins are absolute HTTP(S) origins
without credentials, path beyond `/`, query or fragment. Bounds follow
ROUTING_COVERAGE_SPEC.md. Missing or malformed values fail startup without
echoing values or retaining parsing exception causes. No startup network request,
default public service, guessed regional bounds or Mapbox token is permitted.

Operator configuration is a trusted boundary: URL syntax does not establish
ownership, DNS safety or graph completeness. Use only Routiqo-controlled services,
private service ports, reviewed egress and TLS/network isolation. Photon search
URLs contain query text; disable or redact access logs at every service/proxy.
Do not log Valhalla request bodies. Restrict direct service access so callers
cannot bypass the application's authentication and quotas.

The planner and privacy page disclose Photon search and Valhalla endpoint
processing before requests. Optional one-time browser location is sent only on
route calculation. Existing memory-only results, attribution, cancellation,
offline behavior and bodyless coverage errors remain unchanged.

Acceptance: profile-disabled startup requires no routing settings; enabled startup
rejects incomplete or invalid bundles; a valid synthetic bundle selects exactly
Photon and guarded Valhalla without network access or a Mapbox credential.
Exercise out-of-region rejection before transport and retain existing provider,
HTTP authorization, quota, browser and offline regressions. Real regional services,
dataset quality, deployed logging/egress and browser/device QA are separate gates.
