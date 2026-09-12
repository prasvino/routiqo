# Maps, directions and connection loss

Routiqo Live planning update: the first active-journey experience adds a LIVE list
before collective map overlays. See `docs/features/live/ROUTIQO_LIVE_SPEC.md` and
ADR 0022. Later moment markers use the same authorized projection and coarse
anchors; no stranger dots, finer zoom-derived member queries or public counts.
Keep route geometry/provider work separate from social evidence and maintain
freshness/offline distinctions. Full on-device navigation and downloaded maps
remain planned work but do not block the first useful LIVE list.

## Confirmed target and migration acceptance

ADR 0021 supersedes the Mapbox provider preference. Target: MapLibre GL JS on web,
MapLibre React Native on mobile, self-hosted Valhalla/Photon, and regional
OSM-derived vector archives delivered by Martin. Own-host style JSON, sprites,
glyphs and tiles; do not leave hidden third-party asset references. Preserve
attribution for every dataset/style/font. Public OpenFreeMap is an optional,
explicitly chosen prototype source, never an automatic production fallback.

Migration must preserve every interaction, geometry/step bound, cancellation,
timeout, account reset and accessibility behavior documented below. Update provider
identifiers/contracts, imports/CSS/configuration, UI copy and privacy disclosures
alongside adapters; distinguish MapLibre (renderer), Valhalla (routing) and OSM
(data attribution). Remove token requirements only when their replacement works.
No Mapbox setup is required for future development of the selected stack.

Downloaded maps use reviewed, bounded MapLibre offline packs against our own tile
endpoints first, subject to a pinned-version Android spike. PMTiles archives may
back the tile server; direct native PMTiles sources do not use standard offline-pack
downloads/caching. Direct-file downloads require their own versioned lifecycle.
Include styles/fonts, quotas, resumability, integrity checks, atomic updates and
deletion. Separate on-device routing graphs/engine and guidance from map resources.
Test cold-start airplane mode, interrupted downloads, storage exhaustion, revoked
location permission, region boundaries and safe off-route behavior. Never fabricate
reroutes when offline engine/data are unavailable. See OFFLINE_ARCHITECTURE.md.

## Current web route review

The web renderer uses MapLibre; the opt-in backend configuration now selects guarded Valhalla and Photon with mandatory operator settings (ROUTING_RUNTIME_SPEC.md). Regional services and data remain pending. Historical Mapbox cache disclosures below remain relevant to retained browser data.

The authenticated web route planner uses MapLibre maps, explicit alternative
selection and a provider-supplied directions list with manual Previous/Next review.
This is route review, not GPS-triggered turn announcements or native navigation.
All directions offers direct selection of a provider step, marks the selected item
with `aria-current="step"`, and moves focus to the current instruction. Previous/Next
continue from that selection. Re-selecting the current route alternative preserves
the review position; choosing another alternative starts at its first step. These
controls use loaded data only, including offline, with no location or route requests.
Keep the existing account, origin, CSRF, rate and bounded-body controls.

Directions requests use `steps=true&language=en`. Normalize one leg's bounded steps
into instruction (trimmed, 1–500 characters, no controls), distance/duration
(finite nonnegative), and maneuver location (valid longitude/latitude pair).
Maximum 500 steps per route, three route options, 10,000 overview coordinates.
Malformed supplied steps reject the entire result. Older application responses
without steps remain readable as an empty list; never invent instructions.

MapLibre GL JS 6.9.0 loads only after explicit Show map, using the bounded same-origin style configuration described below. Local bundled CSS, configured dataset attribution, route line, endpoint markers and bounded fit view remain required. No public Mapbox map token is used.
Ignore stale imports/events and remove maps/observers on unmount/account changes.
Each explicit map load has a 20-second deadline spanning SDK import, style and initial route-source readiness. Timeout removes an unfinished map, invalidates late callbacks, preserves
directions and offers explicit retry. Ready maps do not expire on this deadline.
Keep token/provider errors out of UI/logs. No geolocation control or hidden watcher.
After explicit map load, selecting an alternative updates the existing map's route
source, endpoint markers and bounds without recreating its renderer. The newest
selection wins if the SDK or style is still loading. This works with loaded map
resources during connection loss; tiles for newly viewed areas may be unavailable.
Offline tile errors after readiness retain the map and directions. Invalid route
geometry must not leave a previous route displayed as the selected alternative.

Permit same-origin geolocation through Permissions-Policy, keeping microphone and
camera denied. Permission remains browser-controlled and requested only from an
explicit button. A one-time local position may be obtained offline; searching and
calculating require connectivity. Do not request real location during agent QA.

## Offline behavior and boundaries

