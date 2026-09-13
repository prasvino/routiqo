# Current implementation plan

## Next priority: Routiqo Live first release

### Execution queue

| Priority | Next concrete task | Completion evidence |
|---|---|---|
| P0 / L0.1 — complete | Immutable Quick Signal category/value and evidence lifecycle, contribution slot and replay matching primitives | 7 focused tests; full 141 Java tests, architecture/check/bootJar and independent review passed; no public output |
| P0 / L0.2a — complete | ADR 0023 and internal route-context/admission policy | 9 focused tests; full 150 Java tests/check/bootJar and review passed; no public issuer |
| P0 / L0.2b — next | Cohort publication and block-safe suppression design | Fixed partitions/windows, independent evidence, query limits and adversarial acceptance before projections |
| P0 / L0.3 | Resolve storage/retention ADR and idempotent replacement/withdrawal lifecycle | Transaction and cleanup design, stale retry/delete/consent race tests |
| P0 / L0.3a — complete | Internal context-linked receipt state, terminal withdrawal/supersession and exact replay comparison | 8 focused tests; full 158 Java tests/check/bootJar and review passed; no durable store or publication permission |
| P0 / L0.3b — internal command policy implemented; storage pending | Admission-bound grant consumption and retained retry decisions; ADR 0024 remains the storage contract | 15 focused tests; full 173 Java tests/check/bootJar and independent review passed; grant/receipt/slot migrations and database signal race/replay/purge tests remain |
| P0 / L0.3c — account/journey transaction boundary complete | ADR 0025 domain-owned write authority, account-before-journey locks integrated with start/completion, redacted retryable failures | Full 184 Java tests/check/bootJar; 34 targeted database/HTTP tests, independent review; future Live persistence still pending |
| P0 / L0.3d — durable consent complete, public command ordering pending | ADR 0026 privacy-owned latest consent row, journey-scoped CAS and atomic completion revocation | 14 focused disposable PostgreSQL ownership/CAS/race/rollback tests; full 198 Java tests/check/bootJar and independent review passed; no HTTP, leases, cache publication or claim that unchanged opt-out fences delayed enable |
| P0 / L0.3e — durable route context complete, provider validation pending | ADR 0027 Route Update-owned latest context, post-lock time checks, exact-ID CAS, completion deletion and bounded leaf cleanup | 20 focused domain/PostgreSQL lifecycle/race/cleanup tests; full 218 Java tests/check/bootJar and independent review passed; no HTTP, provider anchor validation, admission issuer, signal storage, scheduler or public output |
| P1 / L1 | Protected structured ingestion, consent authority, quota/receipt persistence and operator moderation | Authenticated HTTP and concurrent multi-replica tests before enablement |
| P1 / L2 | Privacy-reviewed moment projection and journey LIVE list with Quick Signals | Pilot data, real login and complete UI/privacy/offline acceptance |
| Supporting | Regional routing/search/tile provisioning and real OAuth/native readiness checks | Required pilot dependencies; retain existing journey/offline reliability |
| Later | Map Live overlay, Ask Ahead, rooms, Pulse, Travel Waves | Separate feature/privacy gates after first-list utility is validated |

The completed internal slices are specified in
`docs/features/live/QUICK_SIGNAL_DOMAIN_SPEC.md`, `SIGNAL_ADMISSION_SPEC.md`,
`QUICK_SIGNAL_RECEIPT_SPEC.md` and `SIGNAL_COMMAND_SPEC.md`.
Durable consent and route context are specified in `DURABLE_CONSENT_SPEC.md`,
`DURABLE_ROUTE_CONTEXT_SPEC.md`, ADR 0026 and ADR 0027. These
internal slices do not implement admission issuance, signal storage idempotency, confidence or a
publicly visible Live Moment. Complete and
verify this slice before promoting the next queue item; do not advance status for
future features based on their plans or primitive tests.

Current dependency discovered during execution: server journey creation accepts
only an ID and kind; stored journeys have no validated route/anchor association.
L0.2 must define a server-issued route-context binding and bounded anchor admission
before L1 ingestion. Existing client-selected places, route estimates or active
journey ownership alone are not admission authority. Preserve the current
memory-only route privacy policy unless that separate storage decision is reviewed.

