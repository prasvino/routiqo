# Routiqo Security Standard

Security, privacy, anti-abuse, and safety controls are correctness requirements. They must be designed, implemented, tested, reviewed, and operated as product behavior—not treated as optional hardening.

Use `THREAT_MODEL.md` for attacker analysis and abuse scenarios. Use `CODE_REVIEW.md` for review gates.

---

## 1. Non-negotiable invariants

- Protected HTTP, realtime, background-job, admin, and internal-service operations require authenticated and authorized access.
- Authorization is enforced server-side for every object and action; possession of an identifier is never sufficient.
- Ownership and tenant boundaries apply to reads, writes, search, exports, notifications, caches, and realtime events.
- Deny by default when identity, permission, privacy state, or policy evaluation is missing, stale, ambiguous, or fails.
- Raw stranger GPS and unnecessary precise-location data are never exposed.
- Home/work endpoints and sensitive repeated-location patterns are protected from direct and inferred disclosure.
- Presence is privacy-transformed server-side before distribution.
- Discoverability is consent-based; Ghost Mode, block, visibility, expiry, and deletion rules apply across every delivery channel.
- Blocked, hidden, expired, or deleted information must not reappear through search, caches, exports, logs, notifications, analytics, or realtime replay.
- Tokens, credentials, secrets, and precise location are absent from fixtures, source control, logs, analytics, error payloads, and screenshots.
- Inputs, payloads, queries, fan-out, retries, subscriptions, uploads, and expensive operations are bounded.

Foundation environments must have no consumer-authentication bypass: protected paths are denied, and administrative surfaces reveal no records without explicit authorization.

---

## 2. Identity, sessions, and authorization

- Use established authentication and session architecture; do not invent alternate bypass paths.
- Validate issuer, audience, signature, algorithm, expiry, not-before time, and intended token use.
- Rotate and revoke credentials safely; use short-lived credentials where practical.
- Protect session/token storage and transmission; never place durable secrets in client-visible configuration.
- Enforce object-level and action-level authorization on every request and subscription.
- Re-evaluate authorization for sensitive long-lived realtime connections and background delivery.
- Test unauthenticated, wrong-user, blocked-user, revoked, expired, and privilege-change cases.
- Administrative access must be least-privileged, strongly authenticated, audited, and separated from consumer access.

---

## 3. Location, presence, and social safety

- Collect and retain the minimum precision and duration necessary.
- Transform, aggregate, delay, bucket, or suppress location and presence before stranger-facing use.
- Do not expose stable enumerable identifiers that allow longitudinal tracking.
- Prevent presence enumeration through APIs, maps, rooms, counts, errors, timing, notifications, and realtime topics.
- Apply consent, visibility, block, Ghost Mode, and expiry rules before fan-out and again where cached or replayed data is served.
- Treat route endpoints, recurring travel patterns, home/work inference, and small cohorts as sensitive.
- Provide effective report, block, mute, moderation, and emergency-response paths appropriate to the feature.
- Threats, harassment, stalking, scams, coercion, personal-information solicitation, impersonation, and dangerous misinformation must be considered in design and testing.

---

## 4. Input, output, and application security

- Validate type, shape, length, range, encoding, and semantic constraints at every trust boundary.
- Use parameterized database access and context-appropriate output encoding.
- Prevent XSS, injection, CSRF, SSRF, unsafe redirects, path traversal, insecure file handling, unsafe deserialization, and mass assignment as applicable.
- Restrict outbound network access and URL fetching to approved destinations and protocols.
- Validate uploads by size, type, content, name, and storage policy; scan or isolate when required.
- Return minimal errors. Do not disclose secrets, stack traces, internal topology, authorization state, or object existence unnecessarily.
- Apply secure headers, transport security, cookie attributes, CORS, and content policies appropriate to each client.

---

## 5. Abuse and availability

- Apply rate limits and quotas by the identities and resources that attackers can actually abuse; IP-only controls are insufficient.
- Bound pagination, geospatial queries, search radius, realtime subscriptions, room membership, notification fan-out, uploads, exports, and retries.
- Prevent replay, duplicate side effects, request amplification, scraping, enumeration, and resource-exhaustion paths.
- Use idempotency, timeouts, backoff, circuit breaking, load shedding, and safe degradation where appropriate.
- Preserve multi-replica correctness for authorization, rate limits, presence, expiry, blocking, and moderation state.
- Security controls must fail closed where disclosure or unauthorized action is possible.

---

## 6. Secrets, dependencies, and supply chain

- Store secrets only in approved secret-management/runtime facilities.
- Never commit secrets or copy them into documentation, examples, CI output, images, or client bundles.
- Pin and verify dependencies and build inputs according to repository policy.
- Review new providers, SDKs, permissions, network access, data flows, and dependencies in an ADR when architecture or trust boundaries change.
- CI must run credential scanning, dependency vulnerability checks, provenance/integrity checks where configured, and relevant security tests.
- A scanner finding is triaged for reachability, exploitability, exposure, and compensating controls; it is not dismissed solely because no public exploit is known.

---

## 7. Vulnerability handling

When a vulnerability is discovered:

1. preserve evidence and report it through the approved private channel;
2. classify affected assets, versions, environments, users, data, and trust boundaries;
3. assess exploitability, active exploitation, blast radius, and safety/privacy impact;
4. contain exposure without destroying forensic evidence;
5. patch or mitigate the root cause and all equivalent paths;
6. add regression tests and detection where practical;
7. rotate/revoke secrets or sessions when compromise is possible;
8. validate the fix adversarially and check for bypasses;
9. deploy safely and monitor for recurrence;
10. document residual risk, required notifications, and follow-up work.

Do not publish exploit details, sensitive logs, credentials, or victim data in issues, commits, chat, or pull requests.

Critical or actively exploited issues take priority over routine feature work and trigger the incident process.

---

## 8. Cyberattack and incident response

For suspected attack activity:

- treat unusual access, scraping, enumeration, credential abuse, privilege escalation, injection, exfiltration, destructive actions, denial of service, dependency compromise, and moderation evasion as potential incidents;
- preserve relevant audit records and establish a timeline;
- contain affected accounts, tokens, workloads, integrations, or release artifacts using least-destructive effective measures;
- determine whether data confidentiality, integrity, availability, user safety, or privacy was affected;
- coordinate remediation, recovery, notification, and post-incident actions through the authorized incident process;
- verify recovery and monitor for attacker persistence or alternate paths.

Do not improvise offensive retaliation or access systems outside Routiqo's authorization.

---

## 9. Logging, privacy, and data lifecycle

- Log security-relevant events with actor, action, target class, result, and correlation metadata without recording unnecessary sensitive content.
- Prevent log injection and restrict audit-log access and retention.
- Minimize data collection, precision, replication, and retention.
- Apply deletion and expiry to primary data, derived data, caches, indexes, exports, analytics, backups, and realtime state according to documented policy.
- Encrypt sensitive data in transit and at rest using approved platform controls.
- Validate telemetry, analytics, crash reporting, and third-party providers against privacy requirements.

---

## 10. Required verification

CI must enforce applicable:

- types, linting, contracts, and builds;
- authentication and object-authorization tests;
- cross-user and blocked-user isolation tests;
- security and privacy regression tests;
- input-bound and abuse-limit tests;
- secret and dependency scanning;
- migration and rollback checks;
- multi-replica/realtime tests where relevant.

High-risk changes require manual adversarial review and, when justified, independent Astra-high review. Automated scanners are supporting evidence, not the security decision-maker.

No security or privacy control may be silently weakened to make a test pass, simplify implementation, or meet a deadline.

