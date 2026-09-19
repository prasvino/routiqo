# Current implementation plan

## Next priority: Routiqo Live first release

Current browser slice (2026-09-19): private active-journey consent controls under
`BROWSER_LIVE_CONSENT_UI_SPEC.md`. Explicit checks and one-shot allow/stop use the
existing clients; uncertain writes remain uncertain until explicit stop succeeds.
No automatic requests, browser persistence, public publication or server feature
activation. Current verification evidence belongs in BUILD_STATUS.md. Route
binding and Quick Signal controls remain the next interface prerequisites.

Latest safety work (2026-09-19): ADR 0041 adds internal action-specific operator
permissions, exact revision checks, transactional audit and deletion-resistant
operator action debits. The unaudited production mutation service is removed;
signal ingestion consumes read-only restriction state. Focused PostgreSQL tests
and independent review pass; final integration evidence is in BUILD_STATUS.md.
No grant is seeded and no administrative or public surface is enabled.

The follow-on moderation maintenance slice reuses its audit/debit cleanup adapter
behind an independent default-off flag and dedicated scheduler. Authentication
maintenance owns its scheduler irrespective of cleanup flags. No retention horizon
or database schema changes; staging activation and operations remain pending.

Latest supporting reliability work (2026-09-19): browser auth, private LIVE,
journal and journey transports enforce bounded streamed responses and whole-
operation deadlines. Journal/journey mutations capture their exact inputs before
CSRF and retain existing draft/outbox acknowledgement rules. Integrated validation
passed 308 TypeScript tests, web production build and repository checks; current
evidence and release gates are in BUILD_STATUS.md. No public LIVE UI is enabled.

### Execution queue

