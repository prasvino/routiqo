# Location privacy
A displayed city is an explicit discovery context, not detected GPS. Optional route planning now accepts deliberate typed searches and a one-time browser location reading only after an explicit button action. When configured, search text is sent to the controlled Photon service on submit; selected endpoints go to Valhalla on Calculate. No watch, background tracking, persistence, analytics or social disclosure is implemented. Temporary place results and route geometry stay in the current view. Clearing or changing an account discards the mounted form; cancellation ignores late responses. Do not request actual user location during agent QA.
Future raw GPS must pass restricted ingestion, route matching, privacy transformation and aggregation before social outputs.
No individual stranger dots, exact endpoints, stable trackable identifiers or coordinate-based enumeration.
Pure domain policy can enforce expiry, Ghost Mode exclusion, and configurable minimum crowd size; an isolated policy is not proof of end-to-end anonymity. Production thresholds, query budgets and smoothing require a dedicated presence specification before enabling exposure.

## Open-source migration target (ADR 0021)

The user confirmed MapLibre with Routiqo-controlled Valhalla/Photon/Martin.
Web rendering now uses MapLibre with explicitly configured same-origin `/maps/` resources. Opt-in backend routing/search selects guarded Valhalla and Photon; live regional service verification remains pending. Search and route inputs use configured controlled services;
own-host tile/style/font/sprite resources too. Public tile providers can observe
IP and viewport requests, so are not an automatic private fallback. Hosting
providers and operational logs remain part of the privacy boundary. Minimize
logs and never promise zero third-party exposure merely because software is open
source. Preserve explicit location intent and no public individual tracking.

Photon is selected by the opt-in routing configuration with matching browser
disclosures. Verify internal request-URL logging controls and service ownership
before accepting real search text.

## Routiqo Live planned boundary

The first release displays situations through a journey LIVE list, not live people.
Canonical scope and gates: ROUTIQO_LIVE_SPEC.md and PRESENCE_SPEC.md. No counts,
member lists or precise traveller markers. Route admission establishes relevance,
not physical presence. Read access must not implicitly publish presence. Ghost
withdrawal invalidates existing evidence for future projections as well as future
submissions; use reviewed suppression rather than exposing count differences.
Moment existence/freshness can also reveal people, so minimum cohorts, fixed
partitions/windows and repeated-query controls require adversarial validation.
No automatic nearby/location discovery is authorized by this plan.

## Private journey contribution choice

The active-journey consent controls concern private contribution preparation only.
They neither publish a location nor make the account discoverable. Future public
participation requires a separately reviewed purpose, disclosure and authorization;
an existing private sharing boolean is insufficient. Stopping contribution
preparation does not delete retained private receipts. A cancelled or failed write
is uncertain even after a later read: the UI retains that warning until an explicit
stop is acknowledged. See `BROWSER_LIVE_CONSENT_UI_SPEC.md` for lifecycle rules.

## Private route preparation

`BROWSER_ROUTE_BINDING_UI_SPEC.md` defines the private browser integration.
Preparing a route is a separate explicit action after confirmed private consent;
ordinary route planning does not require participation. Preparation sends the
selected endpoints and mode to the configured routing service again and may
resolve different geometry from the displayed estimate. It never establishes
physical presence, public visibility or a right to contribute to a public feed.

The browser keeps only a memory-scoped observation and acknowledgement. A context
read contains no input fingerprint and cannot prove that the displayed route was
prepared. Cancellation cannot undo a server write; a later read is not a fence
against a delayed earlier write. Empty preparation results preserve any previous
server context. Consent, identity, route-choice and foreground changes invalidate
local readiness immediately; reconnect and expiry never trigger automatic traffic.
No opaque context or anchor identifiers become public labels or location choices.

## Private contribution choices

ADR 0045's separately gated owner transport returns only current authorized
route-area labels/categories and exact context/consent versions. Labels are
operator-curated public place names, never traveller-derived addresses. The
snapshot still reveals private route intent to its owner and must remain no-store,
bounded and absent from logs, analytics, backups and other-user surfaces.

Reading choices does not create grants or publish information. An explicit
expected-context issuance rechecks current authority; its tuple is not proof of
presence or independent evidence. Cancellation or rejecting an expired response
cannot undo a server-side issuance. Clients must not silently refresh/reissue or
fall back to the legacy anchor-only contract. Public eligibility and disclosure
remain governed by the separate cohort and safety gates.
