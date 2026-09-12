# Routiqo Threat Model

This document defines Routiqo's baseline attacker model, protected assets, trust boundaries, abuse cases, and required mitigations. Update it whenever a feature, provider, data flow, permission, trust boundary, or attacker capability materially changes.

This is a living engineering artifact, not a compliance checklist. Feature specifications should link to the applicable threats and add feature-specific abuse cases.

Native session storage addition (2026-09-12): the mobile runtime now has an Expo SecureStore boundary for opaque credentials, separate from journey SQLite storage. T01/T02/T12/T13/T19 controls include fixed-key bounded records, device-only unlocked accessibility, Android backup exclusion, instance-bound one-use write tickets, serialized clear/write operations, expiry rechecks and redacted fail-closed storage errors. See `docs/features/auth/NATIVE_SESSION_STORAGE_SPEC.md` and ADR0018. This primitive is not yet mounted in a sign-in flow; server verification, native HTTP transport, logout revocation, challenge binding and physical-device/Keychain lifecycle validation remain gates. Storage tests do not establish authenticated access or physical erasure.

---

## 1. Security and safety objectives

Routiqo must:

- prevent unauthorized access and cross-user actions;
- protect identity, precise location, presence, travel patterns, home/work inference, private communications, and social relationships;
- resist stalking, harassment, coercion, scams, impersonation, and unwanted discovery;
- preserve integrity of routes, rooms, reports, blocks, moderation, and user-generated content;
- remain available under realistic abuse and resource pressure;
- contain compromise of a user, device, provider, dependency, service, or administrator;
- support investigation and recovery without excessive sensitive-data collection.

---

## 2. Protected assets

- accounts, sessions, tokens, credentials, recovery mechanisms, and roles;
- current and historical location, route endpoints, presence, travel timing, and recurring patterns;
- home/work and other sensitive-place inferences;
- profiles, visibility settings, blocks, reports, moderation status, and social graph;
- route communities, room membership, messages, calls, notifications, and user-generated content;
- device identifiers, contact information, analytics, audit data, and support records;
- API, database, cache, realtime, queue, storage, CI/CD, signing, and infrastructure credentials;
- service availability, data integrity, moderation integrity, and user trust.

---

## 3. Threat actors

- unauthenticated internet attacker;
- normal but malicious authenticated user;
- stalker, harasser, scammer, impersonator, spammer, scraper, or coordinated abuse group;
- compromised user account or device;
- compromised administrator, support account, service identity, or CI/CD credential;
- malicious or compromised dependency, SDK, provider, integration, or build artifact;
- curious or negligent insider;
- automated botnet or denial-of-service actor.

Do not assume authenticated users are trustworthy.

---

## 4. Trust boundaries

Native authentication HTTP addition (ADR 0020): an isolated opt-in namespace
returns opaque session credentials and challenge bindings to the device. Unlike
browser auth, this transport uses explicit bearer credentials and rejects ambient
cookies/browser-origin headers. Header rejection is not proof of native-app origin.
T01/T02 controls reuse server-side challenge binding, one-time exchange, enabled
account checks, lineage rotation/revocation and recent-auth deletion. T12 requires
redacted errors/DTO strings, no-store responses, TLS deployment, secure vault storage
and no URL credentials. T14 retains bounded strict JSON parsing, exact route/method
allowlists, database-backed rates and expiry cleanup. T13/T19 require late-response
invalidation and vault/deletion coordination before the mobile flow is mounted.
Native resource APIs and redirect-safe device transport remain separate gates;
browser journey/route endpoints do not gain bearer fallback through this change.

Review data whenever it crosses:

- device/browser ↔ public edge/API;
- public edge ↔ application services;
- HTTP services ↔ realtime infrastructure;
- service ↔ database, cache, queue, object storage, search, or analytics;
- Routiqo ↔ map, notification, identity, communication, moderation, or other providers;
- consumer ↔ administrator/support tooling;
- source repository ↔ CI/CD ↔ artifact registry ↔ runtime;
- one user, room, route, tenant, or visibility cohort ↔ another.

