# Native journal browsing evidence — 2026-09-24

API 36 Google APIs x86_64 emulator, existing Pixel 5 AVD. These screenshots
exercise the production native views with **synthetic data**, not a signed-in
Google account or a staging HTTPS service.

The first temporary Trips route supplied labeled view props. `history.png`
shows that only a completed trip offers View journal; an active trip and a commute
do not. Opening the trip, Back to the retained Earlier journeys page and retry
were exercised through real press controls. The state captures are `loaded`,
`empty`, `loading`, `error`, `missing`, `session`, `offline-retained` and
`offline-empty`. No private content is shown after a synthetic session failure.

`loaded-small-large-text.png` and `notes-small-large-text.png` use 900×1600 pixels
at density 400 (360 dp width), font scale 1.3. Dates and paragraphs wrap, the
authored indentation remains, and the notes are reachable by scrolling. Device
size, density and font scale were restored afterward. No added motion requires
a reduced-motion alternative.

A second temporary synthetic account-context fixture mounted the full production
DiscoveryScreen → NativeJourneyPanel → account history/controller path with 20
completed trips and a long journal. `deep-history-open.png` verifies that opening
the final trip brings the heading and Back control into view. Back restores the
loaded history at its heading. This fixture performed no credential exchange or
server calls. It checks composition and navigation, not server authority.

Both fixtures were removed. The restored provider exactly matched its pre-fixture
SHA-256. The final Android debug build passed in 43 seconds (467 tasks, 13 executed)
and the APK installed successfully. This development build serves JavaScript from
Metro; native packaging and rendered JavaScript are separate checks.
APK SHA-256: `90543278bdb1070390fd3795f8da8ce49ec223818d962007859a95da1a998ab0`.
`final-app-trips.png` shows the restored route and honest missing-service message.
The final process log contains `Running "main"`, with no FATAL EXCEPTION, native
fatal signal or ReactNativeJS error in the inspected startup capture. Existing
development-client warnings are not physical-device performance evidence.

The existing CI-mode Metro process served a stale bundle initially. Restarting it
with file watching and rebuilding its cache resolved that. Expo-generated metadata
was restored rather than committed. These runs establish functional UI behavior,
not representative physical-device performance or production readiness.

Real OAuth, staging ownership/account-switch trials and physical-device/TalkBack
validation remain in `docs/validation/NATIVE_JOURNAL_PENDING.md` and
`docs/validation/NATIVE_ANDROID_PENDING.md`.
