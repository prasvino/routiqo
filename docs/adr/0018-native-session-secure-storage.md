# ADR 0018: Native session secure storage

Status: accepted, 2026-09-12.

Use pinned Expo SecureStore 55.0.18 for native opaque session credentials. Existing
SQLite storage remains for plans and account-partitioned journey work. SecureStore
is a new native dependency, not a new provider, endpoint or authentication bypass.

One singleton serializes a fixed-key session record. Generation tickets prevent
stale exchanges and rotations from restoring credentials after local clear. Native
errors fail closed and are redacted. This is a storage prerequisite; native
challenge/exchange, bearer transport, server verification and Google UI remain
separate implementation work.

Keep Android SecureStore data excluded from backups. Choose the device-only,
unlocked Keychain accessibility class without biometric prompts. Do not assume
uninstallation erases iOS Keychain data or claim physical secure deletion. Verify
these properties on devices before release.

The installed Expo SDK's bundled module list recommends `~55.0.18`; pin the exact
version. API and platform behavior were checked against the
[Expo SDK 55 SecureStore documentation](https://docs.expo.dev/versions/v55.0.0/sdk/securestore/).
See `NATIVE_SESSION_STORAGE_SPEC.md` for record bounds, race handling and tests.
