# Private journey consent controls

Status: implemented and independently reviewed, 2026-09-19. This interface does
not open public LIVE. Verification: `docs/quality/PRIVATE_CONSENT_UI_QA.md`.

## Purpose and authority

Mount a compact participation section in the existing current-journey workspace
only for a signed-in account with a confirmed active journey. Pending local starts
are insufficient. Hide or invalidate it during pending completion, blocked journey
work, account verification and account/journey changes. Reuse the private browser
consent clients and existing server account/ownership/CSRF/generation authority.
No new API, role, development-auth bypass, feature flag activation or storage.

The choice controls preparation for private LIVE contributions on this journey.
It does not publish location, make a person discoverable, erase retained receipts,
or opt the user into an unapproved future publication policy. State those limits
plainly. Future public participation needs its own reviewed purpose/disclosure and
authorization decision; the stored boolean alone is never permission to publish.
Avoid naming a local UI state "Ghost Mode": cross-channel public revocation is
not implemented. The controls are not a working public LIVE list.

## Interaction

- Initially show status unknown and an explicit **Check LIVE settings** action.
  Mounting, reconnect, focus and visibility changes must not issue requests.
- **Allow private contributions** is a deliberate one-shot write, available only after
  a confirmed active/off response. Send that exact decimal generation unchanged;
  never coerce to Number or fetch/retry a newer expectation automatically. Terminal
  MAX generation cannot enable. Display confirmed state as the last confirmation,
  not an assertion that other devices cannot change it.
- **Stop private contributions** may be explicitly attempted while state is unknown or last checked off,
  using the last known generation or canonical `0`. The server's existing stale
  revocation precedence makes this safe. This is not an invented acknowledgement:
  only a validated successful response can confirm the stopped state.
- A cancelled or failed enable may commit after a subsequent read reports off.
  A read is a last-observed snapshot, not a fence against an outstanding write.
  Preserve the uncertain-mutation notice across reads, blur and offline transitions
  within the same account/journey scope. Only a successful explicit stop clears
  this uncertainty through server revocation authority: advancing the generation
  (or retaining its terminal maximum) fences older enable intents.
  Until then, do not enable again or claim that a read established privacy protection.
- A write failure makes the displayed state unconfirmed, even if it was previously
  off. Conflict requires explicit reconciliation; it must not silently fetch a
  new generation and enable. A rate limit asks the user to wait and check/retry
  deliberately. No failed stop is described as successful privacy protection.
- Stop disables new contributions; it does not delete previously saved private
  receipts. Public LIVE is currently unavailable. Keep disclosure short and tied
  to the decision rather than exposing implementation vocabulary.
- Completed journey responses disable further enabling for that identity. Do not
  let an earlier delayed response revive the journey.

## Lifecycle and offline behavior

Use one synchronous request guard plus an AbortController and monotonically
invalidated operation token. React rendering alone is not a double-click lock.
Existing transport deadlines bound the operation. Ignore late results even from
adapters that disregard abort. No polling, auto-retry, background dispatch, outbox,
browser persistence or synthetic activity.

Cancel and clear confirmation on account/journey change, unmount, offline, hidden
document, window blur and parent authority/lifecycle invalidation. Reconnect/focus
leave status unknown until an explicit check. Verify current scope again before
launching a network request and before accepting its result. Scope changes must
not briefly render a previous account's confirmation or retain enable authority.
Server authentication and X-Routiqo-Account remain mandatory for every request;
the panel's existence is not independent proof of current authentication.

Keep controls disabled while a request is pending or the app is offline/hidden.
Use explicit status/error text and preserve a manual reconciliation path after
uncertain writes. Abort does not prove an already sent write was rolled back.
Do not retain a known-off indicator after cancelling an uncertain enable.

## Presentation and acceptance

Reuse current typography, tokens, button styles and spacing. No fifth tab or new
design system. Use native keyboard-operable controls with visible focus, touch
targets of at least 44 px, short status announcements and narrow-screen wrapping.
Do not show account IDs, journey IDs or generations as product content.
If the Allow action removes its focused button, restore focus to Stop only for
the same eligible visible scope and only if focus fell to the page body. Never
steal focus from another control or restore it across lifecycle invalidation.

Component tests exercise explicit reads, unknown-state stop, exact long values,
MAX saturation, all mutation failure/reconciliation states, one-flight behavior,
completed journeys, cancellation and late responses, account/journey switching,
offline/foreground transitions and no automatic network work. Parent tests prove
mounting only for eligible confirmed journeys and invalidation during refresh or
completion. Existing backend consent tests remain the server authority evidence.

Render the actual component at desktop and narrow widths using a clearly labelled
test-only visual fixture if real authentication is unavailable. Such a fixture is
not a production route, authentication bypass or evidence of real sign-in. Keep
browser verification and authenticated end-to-end verification distinct. Run
affected React tests, types/lint/format and the web production build; no unrelated
backend rerun is needed for frontend-only changes.
