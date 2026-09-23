# Native account history view evidence — 2026-09-24

These are actual Android emulator screenshots of the production history view
rendered with **synthetic view props**, clearly labeled in the screen. No Google
account, bearer credential or server response was injected. The temporary Trips
route harness was removed after inspection. Server/client authority is verified
separately by HTTP and protocol tests; real service validation remains in
`docs/validation/NATIVE_HISTORY_PENDING.md`.

API 36 Google APIs x86_64, Pixel 5 profile. The updated debug APK built successfully
(467 tasks; 18 executed) and installed. SHA-256:
`db79cc323293b19d19c2b12e7d9f8d47ea735628862487d6f07bad0a6c8d1f12`.

- `initial.png`: explicit load action before any page is shown.
- `loaded.png`: trip/commute rows, dates and latest/earlier actions.
- `offline-retained.png`: retained-page disclosure; the Android accessibility
  hierarchy confirmed both paging actions disabled.
- `empty-earlier.png`: Earlier action reached a synthetic empty terminal page.
- `error-retry.png`: failure disclosure with retry; tapping retry restored rows.
- `loaded-small-large-text.png` and `actions-small-large-text.png`: 900×1600 at
  density 400 (360 dp width), font scale 1.3; rows wrap and controls remain
  reachable by scrolling.
- `session-required.png`: private page cleared, actionable sign-in message.

The explanatory copy was simplified during review; the small-screen images show
the revised text. Synthetic navigation checks exercise view callbacks, not the
network/controller. Automated tests cover strict pages, fixed-size pagination,
owner isolation, exact retry and late-result disposal independently.

After the final POST-only native bridge change, the debug APK rebuilt successfully
(467 tasks; 21 executed), installed and opened the restored production Trips
route. SHA-256:
`26a91a64fc43dfedf7d4ab725111b5be760fe9a7f4ded998797c88d89500e311`.
`final-app-trips.png` shows the unconfigured-server disclosure and the local plan
retained across the APK update. This final smoke check uses no view harness.
The emulator experienced a System UI startup interruption before the successful
app launch; this run does not establish representative device performance.
