# Native journal editing emulator evidence

Captured on 2026-09-24 using the API 36 x86_64 Android emulator. These are
synthetic account/server responses driving the production editor, controller,
saved-journal library and actual SQLite implementation. No real OAuth credentials
or staging service were used. HTTP authority and validation are separately covered
by the Java integration tests.

## Exercised

- `unsaved-close-guard.png`: Android Back asks before discarding edited text;
  Keep editing retained it.
- `offline-draft.png`: explicit device save while offline, with account delivery
  disabled. The unusual title suffix is synthetic ADB-entered test text.
- `recovered-draft.png`: force-stopped and relaunched the app, opened the saved
  journal from its device library, and recovered the exact saved title and notes.
- `account-saved.png`: explicit send accepted by the synthetic versioned server;
  SQLite acknowledged the exact draft before displaying success.
- `conflict-retained.png`: advanced the synthetic account version, then sent a
  stale draft; conflict retained the device draft.
- `conflict-review.png`, `conflict-confirm.png`, `conflict-resolved.png`: fetched
  and displayed the newer account version, cancelled replacement once, then
  explicitly confirmed replacement. The resulting editor showed the reviewed
  account text with no saved-draft indicator.
- `small-large-text-editor.png`, `small-large-text-actions.png`: 360 dp width and
  130% text, with readable wrapping and reachable save actions by scrolling.
- `small-large-text-keyboard.png`: notes field focused at those dimensions. Android
  reported its input view shown, but the emulator used a narrow/floating input
  affordance; this is not proof of a full-size soft-keyboard layout or TalkBack.
  Those remain physical-device validation items.
- `final-restored-app.png`: restored application running after installation of the
  final debug APK. Startup capture showed JavaScript `Running "main"` and no fatal
  exception/signal or ReactNativeJS error in that process. The build passed in
  1 minute 49 seconds (467 tasks; 21 executed).

The temporary account-context and route fixture was removed, and both original
source files were restored with matching SHA-256 hashes. Only the synthetic QA
account's journal partition was removed through the real account-retirement
transaction; existing local plans were retained. Screen size, density, font scale
and hardware-keyboard IME setting were restored. The synthetic server is not an
idempotency implementation; exact replay and response-loss behavior are tested
against the actual transport/domain contracts separately.

See the [journal ledger](../../../validation/NATIVE_JOURNAL_PENDING.md) for remaining
configured-service, backup-transfer and physical-device checks. This evidence does
not establish production readiness or measured device performance.
