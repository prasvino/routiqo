> **Archived 2026-09-25.** Per-journey consent and private LIVE contribution flows, replaced by one Spot-passage opt-in, simple Ghost Mode and public Spot signals. See [docs/archive/README.md](/docs/archive/README.md) and [docs/PRODUCT.md](/docs/PRODUCT.md). Kept as a historical record; not current requirements.

# Private route preparation verification — 2026-09-19

Scope: actual `LiveConsentPanel`, `RoutePlanner`, `RouteResults` and
`LiveRouteBindingPanel`, using shared CSS/tokens in the labelled loopback fixture
under ignored `.patch-work/route-binding-ui`. Consent, routing and context transport
were simulated. No real account, GPS, provider, backend flag or production bypass.

## Rendered and interaction evidence

- Desktop 1280 px and narrow 390/320 px inspected. New route controls wrap without
  horizontal overflow; narrow buttons measure 44 CSS px high. At 320 px the
  document content width is 305 px plus its vertical scrollbar, within the viewport.
- Explicit settings check/allow, typed synthetic place selection, route calculation,
  context check and preparation acknowledgement exercised through actual controls.
  The disclosure explains the additional endpoint/mode request and that fresh
  geometry may differ from the displayed estimate. No opaque IDs appear as labels.
- Enter activates Prepare and focus returns to Check when Prepare disappears.
  Scoped polish corrected the inherited amber focus outline to existing ink:
  solid 2.4 px, 11.78:1 against white. Body/label text is 4.54:1 on white; secondary
  button text is 10.01:1 on its background. No animation was introduced.
- Selecting the second alternative clears the previous acknowledgement immediately.
  A no-route result explicitly says an earlier context may remain; a conflict
  requires another explicit check. No successful preparation is inferred from GET.
- A delayed abort-ignoring fixture bind was started, then Stop was selected.
  Preparation controls disappeared and stayed absent after the late result.
  Simulated offline/reconnect also left preparation absent while loaded directions
  stayed visible; no reconnect preparation request was made.
- A short-lived simulated acknowledgement expired into an explicit expired state.
  An expired returned observation was rejected. Automated tests cover passive
  expiry without traffic and nanosecond future timestamps.

## Automated and review evidence

Full integration: **365 TypeScript tests /45 files**, workspace typechecks,
repository lint/format, generated-contract check and secret scan passed. Web
production build passed. Existing backend was unchanged; no Java rerun was needed.

Review corrections include parent identity/offline invalidation, completed-journey
callback fencing, future/expired timestamp checks, passive acknowledgement expiry,
and scope-tagged notices so old confirmations cannot appear under new route input.
Independent final review approved the UI after the last two corrections. Targeted
tests include late responses, copied route inputs, exact null/UUID expectations,
one flight, scope changes and existing offline direction retention.

Routing transport review also corrected an oversized-stream test so it supplies
JSON content type and reaches the byte counter rather than an earlier media check.
Tests cover shared deadlines, late CSRF/no POST, abort-ignoring headers/streams,
late cleanup, invalid encodings/statuses/redirects and redacted errors.

## Remaining verification

Real Google authentication, configured regional services/catalog, protected backend
end-to-end lifecycle, browser text zoom, assistive technology and device QA remain.
The fixture is not a public LIVE list or Quick Signal submission and does not prove
physical presence, anonymity, production readiness or real-provider behavior.
