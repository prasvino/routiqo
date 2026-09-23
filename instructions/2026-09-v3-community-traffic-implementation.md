# V3 community traffic summary implementation

Status: staging implementation integrated and verified on 2026-09-23; production activation and acceptance of the changed privacy contract remain pending. [Evidence](../docs/validation/V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md) records tests, synthetic utility and exact external gaps. ADR 0055 and `COMMUNITY_TRAFFIC_SUMMARY_SPEC.md` define behavior. Preserve V18/V21/V22 and existing private Quick Signal flows without migrating consent or data into V3.

## Acceptance criteria

1. Separate versioned deliberate Share, exact owner recovery and future-sharing Stop work through authenticated browser transport behind a V3-only default-off production flag. One successful new candidate per account/window across anchors/values and at most 12 per UTC day are enforced in PostgreSQL. Retried commands do not debit again; unknown outcomes do not cause automatic alternate submission.
2. A bounded PostgreSQL publisher owns a version/anchor/window decision before obtaining its `REPEATABLE READ` input snapshot. Candidate and withdrawal/authority state are read from that same snapshot. Uncommitted and late source writes do not block, reopen or shift a terminal decision. A unique canonical key persists `NO_OUTPUT` as well as visible output, with 12/10/80% staging rule and strict expiry.
3. Authenticated active-journey readers get only relevant canonical coarse rows, without counts or contributor linkage, from a server-derived route context. Reports refer only to visible opaque moments; audited authorized moderation can suppress serving without rewriting the decision. Read/share/report rates and bounded cleanup are enforced across replicas.
4. Browser UI uses a fresh V3 purpose, truthful accepted-for-consideration and after-snapshot Stop copy, exact retry/reload recovery, provider distinction, active-journey list, report control, and loading/empty/offline/stale/expired/unavailable states. No V18/V22 copy or prior consent enrolls a user. Contracts and generated TypeScript models match the server.
5. Real PostgreSQL and HTTP security tests cover concurrency, authority order, duplicate/debit, snapshot cutoff, publisher contention, rollback/retry, expiry, relevance, guessed references, account isolation, report/suppression and cleanup. Browser interaction tests cover uncertain writes, Stop, reload and stale data. Run required sequential checks and inspect rendered UI. Produce synthetic staging utility results labeled as synthetic; real density and accuracy remain external gates.

## Work and ownership

- Backend submission/recovery owns V23 candidate/debit schema, application/store/transport and focused tests.
- Backend publisher/read/report/moderation owns V24+ canonical schema, selection job, read/report/moderation transport, cleanup and focused tests; coordinate V23 column contract first.
- Web/contracts owns OpenAPI, generated client types, same-origin proxy/client, active-journey controls/list and browser tests; coordinate exact endpoint/DTOs with backend.
- Root owns architecture/security review, integration, adversarial checks, documentation/ledger, utility simulation and final validation. Root will correct any cross-boundary failures with the implementers and not present staging as production approval.

## Rollout and rollback

V3 flags default off in production. Staging may enable only the reviewed V3 flags with an explicit regional catalog, OAuth/provider configuration and isolated accounts. No migration imports V18 intents or V22 claims. A rollback disables V3 reads, writes and jobs; committed terminal decisions and audit retain their approved lifetimes. Restoring database state must not resurrect expired/suppressed rows or redraw a terminal decision. Production activation needs the separate owner privacy-contract decision and required product/privacy/security review.