While the route view stays open, connection loss preserves the chosen result,
directions and review position. Disable new place/route requests; announce offline
state and that new map tiles and rerouting need a connection. Reconnection updates
the banner without silently recalculating or changing the selected alternative.
Network failure during an explicit recalculation preserves the previous route and
labels it as the last successful result. Changing endpoints/mode clears that result.
An explicit Swap starting point and destination action exchanges both selected
places, their input text and attribution. It requires both selections and no pending
operation, works offline, clears old estimates/matches, and never calculates or
requests location automatically. Reversed directions must come from a new provider
calculation; never reverse the old instruction list or reuse its travel time.
Connection loss aborts pending network work and releases its busy state immediately;
late responses cannot replace retained directions or interfere with an explicit retry.
Reconnection never retries automatically. A pending explicit local location reading
is independent of network connectivity and is not cancelled by an offline event.
During search, calculation or a one-time location reading, Cancel request aborts
the current work, releases busy state and ignores late callbacks. Cancellation
preserves existing selections and loaded route estimates; a location reading has
already cleared its previous origin when started. Cancelling a browser location
reading cannot dismiss the browser/OS permission prompt. No automatic retry occurs.
No automatic upload, service worker, tile scraper, GPS watch or route persistence.
Clear route planning aborts pending work, empties temporary endpoint text/selections,
matches/attribution and estimates, restores driving mode, and unmounts the map.
Late request responses cannot repopulate the view. This explicit action works
offline and does not erase saved journeys or browser map caches; its
status message states the cache limitation.

Earlier Mapbox GL versions may have left CacheStorage tiles and localStorage event metadata. These legacy caches may outlive the renderer migration, account switching or account deletion. Current MapLibre resources may remain in ordinary browser HTTP caches; these are not Routiqo route downloads. The explicit Show map disclosure explains this;
clearing site data is the available complete browser-storage cleanup action.
Do not claim zero persistence or disable undocumented SDK internals.

Reloading/closing the page loses this in-memory route. ADR 0021 supersedes the
former native Mapbox dependency. Downloaded regions require the selected MapLibre
integration and download/storage management; offline rerouting additionally needs
an on-device engine/graph and Android validation.
Temporary geocoding results must not be persisted. These remaining gates prevent
claiming this web slice completes the full maps/navigation roadmap.

## UX and verification

Visual thesis: a calm map workspace with one blue route and readable directions.
Content: endpoint search, travel mode, route options, map, directions, connection state.
Interaction: explicit map load, immediate route/step selection, no ornamental motion.
Use existing tokens, responsive map sizing and accessible button/status semantics.

Test normalization and malformed provider responses, selection/reset, offline and
reconnect behavior, retained results after request failure, map lifecycle/cancellation,
missing token/WebGL/errors, and manual step bounds. Inspect rendered desktop and
small-width states using synthetic routes. Live map/provider and native QA require
configuration; fixtures are not evidence of provider success.

Threats: T01/T02 authentication/account isolation, T04/T12 private endpoints and
map viewport disclosure, T13 stale callbacks, T14 request/geometry/step bounds,
T19 no unreviewed persistence. Map display sends viewport/tile requests to Mapbox
only after explicit action; no Routiqo social presence or journey starts.

References checked 2026-09-12:
- [Directions v5](https://docs.mapbox.com/api/navigation/directions/)
- [Mapbox GL JS](https://docs.mapbox.com/mapbox-gl-js/guides/)
- [Native offline maps](https://docs.mapbox.com/help/dive-deeper/mobile-offline/)
- [Geocoding storage](https://docs.mapbox.com/api/search/geocoding/#storing-geocoding-results)

## Web MapLibre migration acceptance

Replace the web renderer/CSS dependency with pinned MapLibre GL JS. Map rendering
uses NEXT_PUBLIC_MAP_STYLE_PATH, a same-origin absolute /maps/...json path with no
query, fragment, credentials, protocol-relative form or path traversal. Missing or
invalid configuration shows the existing unavailable state without importing the
SDK. No public default tile/style service and no Mapbox map token. Backend search
and routing remain on their current adapter until their own migration.

Keep explicit Show map, route/marker/source reuse, the20-second deadline,
cancellation/stale guards, offline degraded details, and generic errors. Reject
external or out-of-namespace resource URLs in transformRequest. Same-origin cookies may accompany requests; static handlers must not log credentials.
This hook is not proof of redirect containment: the trusted deployed /maps service
must serve static resources without redirects, external imports or dynamic URLs.
Verify actual browser network behavior before release. Do not point map resources
at authenticated APIs or place private content in the map asset namespace. Hosting
and browser caches remain disclosed; replacing the SDK does not delete old Mapbox
browser caches. No geolocation watcher, live tiles or external fallback in tests.

MapLibre 6 uses a separate module worker. Web dev/build commands prepare the pinned
worker and shared sibling under `/maplibre/6.9.0/` with its license; the renderer
sets that fixed same-origin worker URL before creating a map. These are application
code assets, separate from the `/maps/` map-data namespace. Upgrades must review
both worker packaging and the renderer URL. Do not rely on Turbopack to emit the
worker's imported sibling. The initial 20-second deadline must include route-source
loading, not merely base-style loading.