Cohort publication decision checkpoint: `COHORT_PUBLICATION_DESIGN.md` in the Live
feature directory documents the remaining block/withdrawal/differencing issues.
Do not replace that review with a threshold-only publisher. Receipt lifecycle work
under `QUICK_SIGNAL_RECEIPT_SPEC.md` can proceed independently; public output stays
closed until the cohort ADR and adversarial acceptance are complete.

Planning update, 2026-09-12; no new functionality is claimed. Canonical behavior:
`docs/features/live/ROUTIQO_LIVE_SPEC.md`; architectural decision: ADR 0022.
Verified implementation remains recorded separately in `docs/quality/BUILD_STATUS.md`.

| Stage | Deliverable | Gate |
|---|---|---|
| L0 — next implementation | Pure Live Moment/Quick Signal lifecycle, enums, expiry, retry and evidence policy tests; reuse Route Updates and consent interfaces | No public data or mock auth; cohort/admission and retention design before API/migrations |
| L1 | Owned-journey admission, consent authority, bounded structured ingestion, quotas, idempotency, cleanup, blocking/reporting/operator moderation | Auth, multi-replica, abuse and deletion verification |
| L2 — first release | Active-journey LIVE list, moments, freshness/conflict states and deliberate Quick Signals | Reviewed regional context, real sign-in, privacy/anti-correlation and UI/offline acceptance |
| L3 | Map markers using the same authorized situation projection | No finer-grained privacy query surface |
| L4 | Ask Ahead | Cohort probing, recipient consent and repeat-target prevention design |
| L5+ | Temporary rooms, evidence-backed Pulse, Travel Waves | Moderation, realtime revocation, evidence quality and participation |

Continue enough regional mapping and journey work to support the pilot. Full
downloaded maps/on-device navigation, media, broad social features and AI do not
block the first LIVE list. Preserve four tabs and existing offline journey writes.
No crowd counts, arbitrary nearby search, free-text chat or background prompts in
the first release. ADR 0022 lists the decisions that remain gated; L0 can proceed
without pretending those decisions or infrastructure are complete.

## Historical foundation plan

The following records the initial scope, not the current implementation backlog.

Date: 2026-09-06. User authorized autonomous development, pausing only sections that require missing decisions/credentials.

## Acceptance criteria
- Pinned pnpm/Turbo TypeScript workspace and Java 25 Gradle multi-project build.
- Next.js web/admin and Expo mobile applications compile with strict TypeScript.
- Local PostGIS, Redis, S3-compatible services have health checks and loopback-only ports.
- Public catalog/status endpoints described in OpenAPI; generated client stays in sync.
- All unimplemented protected backend paths fail closed. No fake authentication or live presence.
- Useful discovery UI with search/category filtering, place details, saved destinations, and local journey plans.
- Local drafts survive reload, validate persisted data, and are clearly separate from live journeys.
- Mobile reuses domain helpers/tokens while keeping native UI/storage platform-specific.
- Backend lifecycle/privacy rules have meaningful tests even before exposed endpoints.
- Rendered desktop/mobile-width web QA, keyboard use and real interactions verified.
- CI runs relevant contracts, strict types, lint, tests, builds, Java and secret scanning.

## Visual thesis
Warm ivory, dark forest teal, editorial travel photography, and restrained route-inspired details. Discovery is inviting; planning remains legible and practical.

## Content plan
Home: destination prompt, journey CTA, one photographic route feature, curated discovery.
Explore: searchable/filterable destination collection.
Trips: durable local plans, clear empty state, edit/remove flow.
Profile: local preferences and privacy explanation; no pretend logged-in account.

## Interaction thesis
A focused journey-planning dialog, subtle image hover affordance, and short state transitions with reduced-motion support. No decorative map or fabricated traveller counts.

## Deferred sections
- Auth provider/session implementation: document secure model, do not create bypass.
- Maps migration: follow ADR 0021 (MapLibre/Valhalla/Photon/Martin); first generalize
  provider contracts and migrate web rendering/adapters, then verify regional data
  services and native downloads/navigation. Android development build/device and
  regional datasets are prerequisites; Mapbox credentials are not required.
- Live presence/rooms: anonymity thresholds, retention, trust and geographic admission need feature decisions and tests before exposure.
- Real Android/iOS validation: no device/emulator available yet.
- External AI, S3 production, push, cloud deployment: require provider configuration.

Offline draft persistence is implemented alongside planning; it is not postponed to a late phase. Local planning does not claim a live server journey.
