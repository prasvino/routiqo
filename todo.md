# Routiqo TODO

## First release — privacy and publication

- [ ] Define and independently review a measurable public LIVE privacy contract, including collusion assumptions and permitted inference.
- [ ] Specify fixed geographic partitions, publication windows, evidence independence, contribution eligibility and distributed query budgets.
- [ ] Resolve block, withdrawal, Ghost Mode, journey completion and account deletion without exposing individual contributions.
- [ ] Record the approved publication protocol in an ADR and test coordinated accounts, repeated/adjacent queries, threshold changes, stale caches and consent races.
- [ ] Implement the approved aggregation/projection, suppression, conflict and freshness rules.
- [ ] Implement authenticated Live Moment reads with current authorization and revocation checks.

## First release — reporting, moderation and abuse prevention

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

- [ ] Expose the internal route-area/category choice reader and expected-context issuance through reviewed default-off owner contracts and browser clients before Quick Signal controls; never fall back to legacy anchor-only issuance.
- [ ] Add expected-context issuance-specific consent-winning lock-race and grant-insertion rollback tests before browser exposure; retain existing shared transaction/replay regression coverage.
- [ ] Connect Quick Signal actions to the active owned journey; verify the implemented private consent and route-preparation controls with real authenticated lifecycle tests.
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
- [ ] Test delayed responses, duplicate commands, network flaps, interrupted writes and unavailable storage across accounts/devices.
- [ ] Verify journal conflicts, navigation protection, older history, summary completeness and backup/restore with real sign-in.
- [ ] Verify authenticated planning flows, keyboard/focus behavior, small screens, large text, reduced motion and storage-failure recovery.
- [ ] Confirm supported recovery flows preserve acknowledged work, account isolation and exact replay semantics.

## Android release

- [ ] Implement redirect-safe native authenticated transport.
- [ ] Implement Google challenge binding and sign-in UI, secure-vault coordination, session renewal and coordinated logout/deletion.
- [ ] Mount authenticated journey resources and native reconnect dispatch.
- [ ] Integrate MapLibre in a development build and verify platform permissions.
- [ ] Run `scripts/android-doctor.ps1` and resolve current SDK, build-tools, JDK, emulator/device prerequisites.
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
