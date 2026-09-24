# Android emulator run — 2026-09-24

Routiqo was built, installed and launched on the existing `routiqo-codex-api36`
Pixel 5 / Google APIs / x86_64 virtual device. No SDK, Java or system image was
downloaded, no licence acceptance was needed, and device data was not wiped.

## Environment and verification

- Project: Expo 55.0.31, React Native 0.83.10, Hermes/new architecture.
- Android build: compile/target API 36, minimum API 24, Build Tools 36.0.0.
- SDK: `C:\Users\user\AppData\Local\Android\Sdk`.
- Android JDK: `C:\Users\user\AppData\Local\Routiqo\jdk17\jdk-17.0.20.1+1`.
  Android Studio's bundled JBR is Java 25; the existing JDK 17 was reused for this build.
- Existing workspace emulator: version 37.1.11. WHPX acceleration reported usable
  and operational; no BIOS or administrator configuration was required.
- AVD home: `D:\Pras\routiqo\.patch-work\android-avd`.
- Emulator/system-image SDK: `D:\Pras\routiqo\.patch-work\android-sdk`.
- `android-doctor.ps1 -RequireReady`: all six checks Ready.
- `android-build.ps1 -Architectures x86_64`: BUILD SUCCESSFUL in 21 seconds;
  467 tasks, 13 executed. `adb install -r` succeeded.
- APK: `apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk`.
  SHA-256: `90543278bdb1070390fd3795f8da8ce49ec223818d962007859a95da1a998ab0`.
- `adb devices -l`: `emulator-5554 device`, API 36, x86_64.
- `sys.boot_completed`: `1`. Existing Routiqo Metro server on localhost:8081
  was verified by its Expo manifest and reused with `adb reverse tcp:8081 tcp:8081`.
- App launch returned `Status: ok`, activity `com.routiqo.app/.MainActivity`.
  App PID 3081 remained running through Home and Trips checks.

## Recovery and startup logs

The pre-existing headless emulator was slow/unresponsive. It was gracefully
stopped and cold-started as a visible window, preserving the same AVD data, with
`-no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -cores 2`.
Android boot completed in about 82 seconds. System UI displayed an ANR dialog;
closing that System UI instance and relaunching Routiqo recovered normal rendering.

Application log inspection confirmed:

```text
I/ReactNativeJS: Running "main" with {"rootTag":1,"initialProps":{},"fabric":true}
```

No `FATAL EXCEPTION` or `Fatal signal` appeared in the inspected app-process log.
Nonfatal development-client diagnostics included optional SplashScreenManager
lookup failure, startup window-token/context-not-ready messages, appearance-field
lookup and generated-setter warnings. These were not treated as a clean-log or
performance certification; the app subsequently rendered and navigation worked.

## Visual evidence and limits

- `startup.png`: actual Home screen after initialization.
- `trips.png`: actual Trips screen after tapping its tab; the existing synthetic
  local plan remained available after reinstall and emulator restart.

The development-tools gear is Expo client chrome. The app was left running with
Metro available. Server journeys show the expected unavailable-service disclosure:
real OAuth, staging HTTPS and regional map services were not configured or tested.
This run is emulator startup/navigation evidence, not physical-device or release QA.

## Reopen while the emulator and Metro are running

```powershell
$adb = 'C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 reverse tcp:8081 tcp:8081
& $adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d 'routiqo://expo-development-client/?url=http%3A%2F%2F127.0.0.1%3A8081' com.routiqo.app
```