Authentication at one boundary does not eliminate authorization, validation, minimization, or abuse controls at the next.

---

## 5. Baseline threat register

| ID | Threat / abuse case | Example impact | Required control direction |
|---|---|---|---|
| T01 | Authentication or session attack | account takeover, session fixation, token replay | strong token validation, secure storage, revocation, rotation, replay resistance, anomaly detection |
| T02 | Broken object authorization / IDOR | read or modify another user's profile, route, room, report, or content | server-side object/action authorization, ownership isolation, negative cross-user tests |
| T03 | Privilege escalation | consumer reaches admin/support/internal actions | deny-by-default roles, separated admin boundary, least privilege, audit |
| T04 | Location or presence disclosure | stalking, home/work discovery, longitudinal tracking | server-side privacy transformation, consent, precision reduction, expiry, cohort protection |
| T05 | Enumeration and scraping | discover users, rooms, presence, blocks, or private state at scale | opaque identifiers, response normalization, query bounds, rate limits, anti-automation controls |
| T06 | Block/Ghost Mode bypass | blocked user observes target through another endpoint or channel | central policy enforcement across API, realtime, cache, notifications, search, and export |
| T07 | Realtime subscription abuse | unauthorized topic access, replay, stale authorization, event leakage | subscription authorization, re-evaluation, scoped topics, expiry, replay limits |
| T08 | Harassment, threats, stalking, or coercion | physical or psychological harm | block/report/mute, moderation, evidence-safe handling, anti-evasion, safety escalation |
| T09 | Scams, impersonation, spam, or malicious links | fraud, account compromise, unwanted contact | identity and reputation controls, content/link defenses, rate limits, reporting and moderation |
| T10 | Injection or unsafe content | SQL/NoSQL/command injection, XSS, template injection | parameterization, validation, encoding, CSP, safe rendering, least privilege |
| T11 | CSRF, SSRF, unsafe redirects, traversal, or file abuse | action forgery, internal access, credential theft, code/data exposure | origin/session protections, outbound allowlists, canonicalization, upload isolation |
| T12 | Sensitive-data leakage | tokens, PII, location, private state in logs/errors/analytics/cache | minimization, redaction, access control, safe errors, lifecycle enforcement |
| T13 | Replay, race, or duplicate action | duplicate posts, bypassed limits, inconsistent block/privacy state | idempotency, atomic checks, concurrency tests, authoritative shared state |
| T14 | Resource exhaustion / DoS | degraded route search, rooms, realtime, upload, notification, or database | multi-dimensional limits, timeouts, backpressure, quotas, load shedding, isolation |
| T15 | Moderation evasion | ban/block bypass, coordinated abuse, evidence deletion | durable policy state, evasion signals, appeal controls, audited moderation actions |
| T16 | Supply-chain or CI/CD compromise | malicious release, secret theft, runtime takeover | pinned dependencies, secret isolation, provenance, protected branches, review, artifact verification |
| T17 | Provider/integration compromise | location, identity, notification, or message leakage/manipulation | data minimization, scoped credentials, egress limits, isolation, rotation, provider review |
| T18 | Insider/admin misuse | unauthorized lookup, surveillance, modification, or export | least privilege, purpose limitation, strong auth, tamper-resistant audit, alerts and review |
| T19 | Data-retention/deletion failure | supposedly deleted or expired data remains accessible | lifecycle propagation to caches, indexes, exports, analytics, backups, and realtime state |
| T20 | Multi-replica inconsistency | bypassed rate limit, stale presence, divergent authorization or block state | shared authoritative state, atomic operations, deterministic expiry, distributed tests |

---

## 6. Location and presence abuse cases

Every location, route-community, route-room, nearby-user, live-update, or stranger-discovery feature must evaluate:

