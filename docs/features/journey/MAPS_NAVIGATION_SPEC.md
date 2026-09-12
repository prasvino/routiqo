# Maps, directions and connection loss

## Current implementation slice

Extend the authenticated web route planner with Mapbox maps, explicit alternative
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

Mapbox GL JS 3.30.0 loads only after explicit Show map. Use a separate public,
origin-restricted `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN` (`pk.` only), passed per map;
never expose the backend routing token. Fixed streets-v12 style, local bundled
CSS, provider attribution/logo, route line, endpoint markers and bounded fit view.
Ignore stale imports/events and remove maps/observers on unmount/account changes.
Each explicit map load has a 20-second deadline spanning SDK import and style
readiness. Timeout removes an unfinished map, invalidates late callbacks, preserves
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
offline and does not erase saved journeys or Mapbox-managed browser caches; its
status message states the cache limitation.

Mapbox GL itself uses browser CacheStorage for map tiles and localStorage for SDK
event metadata. These are provider-managed caches, not Routiqo route downloads;
they may outlive this view, account switching or account deletion. Ordinary browser
HTTP caches may also retain tiles. The explicit Show map disclosure explains this;
clearing site data is the available complete browser-storage cleanup action.
Do not claim zero persistence or disable undocumented SDK internals.

Reloading/closing the page loses this in-memory route. True downloaded offline
regions and offline rerouting require the native Mapbox Maps/Navigation SDKs,
provider configuration, download/storage management and Android device validation.
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
