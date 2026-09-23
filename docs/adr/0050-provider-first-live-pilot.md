# ADR 0050: Provider-first LIVE pilot

Date: 2026-09-23
Status: accepted implementation direction; runtime activation requires pilot verification

Routiqo will introduce a narrow active-journey provider-alert list before traveller-derived public Live Moments. The initial source is NDMA SACHET CAP alerts for the Chennai district area. This route presents district-wide official warnings with issuer, validity and source link; it makes no assertion about a specific road, traveller presence or Quick Signal corroboration.

The core API owns source retrieval, validation, attribution and authorization. The web app reads through its existing authenticated journey proxy. The feed goes through a fixed outbound host, bounded XML parser and conditional ETag cache. Only opted-in `web-auth` persistence deployments with an explicit provider flag expose the endpoint. Default preview remains closed. Read authorization is current on every request; provider access does not require private contribution consent. No private signal or location data is transmitted to NDMA.

This changes the ordering of the first LIVE utility test without changing ADR 0022's traveller-derived publication gate. Provider rows and future traveller moments must remain distinguishable. The approved provider pilot does not approve deterministic crowd aggregation, physical-presence claims, public Quick Signal evidence, unrestricted provider integrations or a fifth navigation tab.

The provider feed's own data quality and uptime remain external dependencies. Empty is honest; failed or stale source retrieval returns no fresh alert. Staging must verify Chennai coverage, terms/attribution, response performance, accessibility and current authenticated lifecycle before activation.
