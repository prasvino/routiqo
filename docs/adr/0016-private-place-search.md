# ADR0016: Explicit temporary place search

Provider choice superseded by ADR 0021 on 2026-09-12: self-hosted Photon is the
target. The text below records the existing Mapbox implementation. Explicit-submit
interaction and privacy/security bounds remain; autocomplete is not enabled by
the provider change alone.

Status: accepted, 2026-09-10.

Use Mapbox Geocoding v6 behind the existing authenticated routing boundary to resolve typed city/street/address endpoints. Do not guess coordinates from the curated discovery catalog. POI search is a separate future integration. Requests use a fixed host, encoded text, autocomplete=false, permanent=false and limit=5. Provider responses are validated and bounded to256 KiB; attribution is retained. Query and result domain records redact their string representations, and provider failures discard sensitive causes.

The POST `/api/v1/routes/places` endpoint requires the session, exact origin, CSRF and matching account context. The shared PostgreSQL rate gate gives it a separate20/account/minute budget. No external call runs inside a database transaction. The same-origin web proxy and browser transport retain size limits and cancellation. No results enter storage, backups or social presence.

Trips exposes a compact route planner only within a verified account workspace. Search, selection and calculation are separate explicit actions. Editing endpoints/mode invalidates old estimates; account changes remount the form and abort pending work. Optional browser location is one requested reading with no watch or persistence; invalid, stale or overly approximate fixes are rejected. The UI retains typed-place alternatives and provider attribution. It does not provide a basemap or navigation guidance yet.

Verification includes synthetic provider and authenticated HTTP tests, browser transport guards, and component tests for selection, stale results, account changes, offline/throttled states and explicit location intent. No real Mapbox or Google session was used. Windows visual capture failed twice with `SetIsBorderRequired failed: No such interface supported (0x80004002)`; visual layout and real-device QA remain pending. The temporary review page was removed before production build.

Reference: https://docs.mapbox.com/api/search/geocoding/ (checked2026-09-10).
