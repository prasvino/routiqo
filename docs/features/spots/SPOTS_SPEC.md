# Spots

Status: proposed, 2026-09-25. Phase 2 of the pilot path; the catalog, Spots
ahead and activity reads are needed for the Diwali 2026 dry run.
**Implemented 2026-09-26 (server and native transport only, default-off):** the
catalog loader, *Catalog delivery* and the *Activity read* endpoint, which returns
every known Spot as `quiet` until posts and signals exist.
**Implemented 2026-09-26 on Android (default-off, `EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED`,
not device-verified):** the `spot_catalog_v1` cache and ETag revalidation,
Spots-ahead matching and ordering, the panel with collapsed/half/full sizes
(explicit buttons, no drag gesture), an in-place Spot detail, activity refresh
and Spot markers. Spot detail shows no posts, signals or contribution actions
yet (posts spec). Official alerts are not built yet.
Known limitations:
- **Routes that double back.** On a route that returns over the same road (an
  out-and-back branch or a U-turn at a divided-highway end), projection picks
  the nearest segment. A Spot, or the traveller, can then snap to the outbound
  leg, and the order is wrong on the return leg. This is unlikely on the
  Diwali corridors. The fix would be to search near the last along-route
  position.
- **Strong ETags required.** The catalog needs a strong ETag end to end. A proxy
  that weakens it (for example nginx gzip) makes every download fail
  validation, and phones keep their cached list.
Decision records: [ADR 0066](../../adr/0066-bounded-http-refresh-for-spot-chat.md),
[ADR 0067](../../adr/0067-on-device-journey-route-and-spots-ahead.md),
[ADR 0070](../../adr/0070-spot-module-and-catalog-delivery.md).
Product rules: [PRODUCT.md](../../PRODUCT.md).

Scope: the seeded Spot catalog, how the Android app finds the Spots ahead on a
journey, Spot live/fading/quiet state, the activity read and its refresh, the
Spots-ahead panel and Spot detail, and official alerts on Spots. What people post
and how it expires is in [POSTS_AND_SIGNALS_SPEC.md](POSTS_AND_SIGNALS_SPEC.md).
Not in scope: Spot chat and the festival room, voice notes, Ask Ahead, Spot
passage, user-suggested Spots, web.

## Catalog

A Spot is a public place on a pilot corridor: a toll plaza, eatery, fuel station,
restroom, bus stand, temple, junction or rest area. The catalog is curated before
each dry run and the launch.

**File** (illustrative values; `ROUTIQO_SPOT_CATALOG_PATH`, loaded at startup, strict JSON, at most
512 Spots and 256 KiB, unknown keys rejected, whole file rejected on any error):

```json
{
  "schema": "routiqo-spots/1",
  "version": "<lowercase UUID, new for every change>",
  "corridors": [{ "id": "gst-trunk", "name": "Chennai – Trichy (GST Road)" }],
  "spots": [
    {
      "id": "<lowercase UUID>",
      "name": "Example Toll Plaza",
      "nameTa": "<Tamil name>",
      "kind": "toll",
      "longitude": 79.9,
      "latitude": 12.7,
      "district": "chengalpattu",
      "corridors": ["gst-trunk"],
      "categories": ["traffic", "queue"],
      "provenance": { "curator": "<name>", "source": "field_visit", "reviewedAt": "2026-10-20" }
    }
  ]
}
```

- `name` (English) and `nameTa` (Tamil) are both required and follow the
  existing display-label rules (1–80 code
  points, letters, marks, numbers, punctuation, symbols and single spaces).
- `kind`: `toll`, `eatery`, `fuel`, `restroom`, `bus_stand`, `temple`,
  `junction`, `rest_area`.
- `district`: a lowercase key from a fixed list in the loader (corridor
  districts from Chennai to Tirunelveli, Tuticorin and Thanjavur for Diwali;
  Vellore to Coimbatore added for Pongal). The list is in `SpotDistrict.java`
  and uses official district names (for example `thoothukudi`,
  `tiruchirappalli`); the curator confirms it when seeding.
- `corridors`: ids declared in the same file. Diwali: `gst-trunk`,
  `trichy-thanjavur`, `trichy-madurai-tirunelveli`, `madurai-tuticorin`;
  Pongal adds `chennai-coimbatore`.
- `categories`: the one-tap signal categories allowed at this Spot (see the
  posts spec). Defaults by kind are a curation aid, not a loader rule.
- Coordinates must fall inside the configured routing region.
- `provenance` is required and stays on the server: curator, source
  (`osm`, `field_visit`, `operator_knowledge`, `public_listing`) and review date.
- No phone numbers, websites, prices, ratings or paid placement. Businesses do
  not choose or edit their Spot.

