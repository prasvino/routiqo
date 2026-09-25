# Routiqo Security Standard

Security, privacy, anti-abuse, and safety controls are correctness requirements. They must be designed, implemented, tested, reviewed, and operated as product behavior—not treated as optional hardening.

Use `THREAT_MODEL.md` for attacker analysis and abuse scenarios. Use `CODE_REVIEW.md` for review gates.

---

## 1. Non-negotiable invariants

- Protected HTTP, realtime, background-job, admin, and internal-service operations require authenticated and authorized access.
- Authorization is enforced server-side for every object and action; possession of an identifier is never sufficient.
- Ownership and tenant boundaries apply to reads, writes, search, exports, notifications, caches, and realtime events.
- Deny by default when identity, permission, privacy state, or policy evaluation is missing, stale, ambiguous, or fails.
- Contribution restrictions serialize with signal writes under current account
  authority, and stale grants are rejected after suspension/restoration
  ([ADR 0039](../adr/0039-durable-private-contribution-restrictions.md)).
  Restriction mutation is internal, scoped, exact-revision and atomically
  audited ([ADR 0041](../adr/0041-internal-audited-contribution-moderation.md));
  signal ingestion has read-only restriction access.
- Journey database authority takes account before journey locks in one
  synchronous transaction with no ambient transaction joining; database
  unavailability is distinct from authentication denial
  ([ADR 0025](../adr/0025-account-and-journey-write-authority.md)).
- Raw stranger GPS and unnecessary precise-location data are never exposed.
- Private LIVE code (consent, route context and binding, anchor resolution,
  catalog-aware signal authority, browser command APIs; ADRs
  [0026](../adr/0026-durable-journey-consent.md)–[0035](../adr/0035-default-off-browser-quick-signal-api.md),
  [0045](../adr/0045-private-browser-signal-choice-boundary.md)) stays
  default-off and its controls stay in force: exact session/account/origin/CSRF
  and body checks, durable budgets, post-provider authority rechecks, no raw
  context replacement from API packages, and no persisted endpoints or provider
  geometry. Route relevance, consent and receipts are never presence proof or
  publication permission; no public projection is approved
  ([ADR 0038](../adr/0038-publication-threat-boundary-and-safety-prerequisites.md)).
- Home/work endpoints and sensitive repeated-location patterns are protected from direct and inferred disclosure.
- The pilot keeps no server-side presence: no continuous location is collected and no traveller counts are published. Any future presence or aggregate count needs server-side privacy transformation and a separate privacy review before distribution.
- Discoverability is consent-based; Ghost Mode, block, visibility, expiry, and deletion rules apply across every delivery channel.
- Persisted private consent is necessary authority, not publication permission
  ([ADRs 0029](../adr/0029-explicit-consent-intent-ordering.md)/[0030](../adr/0030-default-off-browser-consent-api.md));
  the pilot's Spot-passage opt-in is separate and is not satisfied by it.
- Blocked, hidden, expired, or deleted information must not reappear through search, caches, exports, logs, notifications, analytics, or realtime replay.
- Tokens, credentials, secrets, and precise location are absent from fixtures, source control, logs, analytics, error payloads, and screenshots.
- Inputs, payloads, queries, fan-out, retries, subscriptions, uploads, and expensive operations are bounded.
- Browser auth and private LIVE clients use fixed same-origin paths, refuse
  redirects, bound streamed response bytes and cover the entire operation with a
  deadline, including internal CSRF acquisition. Cancel unused bodies without
  reading their error details. Client validation supplements server authority;
  it cannot grant publication permission.

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

High-risk changes require manual adversarial review and, when justified, a separate independent coding-agent review. The root coding agent resolves findings and owns final verification. Automated scanners are supporting evidence, not the security decision-maker.

No security or privacy control may be silently weakened to make a test pass, simplify implementation, or meet a deadline.

## Spots and Ask Ahead release gate