| Priority | Next concrete task | Completion evidence |
|---|---|---|
| P0 / L0.1 — complete | Immutable Quick Signal category/value and evidence lifecycle, contribution slot and replay matching primitives | 7 focused tests; full 141 Java tests, architecture/check/bootJar and independent review passed; no public output |
| P0 / L0.2a — complete | ADR 0023 and internal route-context/admission policy | 9 focused tests; full 150 Java tests/check/bootJar and review passed; no public issuer |
| P0 / L0.2b — next | Cohort publication and block-safe suppression design | Fixed partitions/windows, independent evidence, query limits and adversarial acceptance before projections |
| P0 / L0.3 | Resolve storage/retention ADR and idempotent replacement/withdrawal lifecycle | Transaction and cleanup design, stale retry/delete/consent race tests |
| P0 / L0.3a — complete | Internal context-linked receipt state, terminal withdrawal/supersession and exact replay comparison | 8 focused tests; full 158 Java tests/check/bootJar and review passed; no durable store or publication permission |
| P0 / L0.3b — internal command policy and storage complete | Admission-bound grant consumption and retained retry decisions; ADR 0024 remains the storage contract | 15 pure-policy tests plus ADR 0028 transactional persistence; public issuance/ingestion remains closed |
| P0 / L0.3c — account/journey transaction boundary complete | ADR 0025 domain-owned write authority, account-before-journey locks integrated with start/completion, redacted retryable failures | Full 184 Java tests/check/bootJar; 34 targeted database/HTTP tests, independent review; future Live persistence still pending |
| P0 / L0.3d — durable consent and internal intent ordering complete | ADRs 0026/0029 privacy-owned latest state, legacy CAS compatibility, revocation-precedence explicit intents and saturating terminal revocation | 45 focused domain/PostgreSQL/composition tests and independent review passed; full verification recorded in build status; no HTTP, leases, cache publication or automatic enable retry |
| P0 / L0.3e — durable route context complete, provider validation pending | ADR 0027 Route Update-owned latest context, post-lock time checks, exact-ID CAS, completion deletion and bounded leaf cleanup | 20 focused domain/PostgreSQL lifecycle/race/cleanup tests; full 218 Java tests/check/bootJar and independent review passed; no HTTP, provider anchor validation, scheduler or public output |
| P0 / L0.3f — internal signal storage complete, public ingestion pending | ADR 0028 durable grants/receipts, partial-unique contribution slot, atomic acceptance/withdrawal, database budgets and bounded cleanup | 16 focused PostgreSQL transaction/race/cleanup tests; full 234 Java tests/check/bootJar and independent review passed; no HTTP, provider anchor validation, moderation, scheduler or public projection |
| P0 / L0.3g — private browser consent transport complete, default off | ADR 0030 owner-only GET/explicit-intent POST with string generations, durable peer/account budgets and exact proxy allowlist | Real HTTP/PostgreSQL, default-deny, strict-input and failure tests; no UI, automatic enable retry, signal ingestion or public output |
| P0 / L0.3h — provider-backed anchor resolution complete, default off | ADR 0031 strict curated catalog and fresh region-guarded Valhalla vertex matching with endpoint exclusion | 23 focused domain/loader/config/provider tests and full 273 Java tests passed; no journey binding, provider quality claim, HTTP, geometry persistence or public output |
| P0 / L0.3i — internal route binding complete, default off | ADR 0032 two short authority transactions around fresh resolution, durable newest-attempt fencing and atomic context replacement | Focused PostgreSQL/domain/race tests and full backend verification; no HTTP, physical-presence claim, public grant issuer or Live output |
| P0 / L0.3j — catalog-aware signal authority complete, default off | ADR 0033 catalog-provenance contexts and configured facade deriving issuance permissions and rechecking new acceptance | Focused PostgreSQL/configuration/architecture tests; no public signal transport, catalog activation, moderation or Live output |
| P0 / L0.3k — private browser route-context transport complete, default off | ADR 0034 owner-only read/bind recovery using the real configured binder, exact proxy and minimal private DTOs | Real HTTP/PostgreSQL/Valhalla, strict-input, race, quota and default-deny tests; no signal transport, UI or public Live output |
| P0 / L0.3l — private browser signal command transport complete, default off | ADR 0035 owner-only issue/accept/withdraw through the catalog-aware facade, exact proxy and minimal private DTOs | Real HTTP/PostgreSQL/catalog/binding, replay/withdrawal, race, strict-input, quota and default-deny tests; no UI or public Live output |
| P0 / L0.3m — implemented, default off | Bounded context/grant/receipt expiry maintenance using existing domain adapters | 5 focused tests; full 328 Java tests/check/bootJar and independent review passed; production operation remains gated |
| P0 / L0.3n — verified | ADR 0037 actor rolling-hour and anchor/category cooldown budgets, independent bounded ledger and cleanup | Full core check/bootJar: 351 tests; database/race checks and independent review passed |
| P0 / L1.0 — verified | ADR 0038 private assessment/suspension, bilateral block and structured report-case primitives | Domain tests and review; no public output, durable block/report store or operator workflow |
| P0 / L1.1 — verified | ADR 0039 durable contribution restrictions with grant revision fencing | Mandatory current authority on new issuance/acceptance; retained private replay preserved; database/race validation and independent review passed |
| P0 / L1.2 — verified | ADR 0040 private durable directed blocks and ordered account-pair authority | 363 tests/50 suites, core check/bootJar and independent review passed; no public block API or output revocation |
| P0 / L1.3 — internal prerequisite verified | ADR 0041 scoped finite operator grants and atomic audited contribution restriction commands | 16 focused PostgreSQL tests, three value-object tests, architecture gates and independent review; full core check/bootJar: 386 tests/52 suites; strong admin authentication, grant administration and case workflows remain pending |
| P0 / L1.3 maintenance — verified, default off | Independent bounded moderation audit/debit expiry job; isolated auth/LIVE/moderation schedulers | 8 job/config tests including profile combinations and real scheduler thread selection; full core check/bootJar: 394 tests/54 suites; final strengthened job tests passed; staging operation pending |
| P1 / L2 prerequisite — verified | Typed private browser consent, route-context and signal clients with strict response validation, exact revisions and bounded cancellation-safe transport | 18 focused tests and independent review; full 291 TypeScript tests, web build/types/contracts pass; UI and public publication remain pending |
| P1 / L2 consent — verified | Explicit private active-journey consent controls, uncertain-write fencing and lifecycle cancellation | 32 focused React tests; full 329 TypeScript tests/43 files, web build and independent review; scoped visual/keyboard QA; real auth and public LIVE remain gated |
| P1 / L1 | Protected structured ingestion, consent authority, quota/receipt persistence and operator moderation | Authenticated HTTP and concurrent multi-replica tests before enablement |
| P1 / L2 | Privacy-reviewed moment projection and journey LIVE list with Quick Signals | Pilot data, real login and complete UI/privacy/offline acceptance |
| Supporting | Regional routing/search/tile provisioning and real OAuth/native readiness checks | Required pilot dependencies; retain existing journey/offline reliability |
| Later | Map Live overlay, Ask Ahead, rooms, Pulse, Travel Waves | Separate feature/privacy gates after first-list utility is validated |

