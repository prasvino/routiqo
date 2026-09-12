# Testing strategy
Strict TypeScript + ESLint + Vitest for shared behavior; generated contract drift checks; Next production builds; Expo export/typecheck.
Java: domain tests, Spring HTTP/security integration tests, ArchUnit boundaries. Multi-instance tests are mandatory before realtime exposure.
Manual rendered UI checks complement automated checks. Native bundling does not prove Android device performance.
Foundation tests must exercise validation, persistence corruption/version handling, duplicate saved IDs, plan changes, lifecycle invariants and privacy suppression.


## Routiqo Live acceptance plan

Planned specification: `docs/features/live/ROUTIQO_LIVE_SPEC.md`. Tests are required
before exposure; this matrix is not a report of implemented or passing behavior.

| Layer | Required cases |
|---|---|
| Pure policies (L0) | Allowed categories/values, server-time boundaries, contribution replacement, duplicate identity, expiry, conflicting evidence, consent generation and no fabricated moments |
| Persistence/HTTP (L1) | Owner isolation, stale/fake admission, origin/CSRF/session guards, schema/body caps, concurrent idempotency, distributed quotas, bounded cleanup and unavailable-authority suppression |
| Privacy projection | Sparse cohorts, collusion, overlap/repeated probing, temporal differences, blocked/withdrawn evidence, opaque reference expiry and no membership/coordinate fields |
| Moderation/deletion | Report authorization, duplicate reports, restricted operator access, hide/reinstate rules, minimal evidence holds, account deletion and cache invalidation |
| Multi-replica | Ghost/submit/read races, reordered generation changes, block changes, cleanup races, stale cache and Redis outages |
| UI/offline (L2) | Cold start, insufficient evidence, coarse freshness, expiry while open, stale offline rows, no report replay, uncertain exact retries, account/end/Ghost clearing, keyboard/screen-reader/large-text checks |
| Safety/provenance | No crowd or report counts, no implied location verification, no lane advice, no safety inference from absent reports, no fabricated confidence and no driving prompts |

Use synthetic locations and users; no actual GPS or personal evidence in agent QA.
Review serialized API/cache/log projections for leaks. Real sign-in and reviewed
regional context are pilot gates. Later sockets need revocation/reconnect/replay
and multi-replica fanout tests; Ask Ahead needs anti-targeting and recipient-consent
tests. Those future tests are not prerequisites for implementing L0 pure policies.
