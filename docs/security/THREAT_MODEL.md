# Routiqo Threat Model

This document defines Routiqo's baseline attacker model, protected assets, trust boundaries, abuse cases, and required mitigations. Update it whenever a feature, provider, data flow, permission, trust boundary, or attacker capability materially changes.

This is a living engineering artifact, not a compliance checklist. Feature specifications should link to the applicable threats and add feature-specific abuse cases.

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

A change must not ship when it introduces an unmitigated Critical or High threat, silently weakens a security/privacy/safety invariant, lacks required authorization or abuse tests, or leaves sensitive behavior ambiguous.

Risk acceptance must be explicit, authorized, time-bounded where appropriate, and accompanied by an owner and follow-up plan.