The completed internal slices are specified in
`docs/features/live/QUICK_SIGNAL_DOMAIN_SPEC.md`, `SIGNAL_ADMISSION_SPEC.md`,
`QUICK_SIGNAL_RECEIPT_SPEC.md` and `SIGNAL_COMMAND_SPEC.md`.
Durable consent, route context and internal signal storage are specified in
`DURABLE_CONSENT_SPEC.md`, `DURABLE_ROUTE_CONTEXT_SPEC.md`,
`SIGNAL_STORAGE_SPEC.md`, `CONSENT_INTENT_SPEC.md`, `BROWSER_CONSENT_API_SPEC.md`,
`ROUTE_ANCHOR_RESOLUTION_SPEC.md`, `ROUTE_BINDING_SPEC.md`,
`CATALOG_SIGNAL_AUTHORITY_SPEC.md`, `BROWSER_ROUTE_BINDING_API_SPEC.md`,
`BROWSER_SIGNAL_API_SPEC.md`, `LIVE_EXPIRY_MAINTENANCE_SPEC.md`, and ADRs 0026–0036. The private consent,
route-context and signal-command transports and internal anchor
resolver are default off; these slices do not expose
public signal publication, confidence, moderation or a publicly visible
Live Moment. Complete and
verify this slice before promoting the next queue item; do not advance status for
future features based on their plans or primitive tests.

Server journey creation accepts only an ID and kind; validated route association
is established separately through ADR 0032 context binding. ADR 0032 binds a fresh
ADR 0031 result to an active owned journey,
current consent and exact context through a durable newest-attempt fence. This
internal path remains default off. ADR 0033 adds a configured internal signal
facade that derives and rechecks catalog categories. ADR 0034 adds the private
owner route-context read/bind transport. ADR 0035 adds only private owner command
issue/accept/withdraw transport and no UI or public output.
Existing client-selected places, route estimates or active journey ownership alone
are not admission authority.

Private browser clients now implement these owner transports under
`BROWSER_LIVE_CLIENT_SPEC.md`. The next integration step is an explicit,
account/journey-scoped route and contribution interaction with cancelled stale
requests, truthful uncertain-write recovery and offline submission disabled.
The private consent panel implements the first part of this boundary. Public list integration
still requires the publication and safety gates; importing the clients enables no
network work or feature flags. The existing browser authentication transport is
bounded under `../features/auth/BROWSER_AUTH_TRANSPORT_SPEC.md`.

For the next route-binding UI slice, capture the exact endpoint/mode/alternative
input associated with the displayed calculation rather than mutable form state.
Binding performs a fresh server calculation: an alternative index is not proof
that the same geometry is still selected. Disclose that distinction instead of
claiming an exact displayed-route attachment. Require explicit context recovery
before an exact-context bind, with no automatic refresh/retry of expectations.
A recovered context is private preparation, not evidence of physical presence or
public eligibility. Resolve useful owner-facing anchor labels before exposing
opaque anchor IDs as contribution choices; no synthetic catalog fallback.

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