- repeated queries that reconstruct movement over time;
- small cohorts or low-traffic routes that reveal a specific person;
- map marker, count, timestamp, ordering, distance, or error differences that reveal presence;
- route origins/destinations that identify a home, workplace, school, hospital, or place of worship;
- correlation across accounts, devices, rooms, routes, notifications, or external datasets;
- block or Ghost Mode bypass through cached data, alternate accounts, shared rooms, notifications, or realtime replay;
- stale presence persisting after logout, disconnect, permission withdrawal, expiry, block, or deletion;
- precise location leaking to clients even when UI shows an approximation;
- malicious users manipulating location or presence to lure, harass, mislead, or target others.

Required design evidence includes the minimum collected precision, server-side transformation, audience policy, consent model, retention/expiry, anti-enumeration behavior, failure state, and multi-replica enforcement.

---

## 7. Abuse and safety cases

Feature design and tests must consider:

- direct and indirect threats;
- repeated unwanted contact and block evasion;
- stalking or coordinated tracking;
- scams, payment solicitation, phishing, and malicious links;
- impersonation and false affiliation;
- requests for personal information or movement details;
- dangerous misinformation about routes, incidents, or emergencies;
- hate, sexual exploitation, and other prohibited content;
- brigading, false reporting, moderation gaming, and retaliation;
- account farming, automation, and coordinated abuse.

Controls may include friction, rate limits, privacy-safe defaults, reporting, blocking, muting, moderation, evidence retention, appeals, trust signals, and escalation. The exact control must match the feature and risk.

---

## 8. Required threat analysis for changes

For each material feature or architectural change, record:

1. assets and sensitive data involved;
2. actors and attacker capabilities;
3. trust boundaries and new data flows;
4. abuse cases, including malicious authenticated users;
5. privacy and physical-safety impact;
6. likelihood, impact, and affected population;
7. preventive, detective, and recovery controls;
8. tests and monitoring;
9. residual risk and accountable owner;
10. conditions that require reassessment.

Use data-flow diagrams when a change crosses multiple services or providers.

---

## 9. Security verification

Verification should include, as applicable:

- unauthenticated, wrong-user, blocked-user, revoked, and expired cases;
- identifier guessing, bulk enumeration, scraping, and timing/error comparison;
- replay, race, duplicate, reconnect, stale-cache, and multi-replica behavior;
- malicious payloads and unsafe content rendering;
- resource-limit and controlled degradation tests;
- privacy transformation and raw-data non-disclosure;
- block, Ghost Mode, expiry, deletion, and moderation propagation across every channel;
- dependency, secret, infrastructure, and provider-boundary review.

Automated scanning supports but does not replace adversarial testing and manual review.

---

## 10. Incident-driven updates

After a vulnerability, attack, abuse incident, near miss, provider change, or newly discovered bypass:

- update the applicable threat and control;
- add regression tests and monitoring;
- check equivalent endpoints, channels, and features;
- reassess residual risk and response procedures;
- link the remediation decision or ADR without including exploit secrets or victim data.

Security-sensitive details must remain in approved private systems.

---

## 11. Release gate

Web maps boundary (2026-09-12): explicit Show map loads Mapbox GL with a separate
public token; backend routing credentials remain server-only. Route geometry and
validated instructions remain in application memory, with no temporary geocoding
cache or automatic location watcher. Mapbox SDK tiles and event metadata can
persist in browser storage across sign-out/account deletion. ADR 0019 records the
Medium residual-risk disposition and pre-load/privacy disclosure; the root
implementation owner must verify provider-cache lifecycle before release. No
claim of complete browser cache erasure or downloaded offline navigation is made.
See `docs/features/journey/MAPS_NAVIGATION_SPEC.md` and
`docs/privacy/DATA_RETENTION_AND_DELETION.md` for boundaries and removal guidance.

A change must not ship when it introduces an unmitigated Critical or High threat, silently weakens a security/privacy/safety invariant, lacks required authorization or abuse tests, or leaves sensitive behavior ambiguous.

