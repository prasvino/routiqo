# Routiqo TODO

## First release — privacy and publication

Detailed traveller-derived public LIVE release ledger: [`docs/validation/PUBLIC_LIVE_PENDING.md`](docs/validation/PUBLIC_LIVE_PENDING.md). Keep validation evidence there as each gate closes.

The [V3 real staging trial handoff](docs/validation/V3_STAGING_TRIAL_PENDING.md) separates remaining engineering work from OAuth, regional-data, operator, pilot and review dependencies. The V3 moderator workflow and controlled operator grants under [ADR 0056](docs/adr/0056-v3-moderator-staging-boundary.md) and [ADR 0057](docs/adr/0057-v3-operator-grant-administration.md) are implemented behind disabled flags; real staging validation and production approval remain open.

ADR 0055's community traffic summary is authorized for V3 implementation and staging evaluation behind a disabled production flag. Its different privacy contract is not accepted for production; prior person-level research is paused and preserved, and no traveller publication is enabled in production.

- [x] Implement private dual-review verified-person authority and an explicit per-receipt public-purpose intent ledger; neither is a public LIVE release.
- [x] Add default-off purpose-specific share/Stop transport, account-wide retained Stop recovery, private candidate evaluation and bounded cleanup foundations; no traveller moment is public.
- [ ] Independently approve a complete transcript-level privacy protocol before traveller output. ADR 0054 now proposes irreversible explicit Share as the input boundary, avoiding the rejected ADR 0051/0052 revocation and delayed-cutoff rules; product consent, deletion retention, implementation timing and whole-transcript review are still open.
- [x] Authorize end-to-end V3 community-summary implementation and staging evaluation behind a disabled production flag; pause and preserve the person-level research code/docs.
- [x] Implement V3 candidate/debit, Share/Stop/recovery, snapshot publisher, canonical reader/report, internal audited suppression, cleanup, contracts and active-journey web controls. [Staging evidence](docs/validation/V3_STAGING_IMPLEMENTATION_EVIDENCE_2026-09-23.md) records PostgreSQL, browser-fixture and full build checks; production flags remain off.
- [x] Implement a separately flagged V3 moderator staging workflow: independent admin authentication, finite permission checks, bounded queue, audited dismissal/suppression, cleanup, generated contracts and UI. [Runbook](docs/development/V3_MODERATOR_STAGING_RUNBOOK.md) records the real trial prerequisites.
- [x] Implement separately flagged V3 operator grant administration: exact-account finite issue/revoke, current grant-administrator authority, minimized audit, cleanup, contracts and admin UI. The [grant runbook](docs/development/V3_OPERATOR_GRANTS_STAGING_RUNBOOK.md) keeps root provisioning and real operation pending.
- [ ] Finish authenticated regional staging, real moderator OAuth/MFA and named finite-grant operation, density/accuracy and output-age measurements, restore/failover/device QA and retention/backup operations before considering V3 production activation.
- [ ] Decide explicitly whether to accept ADR 0055's different privacy contract for production. Review residual participation inference, publication-snapshot withdrawal, audited suppression, block semantics, retention, real density/accuracy and exact user disclosure before activation. The proposed 12/10/80% rule is not a privacy guarantee; V18/V22 data cannot supply V3 consent.
- [x] Choose the policy after ADR 0052's rejected immutable-window follow-up: user selected a measurable person-level guarantee. ADR 0053 is a proposed whole-pilot DP design; its isolated sampler and disconnected V21 person claim need full protocol integration and review. No public reader or flag is authorized.
- [ ] Build independently authenticated operator case/grant provisioning, document-review operations, maintenance scheduling and retention review for verified contributors.

- [x] Implement the separate default-off Chennai district official-alert pilot: bounded NDMA CAP reader, owner-only active-journey API, browser list and explicit source/freshness states. This does not satisfy the traveller publication items below.
- [ ] User-led staging validation of the official-alert pilot is deferred; follow the checklist and record results in `docs/features/live/PROVIDER_LIVE_PILOT_SPEC.md` before activation.