A catalog change is a new file with a new `version` and a restart; there is no
hot reload or merge. **Every edit, however small, needs a new `version`**:
phones revalidate by version (ETag), so an edit that keeps the version never
reaches them. The second reviewer checks the version changed. At startup the
server logs the version, Spot count and a SHA-256 of the served catalog; a
deploy check confirms every replica logs the same digest. The loader also
rejects names containing phone numbers (7 or more digits), web or e-mail
addresses, Tamil names without Tamil script, and review dates later than
tomorrow. The existing anchor catalog loader (ADR 0031) is the model
for strictness and bounds; the anchor catalog itself stays with the archived
route-binding code.

**Seeding.** About 150–200 Spots for Diwali, weighted to the GST Road trunk
(Kilambakkam, tolls, major eateries, fuel, restrooms, bus stands); branch Spots
cover major tolls, bus stands and highway eateries only. A named curator owns the
file; a second person reviews each version against a map before it is deployed.
Decided 2026-09-25:

- **Names:** every Spot has an English and a Tamil name, matching bilingual road
  signs where they exist. A Tamil speaker writes and checks the Tamil names in
  the same review. The app's own interface text stays English for Diwali; a
  Tamil interface is decided before Pongal (PRODUCT.md open question).
- **Rest areas:** seeded when they have restrooms, food or fuel.
- **Temples:** not seeded for Diwali, which is homeward traffic, except temples
  that slow the highway itself (for example Samayapuram near Trichy), seeded with
  the `traffic` and `queue` categories. Temple Spots are revisited for Pongal,
  which overlaps the Sabarimala pilgrimage season.

## Catalog delivery

- `GET /api/v1/native/spots/catalog` (native bearer, account header, flag
  `ROUTIQO_SPOTS_API_ENABLED`). Response: `version`, `corridors`, and each Spot's
  `id`, `name`, `nameTa`, `kind`, `longitude`, `latitude`, `district`,
  `corridors`, `categories`. Provenance is not returned. `ETag` is the version;
  `If-None-Match` returns 304. Rate gate `spot-catalog-read-account`, 10 per
  minute. Response cap 256 KiB.
- The app caches the latest catalog in SQLite (`spot_catalog_v1`, one row, not
  account-scoped because it holds no personal data) and refreshes it on app
  start and on opening Journey mode when online. A new version replaces the old
  one. Without any cached catalog, the Spots panel says "Spots will appear once
  the Spot list downloads."

## Spots ahead (on device)

Computed on the phone from the journey route record
([ANDROID_JOURNEY_MAP_SPEC.md](../journey/ANDROID_JOURNEY_MAP_SPEC.md)) and the
cached catalog. The route, endpoints and position are never sent to the server.

- A Spot is on the route when it lies within 100 m of the route line (distance
  to segments, not only vertices).
- Spots within 1,000 m of the journey origin or destination are left out, except
  `bus_stand` Spots, so the Spot list and the activity requests do not point at
  a home or office. The exclusion uses the stored endpoints.
- Each matched Spot gets its distance along the route. Spots ahead are ordered by
  distance from the traveller's projected position (or from the route start with
  no position). A Spot stays in the list until 200 m behind the traveller, shown
  as "Here".
- The panel lists at most the next 20 Spots ahead; the map shows markers for the
  same 20. Recomputed when the route, catalog or projected position changes, at
  most every 10 s.

## Activity read and refresh

- `POST /api/v1/native/spots/activity` with body `{ "spotIds": [...] }`: 1–20
  distinct, sorted, lowercase UUIDs from the current catalog. POST keeps Spot IDs
  out of URLs and access logs. Unknown IDs are ignored. Without an active journey
  owned by the account the response is 409.
- Response (`Cache-Control: no-store`, cap 128 KiB): `serverTime`,
  `catalogVersion`, and per Spot: `state`, the signal summaries, posts and
  highlights defined in the posts spec, and `alertIds`; plus a top-level
  `alerts` list (see Official alerts).
- The server does not log, store or analyse the requested Spot IDs. They exist
  only to answer the request; the rate gate key is the account, not the IDs.
- Rate gate `spot-activity-read-account`, 20 per minute. Requires an owned active
  journey, so it cannot be used as an open scraping API.
- Refresh (ADR 0066): only while Journey mode is in the foreground; every 60 s
  with the panel collapsed or half open, every 20 s while a Spot detail is open;
  one request in flight; cancelled on close, background, account change and
  journey completion; exponential backoff on 429 and 503 (up to 5 min).
- The last response is kept in memory only. Offline, it stays visible labelled
  "Last updated 14 min ago"; items past their `expiresAt` are removed on the
  device clock and on the next response. Nothing is shown as current when stale.

