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