- [ ] Define and independently review a measurable public LIVE privacy contract, including collusion assumptions and permitted inference.
- [ ] Specify fixed geographic partitions, publication windows, evidence independence, contribution eligibility and distributed query budgets.
- [ ] Resolve block, withdrawal, Ghost Mode, journey completion and account deletion without exposing individual contributions.
- [ ] Record the approved publication protocol in an ADR and test coordinated accounts, repeated/adjacent queries, threshold changes, stale caches and consent races.
- [ ] Implement the approved aggregation/projection, suppression, conflict and freshness rules.
- [ ] Implement authenticated Live Moment reads with current authorization and revocation checks.

## First release — reporting, moderation and abuse prevention

- [x] Implement the V3-only default-off moderator queue and canonical report review/suppression workflow under ADR 0056; real operator trial remains pending.
- [x] Implement the V3-only default-off controlled grant console under ADR 0057; out-of-band root bootstrap, supervision and real operator trial remain pending.
- [ ] Define and implement evidence eligibility and anti-Sybil safeguards.
- [ ] Resolve canonical reporting evidence identity, reference authorization, exact retries after revocation and shared transaction lock order.
- [ ] Define what moderators can investigate after source evidence expires, with explicit bounded retention.
- [ ] Implement durable report intake after its authority and investigation contracts are approved.
- [ ] Add strong administrative authentication, controlled grant administration, case/target scope and the operator queue around the internal audited restriction boundary.
- [ ] Establish operator alerts, supervision, appeals and approved audit/backup retention; activate and verify bounded moderation cleanup in staging.
- [ ] Connect private blocking to authorized public targets and safe delivery.
- [ ] Propagate revocation across reads, derived data, caches and future delivery channels; deny access when authority is unavailable.
- [ ] Verify multi-user, concurrent, replay, abuse and revocation scenarios and document the operator reporting workflow.

## First release — LIVE interface

- [ ] Verify the private consent, route-preparation and Quick Signal controls through the real authenticated lifecycle with a reviewed catalog and provider configuration.
- [ ] Build the active-journey LIVE list after publication and safety gates pass.
- [ ] Add bounded foreground refresh with cancellation, backoff and one request in flight.
- [ ] Provide loading, empty, error, suppressed, conflicting, stale and offline states with truthful source/freshness information.
- [ ] Keep offline LIVE rows in memory, expire stale rows and disable offline submission.
- [ ] Clear LIVE state on Ghost Mode, account changes and journey completion; reauthorize on reconnect.
- [ ] Handle uncertain writes through exact explicit retries without automatically publishing old observations.
- [ ] Verify authenticated lifecycle, account switching, expiry, revocation, accessibility and small-screen behavior.

## First release — Google sign-in configuration

- [ ] Configure the Google project, OAuth client/audience, consent screen and approved origins/redirects with account-owner access.
- [ ] Supply required secrets through local/deployment secret configuration.
- [ ] Verify real sign-in, renewal, logout, expired sessions and recent-auth account deletion through the staging browser/proxy boundary.

## First release — regional maps and catalog

- [ ] Select the pilot region/corridor and review dataset coverage, provenance, licensing, updates, storage and import requirements.
- [ ] Provision controlled Valhalla, Photon and tile hosting, including MapLibre styles, sprites and glyphs.
- [ ] Configure fixed service origins, geographic bounds, internal-service protection and redacted provider/proxy logs.
- [ ] Build and review a versioned LIVE anchor/category catalog and endpoint-exclusion rules.
- [ ] Validate real routing, alternatives, search, attribution, coverage failures, outages, egress behavior and regional updates.

## First release — journey reliability and quality

