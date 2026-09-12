# Private trip journal threat review

## Scope and boundaries

The current slice stores private title/notes for an owned completed trip. The browser crosses the same-origin proxy, authenticated core API and PostgreSQL boundary. A separate account-keyed IndexedDB partition retains device drafts and confirmed snapshots. No journal sharing, media, AI, precise location or provider request is introduced.

Protected assets are journal content, ownership, mutation identity, versions and unsent writing. Attackers include unauthenticated callers, malicious authenticated users, stale tabs and compromised browser storage. An attacker controlling same-origin script or the local browser profile can read local content; account partitioning is not encryption or protection from a compromised device.

## Threats and evidence

| Threat | Control and verification |
|---|---|
| T01/T02 identity and object access | Cookie session and matching account header; service checks journey ownership and completed-trip eligibility. HTTP and service negative tests cover absent/wrong identity and ineligible objects. Account changes clear private UI before fallible storage reads. |
| T10/T11 unsafe content and request forgery | Bounded plain text, control/surrogate validation, React text rendering, parameterized SQL; origin and CSRF checks on writes. No authored HTML execution or outbound URL fetching. |
| T12 leakage | No-store responses, diagnostic record redaction, account-keyed local data, no planning-backup inclusion. No journal analytics or third-party transport. |
| T13/T20 concurrent/reordered writes | PostgreSQL transaction and version comparison arbitrate writers; exact latest mutation retries return the original result. IndexedDB validates local mutation identity, monotonic cache versions and exact acknowledgement. Explicit discard checks both the reviewed journal and current draft in one transaction. |
| T14 resource exhaustion | Input and streaming bounds, 20 writes per account/minute, bounded local capacity and database transaction/open timeouts. No automatic journal retry loop. |
| T19 deletion resurrection | Account/journey foreign-key cascades; independent local retirement with opaque markers; late writes/cache/ack/discard reject retired accounts. Other offline devices can retain local copies as disclosed in privacy copy. |

Likelihood is greatest for ordinary concurrency and interrupted connections; impact is private-content disclosure or loss of unsent writing for affected accounts. Cross-account authorization bypass would be High and block completion. Storage and HTTP tests use synthetic data; these results do not establish real OAuth or device security verification.

## Residual risk and disposition

Root implementation/review owns follow-up. Source review found no backend authorization/CAS blocker. Prior frontend account-switch leakage and stranded-draft findings have regression tests. Ordinary Back navigation has component coverage, but arbitrary multi-entry history jumps and rendered authenticated browser behavior remain unverified. This is an open Medium release issue: keep the feature in local preview and complete real browser navigation/accessibility QA before release. Tests alone do not establish navigation safety.

Live Google configuration, production encryption/backup deletion controls and native device behavior are outside this local verification. Reassess this review before sharing/export, media, AI, native persistence, automatic sync, retention changes, new providers or new account recovery flows. No production deployment is approved by this document.