Scope is [`docs/PRODUCT.md`](../PRODUCT.md). Spot posts, voice notes, signals,
Spot chat, the festival route room and Ask Ahead ship default-off and must not
be exposed until each of these holds and is tested:

- report and quick moderator hide for posts, voice notes and chat items, with
  audited moderator actions and account restriction;
- rate limits on posts, voice uploads, signals, "Still true?", questions,
  answers and reports, by account and resource, with stricter limits for new
  accounts;
- server-time expiry by content type; offline items are accepted only within
  their lifetime since capture and never shown as new;
- per-room aliases that other users cannot link across rooms and that embed no
  account identifier; no private DMs;
- Ask Ahead anti-targeting: recipients only from opted-in recent passers,
  bounded non-deterministic selection, no repeated targeting of one person,
  blocks respected, and no recipient identity or count revealed to the asker;
- Spot passage behind one explicit opt-in, detected on device during an active
  journey, sent as answer plus coarse time only, deleted within 24 hours and
  logged only as outcome codes;
- Ghost Mode stops all sending, including Spot passage and queued posts, and
  takes priority over reconnect and outbox replay;
- voice notes via signed direct S3 uploads with size, duration and type limits,
  scanning or isolation before delivery, and deletion with their post;
- handling for business spam, fake reviews and false alarms ("Still true?",
  expiry, report thresholds for review, moderation).

Never trust client-supplied Spot, room or passage claims as authority. No
recipient/member enumeration, raw report exports or client-side filtering of
private state. Recheck all delivery paths, retries, caches and sockets for
block, Ghost Mode, hide and expiry. No collection of continuous live coordinates
or independent AI assertions. Keep freshness and source semantics honest;
expired or offline content must not be republished as new. Unknown authority
fails closed.

## Block authority (ADR 0040)

ADR 0040's internal block authority validates and locks both current enabled
accounts in PostgreSQL UUID order before inspecting any directed edge. Blocking
wins over stale unblocking; unblocking needs an exact revision. Missing authority,
wrong-account snapshots and storage failure cannot produce CLEAR. Every inserted
edge, including initial unblock, consumes the bounded outgoing capacity; retained
unblocked revisions cannot be deleted merely to free slots. API packages must not
access this trusted internal mutation/participant authority. Account existence is
not public target authorization; a pair snapshot is not a reusable delivery grant.

## Native routing endpoint rules

ADR 0060's native route planning uses separately default-off exact POST leaves,
native bearer/account guards and shared durable browser/native request budgets.
Endpoints and place queries are private transient inputs; do not log or persist
them in journey queues, analytics or diagnostic DTO strings. Native responses
retain strict byte/UTF-8/JSON/deadline bounds. Abort and session changes must fence
late dispatch/results. Routing remains independent of contribution consent and
cannot grant physical-presence authority, route membership or public discovery.

## Archived private command stopping and consent

The private LIVE command, consent and route-preparation boundaries remain in
code, default-off, and their rules still apply to that code: native private
consent keeps separate server/client exposure gates, bearer-only authority and
revocation precedence ([ADR 0059](../adr/0059-native-private-live-consent.md));
native route preparation uses owned read/bind authority, never raw context
replacement ([ADR 0061](../adr/0061-native-private-route-preparation.md));
terminal command stopping consumes an unused grant or withdraws a retained
receipt in one transaction, independent of current sharing or eligibility, and
never treats cancellation or a lost response as a confirmed stop
([ADRs 0046](../adr/0046-terminal-private-signal-command-stop.md)/[0047](../adr/0047-private-browser-command-stop-boundary.md)).
The earlier Routiqo Live implementation gate (LIVE list, admission, cohort and
publication prerequisites; [ADR 0022](../adr/0022-live-list-and-structured-evidence.md))
and the product flows these boundaries served are archived; see
[`docs/archive/`](../archive/README.md).
