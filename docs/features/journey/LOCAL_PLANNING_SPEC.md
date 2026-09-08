# Local planning
User goal: keep a lightweight trip or recurring commute plan without sharing location or creating an account.
Plan includes generated ID, kind, coarse origin/destination labels, departure date/time, recurring weekdays and optional notes. User can edit/remove it. This is not a live journey, routing calculation, notification schedule or cloud sync.
Validate field lengths, real date/time, distinct locations, nonempty commute days, storage version and list bounds. Reject corrupted storage explicitly; do not overwrite without a clear-data action.
Save destination IDs independently of plans. Persist only user-authored non-sensitive planning information on this device.
Web dialog supports native focus containment, Escape, labels and errors. State changes announce via aria-live. Storage failures leave form open with recoverable feedback.
Native SQLite persists the same versioned envelope. No GPS permissions requested.
Tests: roundtrip, edit idempotence, malformed/unsupported payload, duplicate IDs, bounds, invalid dates, day requirements, search filters. Manual tests: reload, search, filter, save, edit, remove, empty/error, keyboard, small viewport.