- [ ] Complete broader cross-device restoration and unresolved-conflict recovery without overwriting pending local work.
- [x] Let travellers explicitly discard a server-refused journey start/finish after a fresh server check shows it cannot apply (ADR 0063, web). See `docs/features/journey/OUTBOX_CONFLICT_RESOLUTION_SPEC.md`; native Android controls and real two-device sign-in QA remain.
- [x] Implement the explicit, default-off account planning copy (ADR 0062): owner-only save/check/add/remove of plans and saved places with CAS, exact retry and merge-only restore on web. See `docs/features/journey/ACCOUNT_PLANNING_BACKUP_SPEC.md`.
- [ ] Validate the account planning copy with real Google sign-in across two devices on HTTPS staging, then add native Android controls on the same contract.
- [ ] Test delayed responses, duplicate commands, network flaps, interrupted writes and unavailable storage across accounts/devices.
- [x] Cover web journey sync with deterministic scenario tests against the server's idempotency rules: lost and deadline-delayed responses, duplicate taps and competing tabs, a network flap with backoff and FIFO order, a delayed response across an account switch, and storage failing during acknowledgement (`tests/journey-network-resilience.test.ts`). Real-network, two-device and native runs remain.
- [ ] Verify journal conflicts, navigation protection, older history, summary completeness and backup/restore with real sign-in.
- [ ] Verify authenticated planning flows, keyboard/focus behavior, small screens, large text, reduced motion and storage-failure recovery.
- [x] Signed-out web planning pass (synthetic API): keyboard/focus, 320 px reflow, 360 px at 130% zoom and 200% text, reduced motion and storage-failure recovery; fixed focus loss after removing a plan, the unannounced journey-type group and raw storage exception copy. See `docs/quality/evidence/planning-accessibility-2026-09-25/`. Authenticated flows, screen readers and native remain.
- [ ] Confirm supported recovery flows preserve acknowledged work, account isolation and exact replay semantics.
- [x] Confirm the web recovery flows end to end across two simulated devices sharing one server (`tests/journey-recovery-flows.test.ts`): history restore beside unsent work, exact finish replay after another device finished, discard of a start refused because another device started a journey, reconcile of an applied action (discard refused), re-authentication without cross-account release, and account isolation and deletion on a shared device. Native and real-sign-in runs remain.

## Android release

- [x] Implement the bounded, redirect-safe Android authenticated transport and owner-scoped native journey API.
- [x] Implement Google challenge binding and sign-in UI, secure-vault coordination, session renewal and coordinated logout/deletion.
- [x] Mount authenticated journey resources, durable native reconnect dispatch and exact blocked-command recovery.
- [x] Integrate MapLibre as an explicit regional basemap preview in the Android development client; GPS and route geometry are separate future work.
- [x] Run `scripts/android-doctor.ps1` and resolve API 36, build-tools, command-line tools and JDK 17 prerequisites; API 36 emulator configured.
- [x] Finish the x86_64 Android debug build and unconfigured-service emulator smoke test; APK/hash, screenshots and results are in [native pending ledger](docs/validation/NATIVE_ANDROID_PENDING.md). Real OAuth, staging, map resources and physical-device validation remain open there.
- [ ] Supply the recommended staging Google OAuth registration, HTTPS API, regional map resources and test identities; complete the real authenticated trial.
- [ ] Verify SQLite/SecureStore restart, account isolation, backup behavior, location denial, accessibility, performance and unreliable networks on real devices.
- [ ] Produce a signed release candidate and complete device QA.

## First release — operations and deployment

- [ ] Configure production PostgreSQL/PostGIS, required caches, secrets, networking, domain/TLS, runtime profiles and feature gates with operator access.
- [ ] Validate migrations, backup restoration and rollback in staging, including the V13 acceptance-writer quiet-period requirement.
- [ ] Validate expiry maintenance throughput and backlog before activation; establish failure and capacity alerts for the intended replica count.
- [ ] Establish redacted monitoring, health checks, incident ownership and moderation procedures.
- [ ] Finalize privacy disclosures, deletion policies and backup retention.
- [ ] Verify remote CI on the actual release commit.
- [ ] Complete authenticated release QA, load/capacity checks and deployment smoke tests.
- [ ] Perform a gradual web pilot rollout after all first-release gates pass.

## Later navigation and product expansion

- [ ] Persist routes across reload with reviewed privacy, storage and deletion behavior.
- [ ] Implement location guidance, bounded offline-map downloads, update integrity, eviction and on-device rerouting; validate device/region performance.
- [ ] Add a map LIVE overlay using the same approved projection without finer-grained tracking.
- [ ] Design and implement Ask Ahead, temporary rooms and realtime delivery with recipient consent, anti-targeting, expiry, blocking and reconnect revocation.
- [ ] Implement reminders/push, media and AI workflows with permissions, bounded retention/jobs/uploads, fallbacks and feature-specific QA.
- [ ] Implement Pulse and Travel Waves after evidence quality and participation justify them.
- [ ] Expand journal media/sharing and administration workflows under separate reviewed specifications.

## Native account history

- [x] Implement fixed 20-row owner history, strict native transport/API contract, latest/earlier/retry controls and session-fenced in-memory pages.
- [ ] Complete real authenticated staging and physical-device checks in [native history ledger](docs/validation/NATIVE_HISTORY_PENDING.md).
