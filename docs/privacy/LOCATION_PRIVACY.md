# Location privacy
A displayed city is an explicit discovery context, not detected GPS. Optional route planning now accepts deliberate typed searches and a one-time browser location reading only after an explicit button action. When configured, search text is sent to the controlled Photon service on submit; selected endpoints go to Valhalla on Calculate. No watch, background tracking, persistence, analytics or social disclosure is implemented. Temporary place results and route geometry stay in the current view. Clearing or changing an account discards the mounted form; cancellation ignores late responses. Do not request actual user location during agent QA.
The pilot runs on active input only (see [`docs/PRODUCT.md`](../PRODUCT.md)). Posts, voice notes and one-tap signals are tied to a Spot, not to the sender's position, and expire by type. The server collects no continuous user location, derives no presence and publishes no traveller counts. Report and reply counts are allowed; traveller counts return only after a separate privacy review.
No individual stranger dots, exact endpoints, stable trackable identifiers or coordinate-based enumeration.
Earlier raw-GPS ingestion, minimum-crowd-size and aggregation rules protected passive presence, which the pilot does not collect. They are archived under [`docs/archive/`](../archive/README.md) and revisited only if aggregate counts return. The existing pure domain policy for expiry, Ghost exclusion and minimum crowd size remains in code but is not a pilot control; an isolated policy is not proof of end-to-end anonymity.

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

## Journey map position

Proposed in [ANDROID_JOURNEY_MAP_SPEC.md](../features/journey/ANDROID_JOURNEY_MAP_SPEC.md)
and ADR 0067; not implemented. Android shows the traveller's own position on the
Journey map using while-in-use permission, requested only from an explicit action
in Journey mode. Updates run only while Journey mode is visible in the
foreground, readings stay in memory on the device, and they are used only to draw
the dot and order Spots ahead along the device-only journey route. No position,
route or endpoint is sent to any server; the server receives only the IDs of
Spots whose activity is requested, and does not log them.

## Spot passage

Planned for the pilot; not implemented unless
[`BUILD_STATUS.md`](../quality/BUILD_STATUS.md) says so.

Spot passage ("I passed Spot X") is opt-in. One clear opt-in covers it; Ghost
Mode and sign-out stop it. Detection runs on the device, in the foreground, only
during an active journey. With the Journey map position below, this is one of
two carve-outs from "no watch or background tracking" above: no location is watched outside an active journey, and no trail,
coordinates or distance are uploaded. The device sends only the answer (or a
request to receive Ask Ahead questions for that Spot) with a coarse time. The
server deletes Spot-passage records within 24 hours and logs them only as outcome
codes. Other users never see who passed a Spot; see
[`ANTI_STALKING.md`](ANTI_STALKING.md) for Ask Ahead recipient rules.

Android mechanism (decided 2026-09-25): a location foreground service with its
required visible notification, started while the app is in use and stopped when
the journey ends, at balanced accuracy about every 100 m or 30 s. No
"allow all the time" background-location permission is requested. The phone
matches locally against the next ~20 Spots ahead on the planned route; a pass is
entering about 150 m of a Spot and then continuing past it. Location readings
stay on the device and are not persisted beyond what matching needs. Battery
target about 3–4% extra per hour, measured on the Phase 1 phones.

Ghost Mode stops all sending, including Spot passage and queued posts, and takes
priority over reconnect and outbox replay.

## Driver safety

A contribution prompt is not a safety assurance. The post-passing prompt is a
quiet, dismissible card that expires if unanswered; it is shown only when the
phone reports the vehicle stopped or slow, or to a user who said they are a
passenger. Never show a blocking modal or sound while moving. Contributions are
never required, and voice notes are recorded only by explicit action. The earlier
per-send stopped/passenger acknowledgement is retired with the private LIVE flows.

## Archived private LIVE boundary

ADRs [0022](../adr/0022-live-list-and-structured-evidence.md)–[0061](../adr/0061-native-private-route-preparation.md)
defined the LIVE list, private journey contribution consent (ADRs 0026, 0029,
0030, 0059), private route preparation (ADRs 0032, 0034, 0061), private
contribution choices (ADRs 0043–0045), private command stopping (ADRs 0046/0047),
the ADR 0054 irreversible Share and the ADR 0055 V3 community traffic summary.
Their product, consent and publication specifications are archived under
[`docs/archive/features/live/`](../archive/README.md); infrastructure specs
stay in [`docs/features/live/`](../features/live/) with a status note. That code remains in the
repository, default-off, and its private boundaries still hold if it runs: route
preparation and consent never establish physical presence, public visibility or a
right to contribute to a public feed; opaque context or anchor identifiers never
become public labels. No stored private report, consent or receipt becomes public
through a migration. No automatic nearby or location discovery is authorized.
