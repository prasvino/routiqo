# Native Android smoke evidence — 2026-09-24

API 36 Google APIs x86_64 emulator, Pixel 5 profile, development APK. Real Google
OAuth, staging HTTPS and regional map configuration were absent. The Expo floating
tools icon is development-client chrome.

- `home.png`: app startup and local discovery.
- `trips.png`: unavailable server journey message; local planning remains available.
- `profile.png`: unavailable sign-in state, backup and local planning controls.
- `local-plan-restarted.png`: synthetic Chennai → Coast Mo plan created through
  the form and retained after force-stop/relaunch. No server upload occurred.
- `profile-large-text.png`: 1080×2340, density 440, system font scale 1.3.
- `profile-small-offline.png`: 900×1600, density 400 (360 dp width), font scale 1.3,
  Wi-Fi/data disabled and animator duration scale zero. Account fallback text
  wraps without horizontal clipping; remaining content is vertically scrollable.

Observed interactions: four-tab navigation, form text input, scrolling, local save,
process restart and return to retained plan. This proves an unconfigured native
development build works; it does not prove real sign-in, SecureStore credential
restoration, server journey synchronization, map loading or physical-device speed.
Those checks remain in `docs/validation/NATIVE_ANDROID_PENDING.md`.
