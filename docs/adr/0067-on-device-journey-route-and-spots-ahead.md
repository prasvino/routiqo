# ADR 0067: On-device journey route and Spots-ahead matching

Date: 2026-09-25
Status: accepted. Decisions 1, 5 and 6 (device-only journey route, foreground
position, `expo-location`) are implemented behind the Journey map flag; 2–4
(catalog on device, Spots-ahead matching, activity reads) arrive with Spots.

## Context

The pilot Journey shows the selected route on a map with the Spots ahead ordered
by distance ([PRODUCT.md](../PRODUCT.md)). Today:

- Android keeps route geometry only in the Trips planner's memory. It is not
  linked to a journey and is lost on restart (ADR 0060 forbids durable route
  storage).
- The server matches catalog anchors to a route only by re-routing from the
  origin and destination (ADR 0031). Binding is explicit, consent-coupled
  (ADR 0032), lives 15 minutes, is one per account, and returns an unordered set
  of anchor IDs with no along-route position.
- The server stores no journey route, and should not start: journey routes and
  plans are private to the user unless published as a route guide.

## Decision

1. **The device keeps the selected route of the active journey.** Starting a
   journey from a calculated route stores that route (mode, the two endpoint
   labels and coordinates, the selected alternative's simplified geometry and
   distance) in a device-only, account-partitioned SQLite record keyed by the
   journey ID. It never enters the journey outbox, planning backup, account
   planning copy, analytics or logs, and is deleted when the journey completes or
   is discarded, on account change, sign-out and account deletion. This narrows
   ADR 0060's "never durable" rule to exactly this record.
2. **The Spot catalog is public reference data sent to the device.** Spots are
   public places (tolls, eateries, fuel stations, bus stands). The device
   downloads the versioned catalog and caches it.
3. **Spots ahead are computed on the device** by matching catalog Spots to the
   stored route geometry and ordering them by distance along the route from the
   traveller's projected position. The server never receives the route, origin,
   destination or position for this.
4. **The server receives only Spot IDs** when the device asks for their activity
   (signals, posts, alerts), in a request body, never a URL. Those IDs reveal
   roughly where the traveller is going, so the server treats them as private:
   they are not logged, stored or used for anything beyond answering and rate
   limiting the request.
5. **Location for the Journey map is foreground-only and on device** (Phase 1):
   while-in-use permission requested from an explicit action in Journey mode,
   updates only while the Journey screen is visible, readings kept in memory.
   Spot passage and its foreground service are a later spec (PRODUCT.md
   Decisions §2).
6. New dependency: `expo-location` for foreground location on Android.

The archived route-binding path (ADRs 0031–0034, 0061) stays in code,
default-off. Its matching rules (100 m from the route, 1,000 m endpoint
exclusion) are reused on the device.

## Consequences

- Spots ahead work offline once the catalog and route are on the device, and do
  not depend on a 15-minute server context.
- A journey started without a calculated route has no Spots ahead; the UI says
  so. Rerouting after a missed turn is out of scope; the traveller can start
  from a new calculated route.
- Catalog changes need a new catalog version and a client refresh; old catalogs
  are replaced, not merged.
- Spot IDs sent for activity reads are a new, bounded leak of approximate route
  to the server, accepted and protected as above. A later design could fetch
  activity per corridor segment instead to blur it further.
- Specs: [ANDROID_JOURNEY_MAP_SPEC.md](../features/journey/ANDROID_JOURNEY_MAP_SPEC.md)
  and [SPOTS_SPEC.md](../features/spots/SPOTS_SPEC.md).
