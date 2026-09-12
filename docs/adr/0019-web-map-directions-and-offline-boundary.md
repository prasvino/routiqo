# ADR 0019: Web maps, directions and offline boundary

Status: accepted, 2026-09-12.

Use Mapbox GL JS 3.30.0 for the existing authenticated web planner, preserving the
provider preference and attribution. Separate the browser's public map token from
the backend routing credential; no token proxy or automatic map initialization.
Expose validated Directions step instructions through the existing bounded API.

Keep calculated routes in memory through connection loss. Do not treat a loaded
map as an offline download or promise rerouting without connectivity. Do not cache
temporary geocoding responses. Full offline map regions/navigation use the native
SDK path and require configured providers and device verification. See
`MAPS_NAVIGATION_SPEC.md` for current acceptance and remaining gates.

Review disposition: accept the Medium provider-managed browser cache boundary for
explicit map display, with disclosure before loading and on the privacy page.
Map tiles/event metadata may persist across accounts; calculated route geometry,
instructions and temporary geocoding results are not written by Routiqo. Mapbox's
documented clearStorage does not guarantee clearing ordinary browser caches and
does not remove all SDK event data. Do not use private SDK switches or claim full
deletion. Provider-cache lifecycle remains a release privacy verification item;
clearing site data is the documented local removal action.
