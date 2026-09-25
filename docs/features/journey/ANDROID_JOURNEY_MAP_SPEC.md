# Android Journey map

Status: implemented behind `EXPO_PUBLIC_ROUTIQO_JOURNEY_MAP_ENABLED`, 2026-09-25,
with local automated checks (see BUILD_STATUS). Emulator and physical-device
checks are pending in `../../validation/NATIVE_ANDROID_PENDING.md`. The Spots
panel content arrives with the Spots spec. Phase 1 of the pilot path
([PRODUCT.md](../../PRODUCT.md)); needed for the Diwali 2026 dry run. Decision
record: [ADR 0067](../../adr/0067-on-device-journey-route-and-spots-ahead.md).

Scope: a full-screen Journey mode on Android that shows the selected route, the
traveller's own position and a panel slot for the Spots ahead. It turns the
existing journey start/complete, route planning and MapLibre preview into one
map-first experience. Spot content inside the panel is defined by
[SPOTS_SPEC.md](../spots/SPOTS_SPEC.md). Not in scope: turn-by-turn guidance,
rerouting, downloaded offline map regions, Spot passage and its foreground
service (Phase 3), background location, web changes.

## Navigation

- Four tabs stay: Home, Explore (renamed Guides in Phase 3), Trips, Profile.
- Journey mode is a full-screen route outside the tab bar (an expo-router stack
  screen above `(tabs)`), not a fifth tab.
- While a journey is active, every tab shows a persistent **Back to journey** bar
  (journey kind, elapsed time, next Spot name when known). Home also shows an
  active-journey card. Tapping either opens Journey mode.
- Journey mode has a visible **Close** (back to tabs; the journey continues) and
  **Complete journey** with an inline confirmation. Android back closes Journey
  mode; it never completes the journey.
- Starting a journey opens Journey mode. Existing Trips controls keep working.

## Starting with a route

- The Trips route planner gains **Start journey with this route** once a route is
  calculated and an alternative is selected. It queues the existing start command
  (unchanged outbox, idempotency and offline behaviour) and, in the same SQLite
  transaction, writes the device-only journey route record.
- Starting without a route (existing "Start trip now" / "Start commute now")
  still works. Journey mode then shows the map and position with an honest note:
  "No route selected, so Spots ahead are not available for this journey."
- **Journey route record** (`journey_route_v1`, account-partitioned): journey ID,
  mode, origin and destination labels and coordinates, selected alternative
  index, route distance and duration, `calculatedAt`, and the geometry simplified
  to at most 2,000 points (Douglas–Peucker, about 10 m tolerance, endpoints
  kept). At most one record per account.
- The record is private and local: not in the journey outbox, planning backup,
  account planning copy, logs or analytics, and not sent to any server. It is
  deleted when the journey is completed (after the completion command is
  queued), when a blocked start is discarded (ADR 0063), on account change,
  sign-out, account deletion and "Clear local data". A record whose journey is
  no longer the active journey in the partition is deleted on next load.

## Map

- MapLibre (`@maplibre/maplibre-react-native`, already a dependency) with the
  existing same-origin configured style (`EXPO_PUBLIC_ROUTIQO_MAP_STYLE_PATH`,
  `nativeMapStyle`). No public tile fallback.
- Layers: the route line (GeoJSON source, one line layer, not re-created on
  location updates); start and destination markers visible only to the owner;
  Spot markers from the Spots spec (a separate source, updated by feature state,
  not by re-rendering React markers); the traveller's position dot.
- Camera: fits the route on open; a **Follow me** toggle keeps the position
  centred, north-up. Manual pan turns follow off. No rotation, tilt or animated
  flights when reduced motion is on; otherwise short eased moves only.
- Map controls have accessible labels; the map itself is not the only way to
  read anything important: the Spots-ahead panel lists the same information as
  text.

## Own position

- Uses `expo-location` foreground (while-in-use) permission only. No background
  location permission, no foreground service in this phase.
- The permission is requested only from an explicit action in Journey mode
  ("Show my position"), never at app start, sign-in or journey start. The
  rationale says the position stays on the phone and is used to order Spots
  ahead.
- Updates run only while Journey mode is visible and the app is in the
  foreground: balanced accuracy, about every 30 s or 50 m. They stop on close,
  background, journey completion and account change.
- Readings stay in memory in an isolated store outside the React tree (the map
  dot and the Spots-ahead ordering subscribe to it); they are never written to
  SQLite, logs, analytics or any request. UI updates at most once per second.
- The position is projected onto the stored route to get distance along it; this
  drives Spots-ahead ordering ("12 km ahead"). If the position is more than
  1 km from the route, the panel says "You seem to be off the selected route"
  and orders Spots from the nearest point ahead; no rerouting.
- States: permission not asked, denied, permanently denied (with a link to
  system settings), location services off, waiting for a fix, weak signal
  (accuracy worse than 100 m), and position unavailable. Without a position,
  Spots ahead are ordered from the route start and labelled "from start".

## Spots-ahead panel slot

- A bottom sheet with collapsed (next Spot), half and full states; it never
  covers the Complete and Close controls. Its content comes from the Spots spec.
- When the Spots flag is off, the panel is not shown. It never shows placeholder
  or sample Spots in production builds.

## Offline and failure

- The route line, markers and Spots-ahead ordering work offline from the stored
  record and cached catalog. If map tiles are unavailable, the screen says
  "Map tiles unavailable offline; your route and Spots ahead still work" and
  keeps drawing the route on the empty style background.
- If the style is not configured, Journey mode still opens with the panel and a
  text route summary instead of the map.
- Journey commands keep the existing outbox and dispatch behaviour; Journey mode
  shows pending and blocked states with the existing copy.

## Driver safety and accessibility

- Journey mode has no text entry except the post composer in Spot detail, which
  opens only from an explicit tap and says "Post only when stopped or as a
  passenger". One-tap signals come first. Controls are at least 48 dp,
  reachable one handed, and readable at 200% font scale on a 360 dp wide screen.
- No sounds, vibration or pop-ups triggered by movement in this phase.
- Screen reader order: journey status, next Spot, panel, map controls, Close and
  Complete. Live regions announce only journey state changes, not position.
- Respects reduced motion and system font scale.

## Flags and rollout

- Client flag `EXPO_PUBLIC_ROUTIQO_JOURNEY_MAP_ENABLED` (exact `true` only;
  default off). Off keeps today's Trips panel and map preview unchanged.
- No server change is needed for this spec. Spot catalog and activity endpoints
  are in the Spots spec.
- New dependency `expo-location` (ADR 0067); Android permissions
  `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` only.

## Acceptance and evidence

- **Unit and shared tests:** route simplification bounds and endpoints; journey
  route record write in the same transaction as the start command; deletion on
  every listed trigger; projection and along-route distance, including off-route
  and no-position cases; the location store never persists or emits to
  transport (a test transport asserts no location fields ever appear).
- **View tests:** Back to journey bar and Home card; Close versus Complete; every
  permission and location state; offline tiles; unconfigured style; no-route
  journey; large text and reduced motion.
- **Emulator evidence** in `docs/quality/evidence/android-journey-map-<date>/`
  with mocked locations, labelled as synthetic.
- **Physical devices (pending ledger):** full journey on 3+ Android phones on a
  real road, with a real route, real position and the configured map style;
  battery and memory recorded over one hour; results in
  `docs/validation/NATIVE_ANDROID_PENDING.md`.
