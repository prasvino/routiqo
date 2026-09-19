package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import com.routiqo.core.routeupdate.application.CatalogSignalService;
import com.routiqo.core.routeupdate.application.SignalCommandPolicy;
import com.routiqo.core.routeupdate.application.SignalStorageService;
import com.routiqo.core.routeupdate.application.SignalStorageDenied;
import com.routiqo.core.routeupdate.application.SignalStorageConflict;
import com.routiqo.core.routeupdate.application.LiveRouteContextService;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.RouteBindingOutcome;
import com.routiqo.core.routeupdate.domain.SignalIssuanceExpectation;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routing.domain.RouteRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_ROUTING_REGION_WEST=-1", "ROUTIQO_ROUTING_REGION_SOUTH=-1",
    "ROUTIQO_ROUTING_REGION_EAST=1", "ROUTIQO_ROUTING_REGION_NORTH=1",
    "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED=true"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "routing"})
class RouteBindingConfiguredTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer SERVER;
    private static final Path CATALOG;

    static {
        try {
            DATABASE.start();
            CATALOG = Files.createTempFile("routiqo-binding-catalog-", ".json");
            Files.writeString(CATALOG, """
                {"version":"00000000-0000-4000-8000-000000000201","anchors":[{
                  "id":"00000000-0000-4000-8000-000000000202",
                  "longitude":0,"latitude":0,"categories":["QUEUE"]}]}
                """, StandardCharsets.UTF_8);
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/route", exchange -> {
                CALLS.incrementAndGet();
                byte[] response = valhallaResponse().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            SERVER.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("ROUTIQO_AUTH_RATE_SECRET",
                () -> "configured-route-binding-test-secret-key");
        properties.add("ROUTIQO_VALHALLA_ORIGIN",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("ROUTIQO_PHOTON_ORIGIN",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        properties.add("ROUTIQO_LIVE_ANCHOR_CATALOG_PATH", CATALOG::toString);
    }

    @AfterAll
    static void stop() throws Exception {
        SERVER.stop(0);
        Files.deleteIfExists(CATALOG);
    }

    @Autowired ApplicationContext applicationContext;
    @Autowired RouteBindingService bindings;
    @Autowired CatalogSignalService signals;
    @Autowired SignalStorageService lowLevelSignals;
    @Autowired LiveRouteContextService contexts;
    @Autowired JourneyService journeys;
    @Autowired JourneyWriteAuthority journeyAuthority;
    @Autowired LiveRouteContextParticipant contextParticipant;
    @Autowired PresenceConsentService consents;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach void resetCalls() { CALLS.set(0); }

    @Test
    void productionProfilesComposeOneBinderAndUseConfiguredBoundedValhalla() {
        assertThat(applicationContext.getBeansOfType(RouteBindingService.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(CatalogSignalService.class)).hasSize(1);
        UUID actor = UUID.randomUUID();
        UUID journey = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, actor.toString());
        journeys.start(actor, journey, Journey.Kind.TRIP);
        consents.submitIntent(actor, journey, 0, true);

        RouteBindingOutcome outcome = bindings.bind(actor, journey,
                new RouteRequest(RouteRequest.Mode.DRIVING,
                        new RouteRequest.Coordinate(-0.02, 0),
                        new RouteRequest.Coordinate(0.02, 0)), 0, java.util.Optional.empty());

        assertThat(outcome.status()).isEqualTo(RouteBindingOutcome.Status.BOUND);
        assertThat(outcome.context().orElseThrow().context().anchorIds())
                .containsExactly(UUID.fromString("00000000-0000-4000-8000-000000000202"));
        assertThat(outcome.context().orElseThrow().catalogVersion())
                .contains(UUID.fromString("00000000-0000-4000-8000-000000000201"));
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        var grant = signals.issue(actor, journey, anchor);
        assertThat(grant.admission().permittedCategories())
                .containsExactly(QuickSignalValue.Category.QUEUE);
        var submission = new SignalCommandPolicy.SubmissionFingerprint(
                journey, anchor, QuickSignalValue.QUEUE_UNDER_5,
                grant.admission().consentGeneration(), grant.admission().contextId(),
                grant.admission().routeRevision());
        var receipt = signals.accept(actor, grant.commandId(), submission,
                Duration.ofMinutes(1), Duration.ofMinutes(2));
        assertThat(receipt.signal().value()).isEqualTo(QuickSignalValue.QUEUE_UNDER_5);
        assertThat(CALLS).hasValue(1);
    }

    @Test
    void exactContextIssuanceMatchesAllVersionsBeforeGrantAndBudgetMutation() {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        var bound = bind(actor, journey, java.util.Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        long generation = consents.read(actor, journey).generation();
        var expected = new SignalIssuanceExpectation(bound.context().contextId(),
                bound.context().revision(), generation);

        var grant = signals.issueExpectedContext(actor, journey, anchor, expected);

        assertThat(grant.admission().contextId()).isEqualTo(expected.contextId());
        assertThat(grant.admission().routeRevision()).isEqualTo(expected.routeRevision());
        assertThat(grant.admission().consentGeneration()).isEqualTo(expected.consentGeneration());
        assertThat(grant.admission().permittedCategories())
                .containsExactly(QuickSignalValue.Category.QUEUE);
        var exactSubmission = fingerprint(grant, QuickSignalValue.QUEUE_UNDER_5);
        var exactReceipt = signals.accept(actor, grant.commandId(), exactSubmission,
                Duration.ofMinutes(1), Duration.ofMinutes(2));
        assertThat(signals.accept(actor, grant.commandId(), exactSubmission,
                Duration.ofMinutes(1), Duration.ofMinutes(2))).isEqualTo(exactReceipt);
        assertThat(signals.withdraw(actor, journey, grant.commandId()).state())
                .isEqualTo(QuickSignalReceipt.State.WITHDRAWN);
        for (SignalIssuanceExpectation wrong : List.of(
                new SignalIssuanceExpectation(UUID.randomUUID(), expected.routeRevision(), generation),
                new SignalIssuanceExpectation(expected.contextId(), expected.routeRevision() + 1,
                        generation),
                new SignalIssuanceExpectation(expected.contextId(), expected.routeRevision(),
                        generation + 1))) {
            assertThatThrownBy(() -> signals.issueExpectedContext(actor, journey, anchor, wrong))
                    .isExactlyInstanceOf(SignalStorageDenied.class).hasNoCause();
        }
        assertThatThrownBy(() -> signals.issueExpectedContext(actor, journey, anchor, null))
                .isExactlyInstanceOf(SignalStorageDenied.class).hasNoCause();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM signal_command_grant WHERE actor_id = ?", Integer.class, actor))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT used_count FROM signal_actor_budget WHERE actor_id = ? AND action = 'GRANT'
                """, Integer.class, actor)).isEqualTo(1);
    }

    @Test
    void sameAnchorReplacementAndConsentCycleDenyThePreviouslyDisplayedTuple() {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        var first = bind(actor, journey, java.util.Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        long generation = consents.read(actor, journey).generation();
        var old = new SignalIssuanceExpectation(first.context().contextId(),
                first.context().revision(), generation);

        var replacement = bind(actor, journey, Optional.of(first.context().contextId()));
        assertThat(replacement.context().anchorIds()).contains(anchor);
        assertThatThrownBy(() -> signals.issueExpectedContext(actor, journey, anchor, old))
                .isExactlyInstanceOf(SignalStorageDenied.class).hasNoCause();

        var currentBeforeConsentCycle = new SignalIssuanceExpectation(
                replacement.context().contextId(), replacement.context().revision(), generation);
        var off = consents.submitIntent(actor, journey, generation, false);
        consents.submitIntent(actor, journey, off.generation(), true);
        assertThatThrownBy(() -> signals.issueExpectedContext(
                actor, journey, anchor, currentBeforeConsentCycle))
                .isExactlyInstanceOf(SignalStorageDenied.class).hasNoCause();
        assertNoGrantMutation(actor);
    }

    @Test
    void committedSameAnchorReplacementWinsTheAuthorityLockRaceBeforeExpectedIssuance()
            throws Exception {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        var first = bind(actor, journey, Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        UUID catalogVersion = UUID.fromString("00000000-0000-4000-8000-000000000201");
        long generation = consents.read(actor, journey).generation();
        var old = new SignalIssuanceExpectation(first.context().contextId(),
                first.context().revision(), generation);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.queryForObject("SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE",
                            UUID.class, actor);
                    locked.countDown();
                    await(release);
                    return null;
                });
                return null;
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var replacement = executor.submit(() -> journeyAuthority.withOwnedJourney(
                    actor, journey, owned -> contextParticipant.replaceBound(owned, Set.of(anchor),
                            Duration.ofMinutes(5), Optional.of(first.context().contextId()),
                            UUID.randomUUID(), catalogVersion)));
            assertThat(waitingAccountWriters(1)).isTrue();
            var issuance = executor.submit(
                    () -> signals.issueExpectedContext(actor, journey, anchor, old));
            assertThat(waitingAccountWriters(2)).isTrue();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            replacement.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> issuance.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SignalStorageDenied.class);
        } finally {
            release.countDown();
        }
        assertNoGrantMutation(actor);
    }

    @Test
    void issuanceExpectationValidatesExactLongValuesAndRedactsDiagnostics() {
        UUID context = UUID.randomUUID();
        long aboveJavascriptSafeInteger = 9_007_199_254_740_993L;
        var maximum = new SignalIssuanceExpectation(
                context, aboveJavascriptSafeInteger, Long.MAX_VALUE);
        assertThat(maximum.routeRevision()).isEqualTo(aboveJavascriptSafeInteger);
        assertThat(maximum.consentGeneration()).isEqualTo(Long.MAX_VALUE);
        assertThat(maximum.toString()).isEqualTo("SignalIssuanceExpectation[private]")
                .doesNotContain(context.toString(), Long.toString(aboveJavascriptSafeInteger),
                        Long.toString(Long.MAX_VALUE));
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable invalid
                : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> new SignalIssuanceExpectation(null, 0, 0),
                () -> new SignalIssuanceExpectation(new UUID(0, 0), 0, 0),
                () -> new SignalIssuanceExpectation(context, -1, 0),
                () -> new SignalIssuanceExpectation(context, 0, -1))) {
            assertThatThrownBy(invalid).isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid signal issuance expectation").hasNoCause();
        }
    }

    @Test
    void missingChangedOrRemovedCatalogProvenanceDeniesBeforeGrantOrBudgetMutation() {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        contexts.replace(actor, journey, java.util.Set.of(anchor), Duration.ofMinutes(5),
                java.util.Optional.empty());
        assertThatThrownBy(() -> signals.issue(actor, journey, anchor))
                .isInstanceOf(SignalStorageDenied.class).hasNoCause();

        bind(actor, journey, java.util.Optional.of(
                contexts.read(actor, journey).orElseThrow().context().contextId()));
        var changedVersion = new CatalogSignalService(lowLevelSignals,
                catalog(UUID.randomUUID(), anchor, QuickSignalValue.Category.QUEUE));
        assertThatThrownBy(() -> changedVersion.issue(actor, journey, anchor))
                .isInstanceOf(SignalStorageDenied.class).hasNoCause();
        var removed = new CatalogSignalService(lowLevelSignals,
                catalog(UUID.fromString("00000000-0000-4000-8000-000000000201"),
                        UUID.randomUUID(), QuickSignalValue.Category.QUEUE));
        assertThatThrownBy(() -> removed.issue(actor, journey, anchor))
                .isInstanceOf(SignalStorageDenied.class).hasNoCause();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM signal_command_grant WHERE actor_id = ?", Integer.class, actor))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM signal_actor_budget WHERE actor_id = ?", Integer.class, actor))
                .isZero();
    }

    @Test
    void newAcceptanceRechecksCurrentCatalogCategoryAndContextWithoutPartialMutation() {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        var bound = bind(actor, journey, java.util.Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        var lowLevelGrant = lowLevelSignals.issue(actor, journey, anchor,
                java.util.Set.of(QuickSignalValue.Category.TRAFFIC));
        var disallowed = fingerprint(lowLevelGrant, QuickSignalValue.TRAFFIC_SLOW);
        assertThatThrownBy(() -> signals.accept(actor, lowLevelGrant.commandId(), disallowed,
                Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(SignalStorageDenied.class).hasNoCause();
        assertUnchanged(actor, lowLevelGrant.commandId());

        var catalogGrant = signals.issue(actor, journey, anchor);
        contexts.replace(actor, journey, java.util.Set.of(anchor), Duration.ofMinutes(5),
                java.util.Optional.of(bound.context().contextId()));
        assertThatThrownBy(() -> signals.accept(actor, catalogGrant.commandId(),
                fingerprint(catalogGrant, QuickSignalValue.QUEUE_UNDER_5),
                Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(SignalStorageDenied.class).hasNoCause();
        assertUnchanged(actor, catalogGrant.commandId());
    }

    @Test
    void consentRevocationBeforeNewAcceptanceLeavesGrantBudgetSlotAndReceiptsUntouched() {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        bind(actor, journey, java.util.Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        var grant = signals.issue(actor, journey, anchor);
        consents.submitIntent(actor, journey, grant.admission().consentGeneration(), false);
        assertThatThrownBy(() -> signals.accept(actor, grant.commandId(),
                fingerprint(grant, QuickSignalValue.QUEUE_UNDER_5),
                Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(SignalStorageDenied.class).hasNoCause();
        assertUnchanged(actor, grant.commandId());
    }

    @Test
    void changedVersionRemovedAnchorAndChangedCategoryDenyAcceptanceWithoutSuperseding() {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        bind(actor, journey, java.util.Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        UUID version = UUID.fromString("00000000-0000-4000-8000-000000000201");
        var acceptedGrant = signals.issue(actor, journey, anchor);
        var accepted = signals.accept(actor, acceptedGrant.commandId(),
                fingerprint(acceptedGrant, QuickSignalValue.QUEUE_UNDER_5),
                Duration.ofMinutes(1), Duration.ofMinutes(2));

        var changedVersionGrant = signals.issue(actor, journey, anchor);
        var changedVersion = new CatalogSignalService(lowLevelSignals,
                catalog(UUID.randomUUID(), anchor, QuickSignalValue.Category.QUEUE));
        assertDeniedAcceptance(changedVersion, actor, changedVersionGrant,
                QuickSignalValue.QUEUE_OVER_30);

        var removedGrant = signals.issue(actor, journey, anchor);
        var removed = new CatalogSignalService(lowLevelSignals,
                catalog(version, UUID.randomUUID(), QuickSignalValue.Category.QUEUE));
        assertDeniedAcceptance(removed, actor, removedGrant, QuickSignalValue.QUEUE_OVER_30);

        var recategorizedGrant = signals.issue(actor, journey, anchor);
        var recategorized = new CatalogSignalService(lowLevelSignals,
                catalog(version, anchor, QuickSignalValue.Category.TRAFFIC));
        assertDeniedAcceptance(recategorized, actor, recategorizedGrant,
                QuickSignalValue.QUEUE_OVER_30);

        assertThat(jdbc.queryForObject("""
            SELECT state FROM quick_signal_receipt WHERE actor_id = ? AND command_id = ?
            """, String.class, actor, accepted.signal().signalId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM quick_signal_receipt WHERE actor_id = ?", Integer.class, actor))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT used_count FROM signal_actor_budget WHERE actor_id = ? AND action = 'ACCEPT'
            """, Integer.class, actor)).isEqualTo(1);
    }

    @Test
    void queuedConsentAndContextChangesWinBeforeCatalogAcceptanceWithoutPartialMutation()
            throws Exception {
        assertAuthorityChangeWins(true);
        assertAuthorityChangeWins(false);
    }

    @Test
    void retainedReplayPrecedesCatalogConsentAndCompletionButChangedRetryConflicts() {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        bind(actor, journey, java.util.Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        var grant = signals.issue(actor, journey, anchor);
        var exact = fingerprint(grant, QuickSignalValue.QUEUE_UNDER_5);
        var receipt = signals.accept(actor, grant.commandId(), exact,
                Duration.ofMinutes(1), Duration.ofMinutes(2));
        consents.submitIntent(actor, journey, grant.admission().consentGeneration(), false);
        journeys.complete(actor, journey);
        var removedCatalog = new CatalogSignalService(lowLevelSignals,
                catalog(UUID.fromString("00000000-0000-4000-8000-000000000201"),
                        UUID.randomUUID(), QuickSignalValue.Category.TRAFFIC));
        assertThat(removedCatalog.accept(actor, grant.commandId(), exact,
                Duration.ofMinutes(1), Duration.ofMinutes(2))).isEqualTo(receipt);
        var changed = new SignalCommandPolicy.SubmissionFingerprint(exact.journeyId(),
                exact.anchorId(), QuickSignalValue.QUEUE_OVER_30, exact.consentGeneration(),
                exact.contextId(), exact.routeRevision());
        assertThatThrownBy(() -> removedCatalog.accept(actor, grant.commandId(), changed,
                Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(SignalStorageConflict.class).hasNoCause();
    }

    private UUID activeJourney(UUID actor) {
        UUID journey = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, actor.toString());
        journeys.start(actor, journey, Journey.Kind.TRIP);
        consents.submitIntent(actor, journey, 0, true);
        return journey;
    }

    private com.routiqo.core.routeupdate.domain.StoredLiveRouteContext bind(
            UUID actor, UUID journey, java.util.Optional<UUID> expected) {
        return bindings.bind(actor, journey,
                new RouteRequest(RouteRequest.Mode.DRIVING,
                        new RouteRequest.Coordinate(-0.02, 0),
                        new RouteRequest.Coordinate(0.02, 0)), 0, expected)
                .context().orElseThrow();
    }

    private static RouteAnchorCatalog catalog(UUID version, UUID anchor,
            QuickSignalValue.Category category) {
        return new RouteAnchorCatalog(version, List.of(new RouteAnchor(anchor,
                new RouteRequest.Coordinate(0, 0), java.util.Set.of(category))));
    }

    private static SignalCommandPolicy.SubmissionFingerprint fingerprint(
            com.routiqo.core.routeupdate.domain.SignalCommandGrant grant,
            QuickSignalValue value) {
        var admission = grant.admission();
        return new SignalCommandPolicy.SubmissionFingerprint(admission.journeyId(),
                admission.anchorId(), value, admission.consentGeneration(),
                admission.contextId(), admission.routeRevision());
    }

    private void assertUnchanged(UUID actor, UUID command) {
        assertThat(jdbc.queryForObject("""
            SELECT state FROM signal_command_grant WHERE actor_id = ? AND command_id = ?
            """, String.class, actor, command)).isEqualTo("UNUSED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM quick_signal_receipt WHERE actor_id = ?", Integer.class, actor))
                .isZero();
        Integer accepts = jdbc.queryForObject("""
            SELECT count(*) FROM signal_actor_budget WHERE actor_id = ? AND action = 'ACCEPT'
            """, Integer.class, actor);
        assertThat(accepts).isZero();
    }

    private void assertNoGrantMutation(UUID actor) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM signal_command_grant WHERE actor_id = ?", Integer.class, actor))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM signal_actor_budget
                WHERE actor_id = ? AND action = 'GRANT'
                """, Integer.class, actor)).isZero();
    }

    private void assertDeniedAcceptance(CatalogSignalService selected, UUID actor,
            com.routiqo.core.routeupdate.domain.SignalCommandGrant grant,
            QuickSignalValue value) {
        assertThatThrownBy(() -> selected.accept(actor, grant.commandId(), fingerprint(grant, value),
                Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(SignalStorageDenied.class).hasNoCause();
        assertThat(jdbc.queryForObject("""
            SELECT state FROM signal_command_grant WHERE actor_id = ? AND command_id = ?
            """, String.class, actor, grant.commandId())).isEqualTo("UNUSED");
    }

    private void assertAuthorityChangeWins(boolean consentChange) throws Exception {
        UUID actor = UUID.randomUUID();
        UUID journey = activeJourney(actor);
        var bound = bind(actor, journey, java.util.Optional.empty());
        UUID anchor = UUID.fromString("00000000-0000-4000-8000-000000000202");
        var grant = signals.issue(actor, journey, anchor);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.queryForObject("SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE",
                            UUID.class, actor);
                    locked.countDown();
                    await(release);
                    return null;
                });
                return null;
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var change = executor.submit(() -> {
                if (consentChange) {
                    consents.submitIntent(actor, journey,
                            grant.admission().consentGeneration(), false);
                } else {
                    contexts.replace(actor, journey, java.util.Set.of(anchor),
                            Duration.ofMinutes(5), java.util.Optional.of(
                                    bound.context().contextId()));
                }
                return null;
            });
            assertThat(waitingAccountWriters(1)).isTrue();
            var acceptance = executor.submit(() -> signals.accept(actor, grant.commandId(),
                    fingerprint(grant, QuickSignalValue.QUEUE_UNDER_5),
                    Duration.ofMinutes(1), Duration.ofMinutes(2)));
            assertThat(waitingAccountWriters(2)).isTrue();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            change.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> acceptance.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SignalStorageDenied.class);
        } finally {
            release.countDown();
        }
        assertUnchanged(actor, grant.commandId());
    }

    private boolean waitingAccountWriters(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity activity
                WHERE activity.datname = current_database()
                  AND cardinality(pg_blocking_pids(activity.pid)) > 0
                  AND activity.query LIKE '%routiqo_account%FOR UPDATE%'
                """, Integer.class);
            if (waiting != null && waiting >= expected) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted");
        }
    }

    private static String valhallaResponse() {
        String shape = encode(List.of(new double[] {-0.02, 0}, new double[] {0, 0},
                new double[] {0.02, 0}));
        return "{\"trip\":{\"locations\":[{\"type\":\"break\"},{\"type\":\"break\"}],"
                + "\"legs\":[{\"maneuvers\":[{\"instruction\":\"Continue\",\"length\":4.4,"
                + "\"time\":60,\"begin_shape_index\":0,\"end_shape_index\":2}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"shape\":\"" + shape + "\"}],"
                + "\"summary\":{\"length\":4.4,\"time\":60},\"status\":0,"
                + "\"units\":\"kilometers\",\"language\":\"en-US\"}}";
    }

    private static String encode(List<double[]> coordinates) {
        StringBuilder encoded = new StringBuilder();
        long previousLatitude = 0;
        long previousLongitude = 0;
        for (double[] coordinate : coordinates) {
            long longitude = Math.round(coordinate[0] * 1_000_000);
            long latitude = Math.round(coordinate[1] * 1_000_000);
            encodeValue(encoded, latitude - previousLatitude);
            encodeValue(encoded, longitude - previousLongitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }
        return encoded.toString();
    }

    private static void encodeValue(StringBuilder encoded, long signed) {
        long value = signed < 0 ? ~(signed << 1) : signed << 1;
        while (value >= 0x20) {
            encoded.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        encoded.append((char) (value + 63));
    }
}
