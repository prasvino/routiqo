# Route planning integration

Purpose: let a traveller deliberately request a route between selected places, independently of social presence. Route requests must never publish presence or start a journey. Precise coordinates belong only to the requesting traveller and the configured routing provider; do not place them in logs, analytics, public presence or backups by default.

First supported modes: driving, walking and cycling. The Mapbox Directions v5 adapter will request GeoJSON overview geometry, distance in metres and duration in seconds. Mapbox accepts longitude/latitude coordinate pairs and returns route geometry; provider results remain estimates, not a guarantee of road conditions. Source checked2026-09-09: https://docs.mapbox.com/api/navigation/directions/.

Application limits: exactly two endpoints initially, finite longitude[-180,180]/latitude[-90,90], distinct points, at most three routes and10000 geometry positions per route. Reject invalid/missing geometry, nonfinite/negative distance or duration and provider failures; never manufacture a straight-line route or travel time as a fallback. Strip unused provider fields. Preserve explicit provider identity and fetch time in the private result. A no-route result is distinct from unavailable service.

Provider configuration is external; without a token the feature must say unavailable. Do not use a public unauthenticated credential proxy. Authenticated route endpoints need account rate limits, no-store, bounded requests/responses and cancellation. Endpoint selection, provider disclosure, route preview, permission denial and offline saved-route policy require UI verification before release. Do not begin GPS watching on page load. Native SDK/device integration and navigation guidance remain separate work.
