# Native private LIVE consent evidence

Captured on 2026-09-24 on the API 36 x86_64 Android emulator. A temporary, labeled
account-context fixture supplied an active journey and synthetic server responses
to the actual mounted consent panel, controller and native consent parser/deadline
code. No real OAuth, HTTPS service or public LIVE publication was used. HTTP and
PostgreSQL authority are covered separately by integration tests.

The fixture toolbar is test-only. Its R/W counters refresh when a toolbar action
is pressed, not on every response; they represent the last inspected counts.
No consent data was persisted by the fixture.

## Interaction checks

- `unknown.png`: initial unknown state, no automatic requests and disabled Allow.
- `checked-off.png`, `allowed.png`, `stopped.png`: explicit check, one-shot Allow
  and explicit Stop with validated generation advancement.
- `uncertain-write.png`: the synthetic server committed Allow then lost its
  response. The UI retained uncertainty and disabled a new Allow.
- `offline-renewal-uncertain.png`, `navigation-uncertain.png`: uncertainty survived
  offline/reconnect, same-account renewal and leaving/returning to Trips. Inspected
  request counts did not increase through those transitions.
- `read-keeps-uncertainty.png`, `explicit-stop-recovery.png`: a read did not erase
  the unresolved-write warning; a validated explicit Stop resolved it.
- `refresh-disabled.png`, `pending-completion-disabled.png`: refresh and queued
  completion disabled controls while retaining the mounted scope.
- `pending-allow.png`, `background-uncertain.png`, `late-allow-fenced.png`: sent a
  held Allow, backgrounded the Android app and returned to an unconfirmed state.
  A subsequent explicit Stop remained confirmed after releasing the old response.
  The synthetic server modeled generation ordering; real ordering is separately
  tested against PostgreSQL, not established by this fixture.
- `account-switch-cleared.png`: another synthetic account began unknown without
  the previous account's confirmation.
- `rate-limited-stop.png`: a failed Stop did not claim success.
- `missing-journey.png`, `completed-terminal.png`: missing and completed journeys
  could not enable contributions; completion remained terminal in the controller.

These interaction captures preceded a final placement correction: private consent
now follows the basic active-journey controls and precedes map/history content.
The final layout and large-text captures document that correction separately.

- `small-large-text-placement.png` and `small-large-text-controls.png`: final
  placement after journey actions, wrapping and reachable controls at 360 dp and
  130% text. No new animation was introduced.
- `final-restored-app.png`: Home rendered after removing all temporary fixtures,
  restoring display size/density and font scale, rebuilding and installing the
  actual default-off app. Startup log contains `Running "main"` and no fatal
  exception, fatal signal or ReactNativeJS error in the inspected process capture.

The final APK SHA-256 is
`4d6e7e57bdae26ff8949a55495200f6eea5a319f89068ffee0b378524fa9c49a`.
Build: 1m45s, 467 tasks (21 executed). Full verification passed: 671 TypeScript
tests / 85 files, 555 Java tests / 90 suites, all six typechecks, lint, formatting,
contract generation and secret scan. Pure controller/view tests are supplemented
by the mounted emulator checks above; they are not React rendering tests.

## Limits

The default-off native consent feature is a private preparation/reconciliation
control, not a public LIVE list or global Ghost Mode. Actual OAuth/staging,
browser/native operation with real sessions, TalkBack and representative physical
device/network checks remain in
[the native LIVE ledger](../../../validation/NATIVE_LIVE_PENDING.md).
The fixture does not prove device performance or configured-service behavior.