### Planned for Pongal: corridor sections

Decided 2026-09-25; confirmed or dropped after measuring load at the Diwali dry
run. Before Pongal, activity reads move from per-Spot IDs to fixed corridor
sections of about 25 km, defined in the catalog:

- The app asks for the sections covering the Spots ahead (usually one or two),
  so the server learns only which stretch of road a traveller is on, not the
  next 20 Spots.
- The shared part of each section's response (signal summaries, posts,
  highlights, alerts) is identical for every reader, so it is built once and
  cached for a few seconds, which keeps festival-peak load flat.
- A small per-reader part (the reader's own votes and posts, and removal of
  content from accounts the reader blocked) is applied on top and never cached
  across accounts.

## Spot state

Computed on the server from unexpired, visible items (signals and posts):

| State | Rule | Shown as |
| --- | --- | --- |
| Live | At least one item captured or confirmed in the last 30 min | "Live" chip, latest summary |
| Fading | Unexpired items, none captured or confirmed in the last 30 min | "Earlier today" chip, muted |
| Quiet | No unexpired items | "No recent reports" and highlights if any |

An official alert adds an alert badge in any state. State never implies safety:
"No recent reports" does not mean the road is clear.

## Panel and Spot detail

- **Panel rows:** Spot name in English with the Tamil name beneath it (Tamil
  first when a Tamil interface exists), kind
  icon, "12 km ahead" or "Here", state chip, the most useful summary for the
  kind (e.g. "Slow · 3 reports in 20 min", "Good · 2 in the last hour"), and an
  alert badge. Everything on a row is available as text to screen readers.
- **Spot detail:** summaries per category with report counts and times, recent
  posts, highlights, alerts with source and expiry, and the contribution actions
  from the posts spec. Report counts are shown; traveller counts never are.
- **States:** loading, catalog missing, no route, no Spots on this route, all
  Spots quiet, offline with last update time, rate limited ("Updates paused
  briefly"), service unavailable, Spots flag off (panel hidden).
- No sample or placeholder Spots or activity in production builds.

## Official alerts

- The NDMA reader (ADR 0050) is extended from its fixed Chennai-area district
  pattern to the district keys present in the current Spot catalog, and a native
  equivalent of the alert read is added as part of the activity response.
- Alerts naming a Spot's district, or the whole of Tamil Nadu, are listed once in
  `alerts` (id, event, area, severity, issuer, source link, issued and expiry
  times) and referenced from each matching Spot. Shown as "District-wide alert",
  never as a road condition.
- The existing bounds, fail-closed behaviour and attribution stay. The feed and
  detail caches must move out of process-local memory or be documented as
  per-replica efficiency caches before multi-replica deployment.

## Privacy and security

- The catalog is public reference data; activity reads and contributions are
  authenticated. Clients never receive other travellers' positions, routes or
  passage records.
- Requested Spot IDs are private journey data (ADR 0067): not logged, stored, sent
  to analytics or used to profile.
- Generic empty-body errors as in existing native endpoints (400, 401, 404, 409,
  413, 415, 429 with `Retry-After`, 503).
- Flags: server `ROUTIQO_SPOTS_API_ENABLED`, client
  `EXPO_PUBLIC_ROUTIQO_SPOTS_ENABLED`; exact `true` only, default off, tests
  prove they fail closed.

## Acceptance and evidence

- **Loader:** valid file; every rejection (unknown key, duplicate id, bad or
  missing English or Tamil name,
  unknown kind, district or corridor, outside region, too many Spots, oversize,
  missing provenance).
- **Endpoints:** auth, account header, flag off, ETag/304, rate limits, active
  journey requirement, unknown IDs ignored, no Spot IDs in logs (log capture
  test), response caps.
- **On device:** segment matching at 99/101 m, endpoint exclusion and bus stand
  exception, ordering with and without position, the 200 m "Here" rule, catalog
  replacement, 20-Spot limit.
- **Refresh:** cadence per panel state, one in flight, cancellation triggers,
  backoff, stale labelling, expiry removal offline.
- **State:** live/fading/quiet boundaries at 30 min; alert badge.
- **Evidence:** emulator screenshots with a synthetic catalog labelled as such;
  physical-device and real-corridor checks in the pending ledger; the Diwali dry
  run is the first real-corridor test.

## Open questions

- None open. Resolved 2026-09-25: bilingual Spot names with an English
  interface for Diwali; rest areas with facilities seeded, temples only where
  they slow the highway; per-Spot activity requests for Diwali, with corridor
  sections planned for Pongal (see *Planned for Pongal: corridor sections*).
