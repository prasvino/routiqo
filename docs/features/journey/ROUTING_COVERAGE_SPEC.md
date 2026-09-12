# Regional route coverage guard

Purpose: enforce an explicit service region before activating regional routing.
This guard is independent of the provider adapter and does not download a graph,
infer graph completeness, publish location or enable an HTTP configuration.

`RoutingRegion` defines an operator-reviewed longitude/latitude rectangle. All
bounds must be finite and valid geographic coordinates, minima strictly below
maxima, and longitude span less than180 degrees. This initial regional pilot
does not support antimeridian-spanning or global coverage. Boundaries are inclusive.
The rectangle must be derived conservatively from the deployed regional dataset;
it is not automatically inferred from Photon results or a provider error code.

`RegionLimitedRouteProvider` wraps an existing RouteProvider and preserves its
typed identity. Before any provider request, validate both traveller endpoints
against the region. After normalization, validate every returned geometry and
maneuver point. Any out-of-region alternative rejects the whole response, not a
silently reduced set of results. Empty provider results remain no-path. Never
clip, invent or persist geometry. Return immutable results without modifying the
delegate's records. Failure messages and string representations contain no
coordinates. Missing region/provider configuration fails closed.

The first slice is unmounted. Before activation, map the dedicated coverage
exception to an explicit HTTP/client outcome, add fixed runtime configuration and
provider disclosures, and exercise authenticated routes. The exception alone is
not a completed browser error flow. Actual region boundaries, cross-border routes,
dataset versions and known-route quality remain deployment verification gates.

Synthetic acceptance tests cover invalid/wrapping/overwide regions, inclusive
boundaries, rejecting endpoints before transport, identity/no-path preservation,
out-of-region geometry/maneuvers/alternatives, immutable results and redacted
diagnostics. Existing auth, origin/CSRF, quotas and response bounds remain required
when mounted; this guard does not substitute for them.
