# Native completed-trip journal browsing

Scope: finish the user-facing read flow from the implemented native account history
and journal transport. Preserve the four tabs and current Routiqo visual system.

## Acceptance and interaction

- Each completed TRIP on the currently displayed account history page offers an
  explicit View journal action. Active trips and commutes do not.
- Open one journal at a time inside the history section, with a Back to history
  control; preserve the displayed history page while reading. No new navigation tab.
- Opening a journal or returning to history positions the section in view, even
  when the selected trip was far down a full page. Do not animate this repositioning.
- Display private title, plain-text notes and known lifecycle dates. Use Trip journal
  for an empty title and explain an empty annotation; never invent route/distance.
- Loading, retry, missing/ineligible, session-required and offline states are
  distinct. Retain an already-loaded journal offline with a clear notice; no
  automatic fetch, reconnect retry, journal writes or new durable cache.
- Close, change selection, session renewal/sign-out/account change/deletion, and
  unmount cancel/fence pending reads. No earlier response can overwrite a later
  selection. A 401/403 clears private content. A 404/409 clears unavailable content.
- Only currently verified account context can read. Use the existing account
  provider and readNativeJournal; keep its strict validation and generation fence.
- Controls use shared tokens, native accessibility roles, readable text scaling,
  minimum touch targets, wrapping and accessible loading/error announcements.

## Boundaries and verification

This feature exposes no other user's content, route, GPS or contributor data.
No new provider, migration, backend endpoint, analytics or storage is required.
The native-auth profile and actual OAuth/staging requirements remain unchanged.
Do not put journal text in logs, fixtures resembling real personal data or backups.

Test controller transitions, selection races, errors, cancellation, offline
retention, session isolation and no automatic fetch. Exercise production views in
a clearly labelled temporary synthetic emulator harness for all relevant states,
small width/large text and navigation. Remove the harness before final build and
production-route smoke. Run relevant lint/types/contracts/tests and Android build.
Record real OAuth/physical-device checks as pending rather than claiming them.
