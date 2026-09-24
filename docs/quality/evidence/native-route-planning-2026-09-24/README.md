# Native route planning evidence

Captured on 2026-09-24 on the API 36 x86_64 emulator. Temporary labeled account
and service fixtures exercised the actual planner, controller and typed native
route/place parser. No real OAuth, GPS, HTTPS service or regional provider was used.
The Kotlin bridge was compiled separately; real HTTP/PostgreSQL integration tests
used bounded local Photon/Valhalla fixtures. None of these are physical-device or
configured-service evidence.

## Interaction evidence

- `initial.png`, `search.png`: explicit search and selectable matches; no requests
  at mount. The toolbar counters update on toolbar actions, not every response.
- `final-route-estimates.png`, `alternative.png`: selected endpoints, estimates,
  attribution, alternate route and manual instructions. Selecting another route
  resets review to its first step; Previous/Next review loaded data only.
- `offline-directions.png`, `offline-renewal.png`: the last loaded estimate and
  selected step survive connection loss, tab navigation and same-account renewal.
  Reconnection does not silently calculate or search (inspected counters unchanged).
- `unavailable-retained.png`, `outside-coverage.png`: distinct failure copy and
  retained previous same-input estimate. `no-route.png` is a valid empty result;
  `no-steps.png` does not invent instructions. `no-places.png` shows empty search.
- `account-cleared.png`: switching synthetic accounts removes old inputs/results.
- `pending-route.png`, `background-late-response.png`: held response and return
  after Android backgrounding; a late response cannot replace the older estimate.
  Final portable-cancellation regression evidence is recorded separately below.

Normal-size interaction checks used the full Trips composition. Final small-screen
checks use an isolated ScrollView fixture hosting the same actual planner and
account hook at 360 dp/130% text. This isolates wrapping/control behavior; full
Trips placement was checked at normal size. Fixtures never persist routes or
change real account data. Local plans remain independent.

The emulator exposed two issues during QA: the containing list could consume the
first Search tap to dismiss input focus, and Hermes lacks the browser DOMException
constructor used by cancellation. The list now handles button taps, Search and
Calculate explicitly dismiss the keyboard, and all native account/journal/consent/
routing cancellation uses a portable Error named AbortError. Tests explicitly
remove DOMException to prevent Node's browser-compatible global hiding this again.

## Final verification

Full backend checks passed: 560 tests / 92 suites, no failures/errors/skips.
Independent backend/transport review passed; its default-off test coverage note
was corrected and verified with the routing profile and real configured providers.
Full TypeScript checks passed: 689 tests / 89 files, all six typechecks, lint,
formatting, generated contracts and secret scan. Android build passed in 1m41s
(467 tasks, 21 executed), installed and launched. Final APK SHA-256:
`06830279478fc7e23a05c7989b681618b645134217e5912d0ab420421a75d7df`.

`final-portable-pending.png` captures a held request on the final runtime;
`final-portable-background.png` and `small-large-text-directions.png` show that
background cancellation discarded its late response and retained the earlier
11:52:51 estimate. No new Metro runtime error appeared after the clean reload
and this cancellation trial. `small-large-text.png` and
`small-large-text-controls.png` document wrapping and reachable controls at
360 dp/130% text. Earlier interaction captures predate this portability fix.

Temporary provider/panel/Trips overrides were restored byte-for-byte from their
pre-fixture snapshots; display size/density and font scale returned to defaults.
`final-restored-app.png` records the default-off app after final install. This is a
debug development client using Metro, not a production startup-performance test.

Real OAuth/HTTPS/provider coverage, TalkBack and representative device/network
checks remain in [the native routing ledger](../../../validation/NATIVE_ROUTING_PENDING.md).
GPS-following navigation, selected-route maps and downloaded navigation remain
separate features. This manual planner does not establish LIVE route membership.
