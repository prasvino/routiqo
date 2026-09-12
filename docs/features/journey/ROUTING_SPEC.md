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
