# Testing strategy
Strict TypeScript + ESLint + Vitest for shared behavior; generated contract drift checks; Next production builds; Expo export/typecheck.
Java: domain tests, Spring HTTP/security integration tests, ArchUnit boundaries. Multi-instance tests are mandatory before realtime exposure.
Manual rendered UI checks complement automated checks. Native bundling does not prove Android device performance.
Foundation tests must exercise validation, persistence corruption/version handling, duplicate saved IDs, plan changes, lifecycle invariants and privacy suppression.


## Journey, Spots and Ask Ahead acceptance plan

Product scope: [`../PRODUCT.md`](../PRODUCT.md). Tests are required before
exposure; this matrix is not a report of implemented or passing behavior.

| Layer | Required cases |
|---|---|
| Pure policies (L0) | Allowed signal categories/values, per-type lifetimes at server-time boundaries, "Still true?" extension and silence-expiry, highlight derivation, duplicate identity, conflicting reports and no fabricated posts |
| Persistence/HTTP (L1) | Owner isolation, origin/CSRF/session guards, schema/body and voice-note size/duration caps, concurrent idempotency, distributed rate limits (stricter for new accounts), bounded cleanup and 24-hour Spot-passage purge |
| Privacy | Per-room aliases unlinkable across rooms and never embedding account IDs; Ask Ahead anti-targeting (bounded, non-deterministic, no repeat targeting, blocks respected) and recipient anonymity towards the asker; Spot passage only after opt-in, coarse time, outcome-code logging; no membership, coordinate or traveller-count fields |
| Moderation/deletion | Report authorization, duplicate reports, hide/reinstate for text, voice and chat, business spam and false-alarm handling, Tamil/Tanglish abuse fixtures, author delete, account deletion and cache invalidation |
| Multi-replica | Ghost/submit/read races, block changes, room membership expiry, cleanup races, stale cache and Redis outages |
| UI/offline (L2) | Cold start, no recent posts, Ask Ahead unanswered, coarse freshness, expiry while open, stale offline rows; offline queue keeps capture time, server rejects items whose lifetime elapsed and shows accepted ones with capture time; uncertain exact retries; Ghost Mode stops Spot passage and clears queued posts; account/end clearing; keyboard/screen-reader/large-text checks |
| Safety/provenance | Report counts allowed, traveller counts forbidden; no implied location verification, no safety inference from absent reports, no fabricated confidence; post-passing prompt is a dismissible card only when stopped/slow or passenger, never a modal while moving |
| Device (phase gates) | Physical Android devices (3+ phones, including mid-range and small screen) for journey, Spot-passage detection, battery and weak-network behaviour; cloud sessions record these as pending |

Use synthetic locations and users; no actual GPS or personal evidence in agent QA.
Review serialized API/cache/log projections for leaks. Real sign-in and reviewed
corridor Spots are pilot gates. Sockets, if adopted, need revocation/reconnect/
replay and multi-replica fanout tests. Sparse-cohort, collusion and probing tests
for published aggregates are archived with the cohort design
([`../archive/README.md`](../archive/README.md)) and return only with aggregate
traveller counts.

## API contract checks

`pnpm contracts:check` validates response field names before comparing generated
TypeScript output. YAML flow descriptions containing commas must be quoted;
otherwise YAML can silently create extra response fields while type generation
still succeeds. The focused response-field check complements the pinned
openapi-typescript/Redocly checks, including unique operation IDs, and is not a
complete OpenAPI validator. Regression tests cover the malformed/quoted forms,
reusable responses and permitted extensions. Review the parser compatibility test
when upgrading openapi-typescript; no separate parser dependency is introduced.
