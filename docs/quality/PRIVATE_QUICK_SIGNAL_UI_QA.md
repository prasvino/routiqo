# Private Quick Signal browser verification — 2026-09-19

Scope: actual `PrivateSignalsPanel`, recovery coordinator and navigation hook,
plus actual `JournalEditor` for navigation coexistence. Rendered through shared
tokens/CSS in the labelled loopback fixture under ignored
`.patch-work/private-signals-ui`. Account, prepared authority, signal transport and
journal storage/transport were synthetic. No real account, provider, GPS, feature
activation or public signal was used. ADR 0048 and
`../features/live/BROWSER_QUICK_SIGNAL_UI_PLAN.md` define the boundary.

## Rendered checks

- Inspected the existing planning preview before scoped changes. Desktop 1280 px
  and narrow 320 px form/recovery states rendered without horizontal overflow.
  At 320 px, content width was 305 px plus the scrollbar; selects and action
  buttons measured 44 CSS px high. A long unbroken catalog label did not widen
  the form. Native select values can truncate visually; full option names remain
  available through the native option list and accessibility tree.
- Explicit choice check, route area/category selection, fresh stopped/passenger
  intent and keyboard submission were exercised. Options contained only allowed
  categories. Accepted copy explicitly distinguished private storage from public
  LIVE. No opaque account, journey, context or command IDs were visible labels.
- Increased introductory/status text from inherited 12 px to 14 px and restored
  focus to an available action when submission removes the focused form. Tests
  also prove a late result does not steal focus from an external control.
  Applied the existing ink focus color to buttons, checkbox and selects instead
  of the inherited amber ring. No motion was introduced.
- Simulated Ghost and completion retained known command controls. A failed Stop
  remained unconfirmed with explicit retry; offline disabled Stop and explained
  that nothing is queued. Successful Stop retained truthful historical copy.
  The local-removal dialog clearly separated local removal from stopping/deletion.
- Scoped 200% CSS scaling with a constrained layout exercised large-text reflow;
  labels, safe-interaction text, statuses and actions remained readable and the
  updated fixture had no page overflow. This is a reflow simulation, not verified
  browser text-only zoom or OS accessibility scaling.

## Actual history and automation limits

Actual browser Back with known recovery invoked the warning; simulated denial
restored the workspace with the same command. With the actual dirty journal above
the signal guard, Back opened the journal discard prompt. Continue writing kept
edits; explicit journal close/discard preserved the subsequent signal warning.
The reverse order was exercised by scheduling a known command after the journal
became dirty. Closing that journal preserved signal recovery and subsequent Back
still warned before departure. An older same-page journal entry can require an
additional Back step in this reverse-order case; no handle was lost.

The in-app browser automation stalled on native `window.confirm`. The combined
history fixture therefore exposed an explicit simulated confirmation decision and
warning count/text while running real history and both production handlers.
An additional isolated ignored Next.js fixture on loopback ran the actual panel,
hook and Next Link/router. Cancelling a Link or Back retained recovery; confirming
either reached the fixture's `/away` route through SPA navigation. The fixture
mounted its synthetic confirmed identity only on the client, matching the actual
workspace's asynchronous identity boundary. Native confirmation/unload acceptance
and real authenticated navigation remain unverified. Component tests cover accepted/cancelled link/Back continuation,
router/foreign-state preservation, restoration and bounded repeated guard cycles.
No claim of complete browser/device navigation QA follows from this fixture.

Actual fragment navigation exposed an additional bug: native hash changes emit
`popstate` with null state, which the guard initially treated as departure. The
correction tracks the last observed URL and permits fragment-only transitions
without entering restoration or skipping a history entry. Fresh-browser tests
covered adding/removing a fragment, Back and Forward with zero warnings; a later
actual departure still warned. Regression tests include the original empty hash,
multiple fragments, trailing `#`, resolved handles and the subsequent sentinel
departure. The final source and tests received independent review.

## Automated coverage and release gates

Coordinator tests cover five-record capacity, no silent eviction, account clearing,
ticket/phase fencing, metadata retention and terminal/null receipt behavior.
Component and workspace integration tests cover fresh-bind-only authority, exact
context/category/expiry, single issuance/acceptance, synchronous invalidation,
late responses, offline/foreground transitions, uncertain results, same-account
refresh, account switches, Ghost/completion/remount recovery and explicit stopping.
Expiry remains cleared after clock rollback; focus and unavailable-empty-panel
behavior have regressions. Final suite/build/secret-scan evidence is recorded in
`BUILD_STATUS.md` rather than duplicated here.

Independent source review corrected coordinator ownership, late acceptance state,
parent consent invalidation and both navigation guard orders. No unresolved
material source finding remained after corrections. Real OAuth, configured
regional services/catalog, protected backend end-to-end flows, assistive technology,
native confirmation behavior and device QA remain release gates. Public publication,
anti-Sybil evidence, reporting and operator moderation remain separate pending work.
