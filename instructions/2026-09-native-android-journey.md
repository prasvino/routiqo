# Native Android journey implementation

Implement the acceptance in `docs/features/journey/NATIVE_ANDROID_JOURNEY_SPEC.md`.
Reuse the existing Google session, journey service, SQLite outbox and SecureStore
vault. Add a native HTTP bridge rather than relying on React Native fetch redirect
options. Bind protected native resource access to bearer verification and exact
account context. Keep browser and default-preview chains unchanged. Make the
mobile sign-in and journey actions explicit; a saved local plan is never an upload
trigger. Test transport/server/client boundaries, then compile an Android debug
build and inspect a device/emulator if the local toolchain permits. Record any
unavailable OAuth credentials, controlled map assets, or device/release signing
checks in the pending ledger.
