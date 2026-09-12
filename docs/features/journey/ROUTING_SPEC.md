# Route planning integration

Purpose: let a traveller deliberately request a route between selected places, independently of social presence. Route requests must never publish presence or start a journey. Precise coordinates belong only to the requesting traveller and the configured routing provider; do not place them in logs, analytics, public presence or backups by default.

Target provider: self-hosted Valhalla, as confirmed in ADR 0021. Current code still
uses Mapbox Directions v5; migration is pending. Support driving, walking and
cycling by explicit adapter mapping to Valhalla costing modes. Decode and validate
provider geometry, convert distance to metres and duration to seconds, and normalize
maneuvers into the existing bounded domain model. Do not assume provider wire
formats or units match. Results remain estimates, not guaranteed road conditions
or live traffic. Reject requests outside the loaded region with a clear coverage
outcome; distinguish that from no route and unavailable service.

Application limits: exactly two endpoints initially, finite longitude[-180,180]/latitude[-90,90], distinct points, at most three routes and10000 geometry positions per route. Reject invalid/missing geometry, nonfinite/negative distance or duration and provider failures; never manufacture a straight-line route or travel time as a fallback. Strip unused provider fields. Preserve explicit provider identity and fetch time in the private result. A no-route result is distinct from unavailable service.

Provider configuration is external; absent configuration or an unavailable regional
graph must produce an unavailable state. Use one explicitly configured internal
service origin, never a request-supplied URL or public demo fallback. Internal
loopback/service-network HTTP may be used in local Docker; deployed trust and TLS
boundaries require review. Refuse redirects and avoid logging endpoint/query URLs.
No Mapbox token is needed for the target provider. Authenticated endpoints retain
rate limits, no-store, bounded bodies and cancellation. Migrate hard-coded provider
identifiers in controllers, OpenAPI/generated types and client validators together;
provider identity must be a validated supported value, not arbitrary input. Native
guidance and on-device rerouting remain separate from the server routing service.

Existing Mapbox transport acceptance (retain these bounds during migration): POST `/api/v1/routes` requires a verified session and matching account header, exact origin and CSRF. Requests are bounded to 20 KiB; provider and browser response bodies to 1 MiB. Account quota is 20 calculations per minute using the shared database rate gate. Provider redirects are refused and its request timeout is 10 seconds. Invalid inputs are rejected before calling Mapbox. Browser cancellation prevents posting after an abandoned CSRF setup and aborts active fetches. Empty routes mean no route found; authentication, throttling and service failures remain errors. Synthetic HTTP/provider tests cover these boundaries; real provider verification remains pending; the planner UI is mounted, with live authenticated validation still required.

Provider-identity migration acceptance: route results permit only mapbox/valhalla;
place results permit only mapbox/photon. Each configured Java adapter declares its
own typed identity; controllers emit it and clients preserve it after validation.
Unknown/cross-purpose identities fail closed. Retain existing Mapbox identity while
its adapter is active. Adding identifiers does not enable new services or imply a
Valhalla/Photon request occurred. Route estimate labels follow response identity;
map renderer/provider disclosure remains separate until rendering is migrated.

## Valhalla adapter acceptance

The initial Valhalla slice is unmounted. No browser/provider configuration changes
or real requests are enabled until regional coverage and paired service rollout
are reviewed. Constructor accepts a fixed operator-owned HTTP(S) origin only:
host required, bounded length, no credentials/query/fragment/non-root path, valid
port. POST `/route` with bounded JSON; never place traveller coordinates in a URL.
The application owns costing (auto/pedestrian/bicycle), two break locations,
kilometer units, English instructions and at most two alternatives. No dynamic
request URL, public demo, credential forwarding or automatic retry/fallback.

Native Valhalla JSON uses encoded polyline6; decode with bounded varints/positions,
validate latitude/longitude and reject truncated/overflow/invalid encodings. Do not
assume GeoJSON shape support in the native JSON format. Require one leg per route,
up to three routes,2–10000 geometry positions, at most500 valid maneuvers and
bounded well-formed instructions. Validate begin/end shape indices and derive
maneuver positions from checked geometry; convert km to metres and preserve
seconds. Enforce expected units/status and strict duplicate/trailing/UTF-8 parsing.
Malformed alternatives fail the whole result. Never manufacture route geometry,
travel duration or guidance. Only the verified no-path code442 under HTTP400 is
an empty result; unexpected provider statuses/errors remain unavailable and are
redacted. Missing regional graph/out-of-coverage classification is a separate
required integration gate, not inferred from arbitrary provider errors.

The shared POST transport preserves HTTP400 for adapter classification but refuses
redirects/other statuses. It caps request UTF-8 at20KiB, response at1MiB and applies
the whole-operation10-second deadline with cancellation on timeout/interruption.
No response wrapper may print its private body. Synthetic tests cover mode mapping,
units, alternatives, decoder/maneuver bounds, failure redaction and actual loopback
POST transport. Reference: [Valhalla route API](https://valhalla.github.io/valhalla/api/route/api-reference/).
The next independent prerequisite is documented in ROUTING_COVERAGE_SPEC.md: an unmounted provider decorator enforces a conservative configured region both before requests and on normalized route geometry/maneuvers. Runtime activation and an explicit browser coverage outcome remain separate.
