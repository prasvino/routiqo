# Routiqo Code Review Standard

Use this standard for every production change. Review is a correctness gate, not a style-only pass.

Detailed security invariants belong in `SECURITY.md`; abuse cases and attacker analysis belong in `THREAT_MODEL.md`. Apply both when the change touches a relevant boundary.

---

## 1. Reviewer responsibilities

The reviewer must:

- compare the implementation with the request and acceptance criteria;
- inspect the final diff, not only the implementation summary;
- identify unintended and unrelated changes;
- verify architecture, ADR, API, and domain-boundary consistency;
- inspect affected tests and actual validation results;
- challenge security, privacy, abuse, reliability, and failure assumptions;
- distinguish verified behavior from unverified behavior;
- request concrete corrections for material issues.

Do not approve based only on a successful build, automated scanner, worker report, or happy-path demonstration.

---

## 2. Required review areas

Review the areas affected by the change:

### Functional correctness

- acceptance criteria and edge cases;
- empty, loading, error, retry, stale, expired, and partial-success states;
- time zones, ordering, pagination, idempotency, and concurrency;
- offline/reconnect behavior where applicable;
- backwards compatibility and migration behavior.

### Contracts and data

- strict types and nullability;
- API request/response and OpenAPI drift;
- validation at trust boundaries;
- schema and migration safety;
- ownership, retention, expiry, and deletion;
- bounded collection sizes, payloads, queries, and uploads.

### Security and privacy

- authentication and object-level authorization;
- owner/tenant isolation;
- least privilege and secure defaults;
- secret, token, PII, and precise-location handling;
- injection, XSS, CSRF, SSRF, redirect, path traversal, and unsafe deserialization risks where relevant;
- rate limits, enumeration resistance, replay protection, and abuse controls;
- logging, analytics, error, cache, and notification leakage;
- dependency and supply-chain impact.

For social/location features, explicitly review stalking, harassment, unwanted discovery, presence enumeration, home/work inference, and malicious authenticated-user behavior.

### Reliability and operations

- timeout, retry, backoff, circuit-breaking, and duplicate-operation behavior;
- race conditions and multi-replica correctness;
- cache invalidation and stale-data behavior;
- observability without sensitive-data leakage;
- safe rollout, rollback, and feature-flag behavior.

### User experience and accessibility

- inspect rendered UI as well as source code;
- keyboard, focus, screen-reader, contrast, responsive, and reduced-motion behavior where applicable;
- understandable permissions, privacy states, errors, and recovery paths;
- performance on realistic devices, networks, and data sizes.

### Tests

- tests prove the changed behavior instead of mirroring implementation details;
- authorization and privacy tests include negative and cross-user cases;
- regression tests cover the defect or risk being changed;
- mocks do not bypass the boundary under review;
- validation is proportional to scope and risk.

---

## 3. Adversarial review

For security-, privacy-, identity-, moderation-, payment-, location-, presence-, realtime-, schema-, or distributed-systems changes, review from an attacker's perspective.

Ask:

- What can an unauthenticated attacker do?
- What can a normal but malicious authenticated user do?
- Can identifiers, timing, errors, counts, notifications, or realtime events reveal protected state?
- Can requests be replayed, reordered, raced, amplified, or made at scale?
- Can blocked, hidden, expired, or deleted data reappear through caches, search, exports, logs, or secondary channels?
- Can a compromised dependency, provider, account, token, or internal service cross an intended trust boundary?
- Does failure default to the safer state?

Scanner success does not replace manual adversarial review.

---

## 4. Finding severity

Classify findings consistently:

- **Critical:** exploitable compromise, broad sensitive-data exposure, authentication/authorization bypass, credible stalking/safety failure, destructive integrity loss, or release-blocking incident risk.
- **High:** material security/privacy violation, cross-user access, major data corruption, serious availability weakness, or architecture break with production impact.
- **Medium:** correctness, maintainability, reliability, accessibility, or defense-in-depth gap that should be fixed before merge unless explicitly accepted.
- **Low:** localized improvement with limited impact.

Critical and High findings block completion. Medium findings require correction or explicit root-agent disposition. Do not hide unresolved findings inside a general summary.

---

## 5. Independent review

Use an independent **GPT-6 Astra · high** review when justified by risk, as defined in `CODEX_ORCHESTRATION.md`.

Provide the reviewer:

- requirements and acceptance criteria;
- relevant architecture, security, privacy, and threat constraints;
- final diff;
- test and validation results.

Ask for concrete issues and missing tests without telling the reviewer that the implementation is believed to be correct.

---

## 6. Review output

Report:

1. findings ordered by severity, with file/location and impact;
2. questions or assumptions requiring resolution;
3. validation actually run and its result;
4. relevant validation not run and why;
5. residual risks;
6. final disposition: approve, approve with non-blocking notes, or request changes.

If there are no findings, state that directly but still report validation limits.

Never claim native-device, backend, provider, security, performance, or integration verification that was not actually performed.