Risk acceptance must be explicit, authorized, time-bounded where appropriate, and accompanied by an owner and follow-up plan.

## Open-source map migration controls (ADR 0021, 2026-09-12)

Target rendering/routing/search/tiles are MapLibre, Valhalla, Photon and Martin.
Current Mapbox findings remain until migration. Self-hosting retains T01/T02 account
authorization, T04/T12 endpoint/viewport privacy, T13 cancellation/update races,
T14 resource exhaustion and T19 retention/deletion obligations. Internal service
URLs are configuration, never caller input; constrain outbound destinations and
redirects, keep services private, and preserve database-backed rate limits and
bounded bodies/timeouts. Do not log search terms, coordinates or sensitive URLs.
Inspect style/sprite/glyph URLs for external requests. Dataset/archive ingestion
requires trusted sources, pinned versions, integrity checks and bounded extraction;
updates need atomic activation/rollback and disk headroom. Offline packages must
not leak account-associated region selections or resurrect deleted route state.
Synthetic tests do not validate real regional coverage or native offline guidance.

## Web MapLibre asset boundary (ADR 0021)

MapLibre GL JS 6.9.0 loads only after explicit Show map. The configured style must
be a bounded same-origin `/maps/...json` path. Resource transformation rejects
external origins, credentials, query/fragment, out-of-namespace URLs, encoded
separators and nested traversal; inline data and same-origin blobs are allowed.
Blocked requests resolve to the constant `/maps/__blocked_map_resource__` without
forwarding the original URL. The deployment must reserve this as a static failure.

This is a trusted static-service boundary, not a sandbox for arbitrary styles.
Cookies may accompany same-origin resource requests and SDK transports may follow
redirects. Release verification must establish no redirects or authenticated APIs
in `/maps/`, no credential/location-linked access logs, reviewed styles and nested
assets, and actual browser network behavior. Do not claim cookie-free requests or
redirect containment from transformRequest alone. Browser caches and legacy
Mapbox caches survive account deletion; disclosure and site-data removal remain.

## Photon adapter boundary (ADR 0021)

The opt-in Photon place adapter accepts an operator-constructed fixed service
origin, never a traveller-supplied destination. It encodes submitted search text
and returns only bounded IDs/labels/coordinates with fixed OSM attribution. Strict
JSON shape, duplicate-key/trailing-data rejection, UTF-8 byte limits and coordinate
validation contain malformed or compromised provider responses. Transport refuses
redirects, bounds chunked bodies and rejects malformed UTF-8. Provider failures
are replaced with generic errors without a cause; interruption is preserved.

This is not an egress firewall or permission to expose internal Photon publicly.
Configured DNS, destination ownership, TLS/private network and proxy/service logs
must be reviewed before enabling the authenticated routing profile in a deployment. Search
terms appear in the internal provider query URL; infrastructure must avoid request
URL logging. No public demo fallback, browser configuration switch, persistence,
proximity tracking or dataset download is introduced by the adapter or runtime wiring.
Review found and corrected a shared transport slow-body gap: request-header timeout alone did not bound body completion. A whole-operation10s deadline now cancels the HTTP future on timeout/interruption; loopback stalled-body regression exercises this boundary.

## Valhalla adapter and routing POST boundary

The opt-in Valhalla adapter sends two precise endpoints in a bounded JSON body
to a fixed operator-owned `/route` destination. No coordinates, account IDs or
browser credentials enter the provider URL. POST retains only200/400 responses
for strict adapter interpretation; redirects and unexpected statuses fail. Whole
body deadlines, interruption cancellation, request/response byte caps and strict
UTF-8 apply to both HTTP methods. Response wrappers redact their bodies in toString.

Treat route shapes, maneuver indices, distances, units, instructions and error
codes as untrusted. Polyline6 parsing must bound varints, accumulation and point
counts; reject invalid coordinates/index spans and malformed alternatives rather
than returning partial fabricated guidance. Explicit no-path classification must
not disguise service errors or missing regional data. Before mounting, add
reviewed coverage/configuration and verify service egress, dataset attribution,
logging suppression and route quality. No live provider/region setup is implied.

