# ADR 0021: Open-source mapping direction

Status: selected architecture direction, 2026-09-12; migration not implemented.

The user selected open-source maps and requested an assessment of
map-alternate-discussion.txt. That attachment is a proposal, not verified technical
documentation. This decision supersedes the Mapbox preference in earlier roadmap
documents and ADRs 0014/0019 for future implementation. Existing Mapbox code remains
the current implementation until migrated; previous privacy/security bounds remain.

## Selected stack

| Responsibility | Decision | Reason |
|---|---|---|
| Web rendering | MapLibre GL JS | Open-source vector rendering with a similar programming model to the existing web map |
| Mobile rendering | MapLibre React Native / Native | Fits the Expo/React Native architecture; requires a development build and device validation |
| Routing service | Self-hosted Valhalla | Driving, cycling and walking with dynamic costing, maneuvers and a path to regional offline routing |
| Place search | Self-hosted Photon | Search-as-you-type, multilingual search and typo tolerance; keep behind PlaceProvider |
| Basemap data | OSM-derived regional vector tiles, Protomaps PMTiles | Versioned regional artifacts with storage under Routiqo's control |
| Tile delivery | Self-hosted Martin plus own style, sprites and glyphs | Serve archives as ordinary tile endpoints for web/native and offline-pack compatibility |

This is our engineering choice, not a claim that all components are already tested
together. Pin compatible releases after an Android development-build spike. Photon
currently uses OpenSearch and supports an embedded server; its search index is a
geocoding-specific dependency, not permission to introduce application-wide
OpenSearch. Start with a regional import or compatible regional dump; verify dump
availability, update workflow and resource requirements before downloading.

Use OSRM if requirements later narrow to a simpler fixed-profile routing service;
Valhalla is a better fit for Routiqo's planned travel modes and regional/offline
direction. Self-hosted Nominatim is a valid simpler alternative for explicit-submit
address lookup, and may be needed in Photon's custom import/update pipeline. Do
not add both as online services by default. Benchmark Photon address/POI quality
in English and Tamil against public, non-personal regional examples before launch.

## Hosting and privacy

OpenFreeMap's public instance is useful for an explicitly selected prototype: no
registration or key is required, but no SLA is offered. It is not the selected
production privacy boundary. Direct public tile requests reveal an IP and viewed
area; open-source rendering does not prevent that. Own-host all style resources
as well as tiles, and keep route/search requests inside Routiqo-controlled services.
Cloud hosting still involves infrastructure providers; avoid absolute zero-exposure
claims. Minimize access logs and avoid associating tile paths with account identity.

There are no required Mapbox accounts or Mapbox tokens in the selected stack.
Hosting, bandwidth, storage, graph/index builds, updates, backups and operational
work still cost money or machine resources. Preserve authentication, rate limits,
fixed service destinations, bounded requests and generic errors. Self-hosted does
not mean unlimited public APIs or exemption from SSRF and abuse controls.

Do not use public OSM raster tiles for offline downloads. Public Nominatim is not
our app backend: its aggregate limit is one request/second, autocomplete is
prohibited, and confidential queries must not be sent. Photon demo service likewise
has no availability guarantee. OSM attribution/ODbL and the separate licenses of
styles, fonts and data artifacts must be preserved when publishing/distributing.

## Offline delivery is separate from routing

1. Preserve selected routes and instructions through connection loss/restart with
   an explicitly reviewed local storage policy; existing web state is memory-only.
2. Implement bounded, resumable region downloads, manifest/version checks, disk
   quotas, atomic updates, cancellation, deletion and cold-start airplane-mode QA.
   Initially use normal self-hosted tile endpoints with MapLibre offline packs,
   subject to testing the pinned React Native version. Bundle all required style
   resources. PMTiles can remain the server-side archive format.
3. Evaluate directly downloaded local PMTiles as a separate mobile path. Native
   Android supports local files, but PMTiles sources do not support its normal
   offline-pack downloads/caching; do not assume the JavaScript adapter bridges all
   native features. Verify the exact dependency version and file lifecycle.
4. Offline rerouting needs routing graphs and an engine on the phone. Docker on
   our server cannot reroute a disconnected phone. Prototype embedded Valhalla and
   an Android navigation integration before committing to memory/battery/storage
   targets. MapLibre rendering/offline packs alone do not provide guidance,
   map matching, voice prompts, background location or rerouting. MapLibre Navigation
   Android is a candidate to evaluate, not a confirmed Expo/Valhalla integration.

## Migration sequence and acceptance

First generalize provider identifiers in Java controllers, OpenAPI/generated types
and TypeScript validators. Then replace map rendering/style/CSS/configuration and
privacy copy; add bounded Valhalla/Photon adapters behind existing interfaces.
Normalize provider-specific units, coordinate encodings and maneuver fields into
Routiqo's existing domain contract. Do not pass raw provider responses through.

Keep existing cancellation, stale-result, timeout, attribution, manual directions,
account isolation and offline tests. Remove Mapbox imports/endpoints/configuration
only after replacement verification. Current controllers and client validators
hard-code 'mapbox', and route-map.tsx directly loads Mapbox: this is not a two-class
or zero-frontend-change migration.

Next bring up an opt-in regional data stack, validate known routes and place search,
then perform the Android map/offline spike. Start with a Tamil Nadu-area pilot as a
working assumption, including sufficient neighboring coverage for boundary routes;
explicitly reject out-of-coverage requests. Expand after measuring import peak RAM,
disk including double-buffered updates, latency, data freshness and routing quality.
Do not promise Google-style live traffic or transit schedules from OSM alone.

## Sources checked 2026-09-12

- [MapLibre Expo setup](https://maplibre.org/maplibre-react-native/docs/setup/expo/)
- [MapLibre offline manager](https://maplibre.org/maplibre-react-native/docs/modules/offline-manager/)
- [Valhalla overview](https://github.com/valhalla/valhalla)
- [OSRM API](https://project-osrm.org/docs/v5.24.0/api/)
- [Photon features and operations](https://github.com/komoot/photon)
- [Protomaps PMTiles](https://docs.protomaps.com/pmtiles/)
- [Martin tile server](https://maplibre.org/martin/)
- [Native Android PMTiles limitations](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)
- [MapLibre Navigation Android](https://github.com/maplibre/maplibre-navigation-android)
- [OpenFreeMap service terms](https://openfreemap.org/)
- [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/)
- [Public Nominatim policy](https://operations.osmfoundation.org/policies/nominatim/)
- [OSM attribution and license](https://www.openstreetmap.org/copyright)

The attachment's blanket claim that server Mapbox calls require secret-scope tokens
is also inaccurate: scopes depend on the API, not whether the caller is a server.
See [Mapbox token documentation](https://docs.mapbox.com/help/getting-started/access-tokens/).
We select open source for control and architectural fit, not that token claim.
