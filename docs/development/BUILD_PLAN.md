# Foundation and first usable slice
Date: 2026-09-06. User authorized autonomous development, pausing only sections that require missing decisions/credentials.

## Acceptance criteria
- Pinned pnpm/Turbo TypeScript workspace and Java 25 Gradle multi-project build.
- Next.js web/admin and Expo mobile applications compile with strict TypeScript.
- Local PostGIS, Redis, S3-compatible services have health checks and loopback-only ports.
- Public catalog/status endpoints described in OpenAPI; generated client stays in sync.
- All unimplemented protected backend paths fail closed. No fake authentication or live presence.
- Useful discovery UI with search/category filtering, place details, saved destinations, and local journey plans.
- Local drafts survive reload, validate persisted data, and are clearly separate from live journeys.
- Mobile reuses domain helpers/tokens while keeping native UI/storage platform-specific.
- Backend lifecycle/privacy rules have meaningful tests even before exposed endpoints.
- Rendered desktop/mobile-width web QA, keyboard use and real interactions verified.
- CI runs relevant contracts, strict types, lint, tests, builds, Java and secret scanning.

## Visual thesis
Warm ivory, dark forest teal, editorial travel photography, and restrained route-inspired details. Discovery is inviting; planning remains legible and practical.

## Content plan
Home: destination prompt, journey CTA, one photographic route feature, curated discovery.
Explore: searchable/filterable destination collection.
Trips: durable local plans, clear empty state, edit/remove flow.
Profile: local preferences and privacy explanation; no pretend logged-in account.

## Interaction thesis
A focused journey-planning dialog, subtle image hover affordance, and short state transitions with reduced-motion support. No decorative map or fabricated traveller counts.

## Deferred sections
- Auth provider/session implementation: document secure model, do not create bypass.
- Maps migration: follow ADR 0021 (MapLibre/Valhalla/Photon/Martin); first generalize
  provider contracts and migrate web rendering/adapters, then verify regional data
  services and native downloads/navigation. Android development build/device and
  regional datasets are prerequisites; Mapbox credentials are not required.
- Live presence/rooms: anonymity thresholds, retention, trust and geographic admission need feature decisions and tests before exposure.
- Real Android/iOS validation: no device/emulator available yet.
- External AI, S3 production, push, cloud deployment: require provider configuration.

Offline draft persistence is implemented alongside planning; it is not postponed to a late phase. Local planning does not claim a live server journey.