The independent regional guard in ROUTING_COVERAGE_SPEC.md rejects out-of-region
endpoints before any provider call and rejects out-of-region returned geometry or
maneuvers as a whole response. Bounds are a conservative operator-controlled
non-antimeridian rectangle, not proof that all roads inside it exist in the graph.
No coordinates enter coverage error messages or region string representations.
The opt-in routing configuration now wraps Valhalla in this guard and requires
both provider origins and every regional bound. Authenticated HTTP/client coverage
handling is implemented; reviewed datasets and live service validation remain
deployment gates. No route clipping or invented rerouting.

Routing runtime migration: see ROUTING_RUNTIME_SPEC.md. Missing or malformed
operator settings fail startup without raw values or parsing causes. Both services
are configured together with no public defaults or legacy fallback. Configuration
is trusted; syntactically valid hosts are not proof of ownership or DNS safety.
Review egress, private ports, TLS and log suppression (especially Photon query
URLs) before enabling services. Deploy the matching browser disclosure version
with the backend; previous Mapbox deployments must not serve the new disclosure.

## Routiqo Live: first-list threats (planned)

Admission implementation boundary (ADR 0023): immutable context/admission objects
are internal snapshots, not proof of identity or public capabilities. A malicious
client can invent every field; HTTP must never deserialize these as authority.
Snapshot consistency checks must be coupled to writes using current authoritative
revisions across replicas. Coarse anchor sets are sensitive route intent even
without raw geometry. Record constructors, short expiry and matching generations
do not establish physical presence, prevent Sybils or approve public aggregation.

Scope and lifecycle: `docs/features/live/ROUTIQO_LIVE_SPEC.md`; ADR 0022.
No Live capability is enabled by this planning change. Treat authenticated actors
as potentially malicious, including colluding accounts and commercial spammers.

| Abuse case | Required design/verification |
|---|---|
| Fake journey admission or route/anchor scanning | Server-issued short-lived admission to fixed relevant partitions; generic denials and bounded query budgets; route intent is not physical-presence proof |
| Correlating moment appearance, conditions or freshness | Fixed publication windows, sparse-evidence suppression, adversarial temporal/overlap tests; removing exact counts alone is insufficient |
| Block/Ghost/deletion differencing | No individually tailored count subtraction; reviewed suppression and cache invalidation; withheld accepted evidence cannot return after retry/reconnect |
| Coordinated false signals and commercial manipulation | Per-actor contribution replacement, duplicate suppression, independent corroboration rules, conflict states, moderation and accountable operator review |
| Stale evidence replay or clock manipulation | Server-time expiry, exact idempotent retries, no offline report dispatch or resurrected expired moment |
| Revocation races across replicas/cache/HTTP | Authoritative generations and current read/write checks; fail closed; do not rely solely on Pub/Sub |
| Log/telemetry and source leakage | No raw GPS, private membership, per-user query traces or report payloads in observability; bounded operational outcomes only |
| Resource exhaustion and scraping | Bounded list/payload/cardinality, distributed read/write/peer budgets, cleanup capacity tests and no arbitrary spatial queries |
| Unsafe recommendations or driver distraction | Structured condition bands, uncertainty/source labels, no safety assurance from silence, no proactive driving prompts or lane-specific guidance |
| Future Ask Ahead harassment/targeting | Separate gate for recipient consent, limited nondeterministic selection, repeated-target budgets, blocks and no recipient identifiers |

Unresolved cohort/admission and evidence-retention mechanisms are explicitly listed
in ADR 0022. They require design and adversarial acceptance before public output;
a minimum actor threshold alone is not proof of anonymity. Previously delivered
in-memory rows cannot be remotely erased while a client is offline: local events
clear them and bounded expiry limits display. Do not claim stronger revocation.
