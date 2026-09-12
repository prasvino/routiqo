# Private route endpoint lookup

Purpose: let travellers resolve a typed city, street or address into a selectable endpoint before deliberately calculating a route. Curated discovery descriptions are not authoritative coordinates. Target lookup uses self-hosted Photon per ADR 0021; current Mapbox Geocoding v6 code awaits migration. POI expansion remains separate from this endpoint-search slice.

Search only on an explicit submit, never on every keystroke or page load. After migration, explain that search text goes to Routiqo-controlled search infrastructure; current UI must retain its truthful Mapbox disclosure until replaced. No location permission or IP-based proximity is needed. Keep results in the current interaction only; do not store them in drafts, backups, server tables, logs or analytics. Results remain temporary by product privacy policy even when self-hosted; no new retention is authorized by changing providers. Photon supports autocomplete, but explicit submit remains the accepted interaction until a separate bounded/debounced UX decision.

Input: trimmed text, 3–256 characters, at most20 letter/number groups, no semicolon or control characters. Use an explicitly configured fixed service origin, never a request-supplied URL. Encode all user text and request at most5 results. No public Photon/Nominatim demo fallback; no third-party proximity lookup. Review deployed TLS/service-network boundaries and refuse redirects. Validate FeatureCollection/Point geometry, finite coordinates, bounded nonempty provider ID and display label, unique IDs, and preserve attribution. Reject malformed/oversized responses; an empty result set differs from an unavailable provider. Responses are bounded at256 KiB. No tokens or original provider errors reach the browser.

Use the existing opt-in routing configuration, session/account/origin/CSRF guards, no-store, bounded POST body and a database-backed account rate gate. No journey or social presence is created by lookup. Tests use synthetic fixtures and fixed-host assertions, never real credentials. UI must display loading, no matches, offline, authentication, throttling and service error states; selecting a result must remain explicit. Clear old selections when text/account changes and ignore cancelled or stale responses.

Provider direction and checked primary sources: ADR 0021. Photon IDs/labels must be normalized deterministically into the current contract; reject duplicates and malformed data. Validate regional coverage and English/Tamil public test queries before rollout. The embedded OpenSearch index is geocoding-specific, not an application-wide search dependency.

UI composition: calm, cardless disclosure within the authenticated Trips journey workspace, using existing typography and action color. Content: Plan a route summary; short provider/privacy disclosure; From/To search fields with plain selectable result lists; travel mode and Calculate route; estimate rows with freshness and provider attribution. Interaction: existing button/focus transitions and native disclosure opening; avoid ornamental motion. Changing either field or mode clears old estimates, selecting a match clears its result list, and account changes remount the form. No speculative basemap or turn-by-turn guidance is shown. Screen-reader status announces loading/no matches/results and errors. Test explicit selection, stale search cancellation, offline behavior and account resets using component fixtures.

Optional current-location origin: request a single browser position only after the traveller presses Use my current location. Explain that it becomes the private starting point and is sent to the selected routing service only on Calculate. Never watch location or persist the position. Use a bounded10second request, maximumAge0, no high-accuracy requirement; ignore late callbacks after cancellation/account change. Reject invalid coordinates/accuracy and positions older than30seconds. Accuracy worse than1000metres asks for a typed place instead. Permission denied, timeout, unsupported browser and unavailable location have actionable messages. Test only mocked browser callbacks; do not request the user's actual location during agent QA.

## Photon adapter implementation acceptance

The first backend slice is a tested adapter, not a live configuration switch.
RoutingConfiguration remains on Mapbox until paired configuration and truthful UI
provider disclosure are integrated. Photon consumes an explicitly constructed
fixed HTTP(S) origin (host required; no userinfo, query, fragment or non-root path;
bounded length and valid port). This operator-controlled destination is a trust
boundary; deployment must restrict egress and protect DNS/service routing. Never
accept it from a browser payload. Use `/api` with encoded q, limit=5 and lang=en;
no proximity coordinate, credentials, autocomplete dispatch or public demo fallback.

Normalize GeoJSON Point results into the existing contract. Require positive
integral OSM IDs and N/W/R type; namespace IDs as photon:<type>:<id> and reject
duplicates. Build a deterministic, bounded label from name/address/locality fields;
nameless address/street results may use address fields, but no placeholder label.
Validate supplied field types/control characters and final 512-character label.
Require strict JSON without duplicate keys/trailing data, at most five features,
valid finite coordinates and at most256 KiB UTF-8 response bytes. Empty features
means no matches; malformed data or transport failure means unavailable without
provider details/cause. Preserve thread interruption. Attribution is fixed to
OpenStreetMap contributors; ignore provider HTML and unused properties.

The shared infrastructure transport is provider-neutral, retaining its streaming
byte cap, request/connect deadlines and no-redirect behavior. No database writes,
new HTTP endpoint, profile activation, service download or real query occurs in
this slice. Synthetic tests cover hostile URL/input/response and failure paths.
Live regional English/Tamil query quality, service logging/TLS/egress and rollout
remain separate gates. Wire format reference checked2026-09-12:
https://github.com/komoot/photon/blob/master/docs/api-v1.md.
The10-second transport deadline covers response-body completion, including stalled/chunked bodies after headers; timeout or thread interruption cancels the underlying asynchronous HTTP exchange. Header-only timeouts are insufficient.

The OSM type segment is normalized to lowercase (`photon:n:123`, `photon:w:456`,
`photon:r:789`). Source confirmation: Photon's
[GeoJsonFormatter](https://github.com/komoot/photon/blob/master/src/main/java/de/komoot/photon/searcher/GeoJsonFormatter.java)
emits the FeatureCollection root and Point fallback used here. Label fields are
validated before whitespace trimming, including escaped malformed surrogate pairs;
valid multilingual text remains supported. Synthetic Unicode tests are not proof
of regional search relevance or translation quality.
