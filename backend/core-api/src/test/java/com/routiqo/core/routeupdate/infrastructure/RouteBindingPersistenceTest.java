package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.identity.infrastructure.JdbcAuthRateGate;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.journey.infrastructure.JdbcJourneyStore;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.routiqo.core.privacy.infrastructure.JdbcPresenceConsentParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextService;
import com.routiqo.core.routeupdate.application.RouteAnchorResolver;
import com.routiqo.core.routeupdate.application.RouteBindingConflict;
import com.routiqo.core.routeupdate.application.RouteBindingRateLimited;
import com.routiqo.core.routeupdate.application.RouteBindingService;
import com.routiqo.core.routeupdate.application.RouteBindingUnavailable;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.RouteBindingOutcome;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class RouteBindingPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant START = Instant.parse("2026-09-13T08:00:00.123456789Z");
    private static final RouteRequest REQUEST = new RouteRequest(RouteRequest.Mode.DRIVING,
            new RouteRequest.Coordinate(0, 0), new RouteRequest.Coordinate(0.1, 0));
    private static final UUID ANCHOR = UUID.fromString("00000000-0000-4000-8000-000000000111");
    private static final UUID CATALOG = UUID.fromString("00000000-0000-4000-8000-000000000112");

    static { DATABASE.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcRouteBindingAttemptParticipant configuredAttempts;

    @Test
    void successfulBindingConsumesAttemptPersistsOnlyAnchorsAndUsesNoProviderTransaction() {
        MutableClock clock = new MutableClock(START);
        ControlledProvider provider = new ControlledProvider(request -> routeThroughAnchor());
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);

        RouteBindingOutcome outcome = services.bindings.bind(actor, journey, REQUEST, 0,
                Optional.empty());

        assertThat(outcome.status()).isEqualTo(RouteBindingOutcome.Status.BOUND);
        assertThat(outcome.context().orElseThrow().context().anchorIds()).containsExactly(ANCHOR);
        assertThat(outcome.context().orElseThrow().issuedAt()).isEqualTo(
                START.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(provider.calls).hasValue(1);
        assertThat(provider.observedTransaction).isFalse();
        assertThat(state(actor)).isEqualTo("CONSUMED");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM route_binding_attempt WHERE actor_id = ?",
                Integer.class, actor))
                .isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM live_route_context WHERE actor_id = ?",
                Integer.class, actor))
                .isEqualTo(1);
    }

    @Test
    void noRouteAndNoEligibleAnchorsConsumeWithoutChangingExistingContext() {
        UUID actor = account();
        MutableClock clock = new MutableClock(START);
        ControlledProvider noRoute = new ControlledProvider(request -> List.of());
        Services first = services(clock, noRoute, null);
        UUID journey = activeAndSharing(first, actor);
        RouteBindingOutcome absent = first.bindings.bind(actor, journey, REQUEST, 0, Optional.empty());
        assertThat(absent.status()).isEqualTo(RouteBindingOutcome.Status.NO_ROUTE);
        assertThat(absent.context()).isEmpty();
        assertThat(state(actor)).isEqualTo("CONSUMED");

        ControlledProvider noMatch = new ControlledProvider(request -> List.of(new RouteOption(1, 1,
                List.of(REQUEST.origin(), REQUEST.destination()))));
        Services second = services(clock, noMatch, null);
        RouteBindingOutcome unmatched = second.bindings.bind(actor, journey, REQUEST, 0, Optional.empty());
        assertThat(unmatched.status()).isEqualTo(RouteBindingOutcome.Status.NO_ELIGIBLE_ANCHORS);
        assertThat(unmatched.context()).isEmpty();
        assertThat(state(actor)).isEqualTo("CONSUMED");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM live_route_context WHERE actor_id = ?",
                Integer.class, actor))
                .isZero();
    }

    @Test
    void exactContextExpectationIsCheckedBeforeProviderAndEmptyOutcomePreservesContext() {
        MutableClock clock = new MutableClock(START);
        ControlledProvider provider = new ControlledProvider(request -> List.of());
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);
        var current = services.contexts.replace(actor, journey, Set.of(UUID.randomUUID()),
                java.time.Duration.ofMinutes(5), Optional.empty());

        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0,
                Optional.empty())).isInstanceOf(RouteBindingConflict.class).hasNoCause();
        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0,
                Optional.of(UUID.randomUUID()))).isInstanceOf(RouteBindingConflict.class).hasNoCause();
        assertThat(provider.calls).hasValue(0);

        RouteBindingOutcome outcome = services.bindings.bind(actor, journey, REQUEST, 0,
                Optional.of(current.context().contextId()));
        assertThat(outcome.status()).isEqualTo(RouteBindingOutcome.Status.NO_ROUTE);
        assertThat(services.contexts.read(actor, journey)).contains(current);
        assertThat(state(actor)).isEqualTo("CONSUMED");
    }

    @Test
    void invalidAuthorityConsentAndInputNeverCallProvider() {
        MutableClock clock = new MutableClock(START);
        ControlledProvider provider = new ControlledProvider(request -> routeThroughAnchor());
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = start(services, actor);

        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, -1, Optional.empty()))
                .isInstanceOf(RouteBindingConflict.class).hasNoCause();
        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0, Optional.empty()))
                .isInstanceOf(RouteBindingConflict.class).hasNoCause();
        assertThatThrownBy(() -> services.bindings.bind(UUID.randomUUID(), journey, REQUEST, 0,
                Optional.empty())).isInstanceOf(RuntimeException.class);
        jdbc().update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", actor);
        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0, Optional.empty()))
                .isInstanceOf(SecurityException.class);
        assertThat(provider.calls).hasValue(0);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM route_binding_attempt WHERE actor_id = ?",
                Integer.class, actor))
                .isZero();
    }

    @Test
    void providerFailureIsChargedAndNewestFailedAttemptFencesAnOlderResponse() throws Exception {
        MutableClock clock = new MutableClock(START);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        ControlledProvider provider = new ControlledProvider(request -> {
            if (sequence.getAndIncrement() == 0) {
                firstEntered.countDown();
                await(releaseFirst);
                return routeThroughAnchor();
            }
            throw new IllegalStateException("private provider detail");
        });
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var older = executor.submit(() -> services.bindings.bind(
                    actor, journey, REQUEST, 0, Optional.empty()));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0,
                    Optional.empty())).isInstanceOf(RouteBindingUnavailable.class)
                    .hasMessage("Route binding unavailable").hasNoCause();
            releaseFirst.countDown();
            assertThatThrownBy(() -> older.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RouteBindingConflict.class);
        }
        assertThat(provider.calls).hasValue(2);
        assertThat(state(actor)).isEqualTo("PENDING");
    }

    @Test
    void newestAttemptWinsRegardlessOfProviderResponseOrder() throws Exception {
        for (boolean newerReturnsFirst : List.of(false, true)) {
            MutableClock clock = new MutableClock(START);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch releaseSecond = new CountDownLatch(1);
            AtomicInteger sequence = new AtomicInteger();
            ControlledProvider provider = new ControlledProvider(request -> {
                int number = sequence.getAndIncrement();
                if (number == 0) {
                    firstEntered.countDown();
                    await(releaseFirst);
                } else {
                    secondEntered.countDown();
                    await(releaseSecond);
                }
                return routeThroughAnchor();
            });
            Services services = services(clock, provider, null);
            UUID actor = account();
            UUID journey = activeAndSharing(services, actor);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var older = executor.submit(() -> services.bindings.bind(
                        actor, journey, REQUEST, 0, Optional.empty()));
                assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
                var newer = executor.submit(() -> services.bindings.bind(
                        actor, journey, REQUEST, 0, Optional.empty()));
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                if (newerReturnsFirst) {
                    releaseSecond.countDown();
                    assertThat(newer.get(5, TimeUnit.SECONDS).status())
                            .isEqualTo(RouteBindingOutcome.Status.BOUND);
                    releaseFirst.countDown();
                } else {
                    releaseFirst.countDown();
                    assertThatThrownBy(() -> older.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(RouteBindingConflict.class);
                    releaseSecond.countDown();
                    assertThat(newer.get(5, TimeUnit.SECONDS).status())
                            .isEqualTo(RouteBindingOutcome.Status.BOUND);
                }
                assertThatThrownBy(() -> older.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(RouteBindingConflict.class);
            }
            assertThat(state(actor)).isEqualTo("CONSUMED");
        }
    }

    @Test
    void accountBudgetSpansAdaptersAndChargesProviderFailures() {
        MutableClock clock = new MutableClock(START);
        ControlledProvider failing = new ControlledProvider(request -> {
            throw new IllegalStateException("provider failed");
        });
        UUID actor = account();
        Services first = services(clock, failing, null);
        UUID journey = activeAndSharing(first, actor);
        for (int index = 0; index < 5; index++) {
            assertThatThrownBy(() -> first.bindings.bind(actor, journey, REQUEST, 0,
                    Optional.empty())).isInstanceOf(RouteBindingUnavailable.class);
        }
        Services second = services(clock, failing, null);
        for (int index = 0; index < 5; index++) {
            assertThatThrownBy(() -> second.bindings.bind(actor, journey, REQUEST, 0,
                    Optional.empty())).isInstanceOf(RouteBindingUnavailable.class);
        }
        assertThatThrownBy(() -> second.bindings.bind(actor, journey, REQUEST, 0, Optional.empty()))
                .isInstanceOf(RouteBindingRateLimited.class).hasNoCause();
        assertThat(failing.calls).hasValue(10);
    }

    @Test
    void rateStoreFailureIsSanitizedBeforeProviderAndWritesNothing() {
        MutableClock clock = new MutableClock(START);
        ControlledProvider provider = new ControlledProvider(request -> routeThroughAnchor());
        Services services = services(clock, provider, (key, category, limit) -> {
            throw new org.springframework.dao.DataAccessResourceFailureException(
                    "private rate database detail");
        });
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);
        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0, Optional.empty()))
                .isInstanceOf(RouteBindingUnavailable.class)
                .hasMessage("Route binding unavailable").hasNoCause();
        assertThat(provider.calls).hasValue(0);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM route_binding_attempt WHERE actor_id = ?", Integer.class, actor))
                .isZero();
    }

    @Test
    void consentChangeCompletionAndDirectContextReplacementInvalidatePendingAttempts() throws Exception {
        assertConcurrentInvalidation(Invalidation.CONSENT);
        assertConcurrentInvalidation(Invalidation.COMPLETION);
        assertConcurrentInvalidation(Invalidation.CONTEXT);
    }

    @Test
    void futureAndExpiredAttemptsAreDeniedAfterProviderWithoutChangingContext() {
        MutableClock clock = new MutableClock(START);
        AtomicInteger call = new AtomicInteger();
        ControlledProvider provider = new ControlledProvider(request -> {
            if (call.incrementAndGet() == 1) clock.set(START.minusSeconds(1));
            else clock.set(START.plusSeconds(91));
            return routeThroughAnchor();
        });
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);
        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0, Optional.empty()))
                .isInstanceOf(RouteBindingConflict.class).hasNoCause();
        clock.set(START);
        assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0, Optional.empty()))
                .isInstanceOf(RouteBindingConflict.class).hasNoCause();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM live_route_context WHERE actor_id = ?",
                Integer.class, actor))
                .isZero();
    }

    @Test
    void deadlineIsEvaluatedAfterWaitingForTheAttemptRowLock() throws Exception {
        MutableClock clock = new MutableClock(START);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        ControlledProvider provider = new ControlledProvider(request -> {
            providerEntered.countDown();
            await(releaseProvider);
            return routeThroughAnchor();
        });
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var binding = executor.submit(() -> services.bindings.bind(
                    actor, journey, REQUEST, 0, Optional.empty()));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var holder = executor.submit(() -> {
                new org.springframework.transaction.support.TransactionTemplate(manager()).execute(status -> {
                    jdbc().queryForObject("SELECT attempt_id FROM route_binding_attempt "
                            + "WHERE actor_id = ? FOR UPDATE", UUID.class, actor);
                    rowLocked.countDown();
                    await(releaseRow);
                    return null;
                });
                return null;
            });
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            releaseProvider.countDown();
            assertThat(waitForBlocked("%route_binding_attempt%FOR UPDATE%", 5_000)).isTrue();
            clock.set(START.plusSeconds(91));
            releaseRow.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> binding.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RouteBindingConflict.class);
        }
        assertThat(state(actor)).isEqualTo("PENDING");
    }

    @Test
    void accountDeletionDuringProviderCascadesAndPreventsFinish() throws Exception {
        MutableClock clock = new MutableClock(START);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ControlledProvider provider = new ControlledProvider(request -> {
            entered.countDown();
            await(release);
            return routeThroughAnchor();
        });
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var binding = executor.submit(() -> services.bindings.bind(
                    actor, journey, REQUEST, 0, Optional.empty()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc().update("DELETE FROM routiqo_account WHERE id = ?", actor);
            release.countDown();
            assertThatThrownBy(() -> binding.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SecurityException.class);
        }
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM route_binding_attempt WHERE actor_id = ?", Integer.class, actor))
                .isZero();
    }

    @Test
    void contextFailureRollsBackAttemptConsumption() {
        MutableClock clock = new MutableClock(START);
        Services services = services(clock,
                new ControlledProvider(request -> routeThroughAnchor()), null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);
        jdbc().execute("""
            CREATE OR REPLACE FUNCTION route_binding_test_fail_context()
            RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
                RAISE EXCEPTION 'private context detail';
            END $$
            """);
        jdbc().execute("""
            CREATE TRIGGER route_binding_test_fail_context
            BEFORE INSERT OR UPDATE ON live_route_context
            FOR EACH ROW EXECUTE FUNCTION route_binding_test_fail_context()
            """);
        try {
            assertThatThrownBy(() -> services.bindings.bind(actor, journey, REQUEST, 0,
                    Optional.empty())).isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining("private context detail");
            assertThat(state(actor)).isEqualTo("PENDING");
            assertThat(jdbc().queryForObject("SELECT count(*) FROM live_route_context WHERE actor_id = ?",
                    Integer.class, actor))
                    .isZero();
        } finally {
            jdbc().execute("DROP TRIGGER route_binding_test_fail_context ON live_route_context");
            jdbc().execute("DROP FUNCTION route_binding_test_fail_context()");
        }
    }

    @Test
    void migrationAndTrustedTransactionBoundaryFailClosedAndRedact() {
        assertThatThrownBy(() -> configuredAttempts.invalidatePending(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Route binding attempt transaction is required").hasNoCause();
        assertThat(configuredAttempts.toString()).isEqualTo(
                "JdbcRouteBindingAttemptParticipant[private]");
        assertThat(new RouteBindingConflict()).hasNoCause();

        UUID actor = account();
        Services services = services(new MutableClock(START),
                new ControlledProvider(request -> routeThroughAnchor()), null);
        UUID journey = activeAndSharing(services, actor);
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO route_binding_attempt(actor_id, attempt_id, journey_id,
                consent_generation, catalog_version, issued_at, deadline, state)
            VALUES (?, ?, ?, 0, ?, ?, ?, 'PENDING')
            """, actor, UUID.randomUUID(), journey, UUID.randomUUID(),
                java.sql.Timestamp.from(START), java.sql.Timestamp.from(START.plusSeconds(89))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void assertConcurrentInvalidation(Invalidation invalidation) throws Exception {
        MutableClock clock = new MutableClock(START);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ControlledProvider provider = new ControlledProvider(request -> {
            entered.countDown();
            await(release);
            return routeThroughAnchor();
        });
        Services services = services(clock, provider, null);
        UUID actor = account();
        UUID journey = activeAndSharing(services, actor);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var binding = executor.submit(() -> services.bindings.bind(
                    actor, journey, REQUEST, 0, Optional.empty()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (invalidation == Invalidation.CONSENT) {
                services.consents.submitIntent(actor, journey, 1, false);
            } else if (invalidation == Invalidation.COMPLETION) {
                services.journeys.complete(actor, journey);
            } else {
                services.contexts.replace(actor, journey, Set.of(UUID.randomUUID()),
                        java.time.Duration.ofSeconds(1), Optional.empty());
                clock.set(START.plusSeconds(2));
                new JdbcLiveRouteContextExpiryMaintenance(jdbc(), manager(), clock).purgeExpired(100);
                assertThat(jdbc().queryForObject("SELECT count(*) FROM live_route_context WHERE actor_id = ?",
                        Integer.class, actor))
                        .isZero();
            }
            release.countDown();
            assertThatThrownBy(() -> binding.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RuntimeException.class);
        }
        assertThat(state(actor)).isEqualTo(invalidation == Invalidation.CONSENT
                ? "PENDING" : "INVALIDATED");
    }

    private Services services(MutableClock clock, ControlledProvider provider, AuthRateGate rates) {
        JdbcTemplate jdbc = jdbc();
        var store = new JdbcJourneyStore(jdbc, new JdbcAccountWriteAuthority(jdbc, manager()));
        var consent = new JdbcPresenceConsentParticipant(jdbc);
        var attempts = new JdbcRouteBindingAttemptParticipant(jdbc, clock);
        var context = new JdbcLiveRouteContextParticipant(jdbc, clock, attempts);
        var journeys = new JourneyService(store, store, List.of(consent, context, attempts), clock);
        var consentService = new PresenceConsentService(store, consent);
        var contextService = new LiveRouteContextService(store, context);
        var catalog = new RouteAnchorCatalog(CATALOG, List.of(new RouteAnchor(ANCHOR,
                new RouteRequest.Coordinate(0.05, 0), Set.of(QuickSignalValue.Category.QUEUE))));
        var resolver = new RouteAnchorResolver(provider, catalog);
        AuthRateGate selectedRates = rates == null
                ? new JdbcAuthRateGate(jdbc, "route-binding-focused-test-secret-key", clock)
                : rates;
        var bindings = new RouteBindingService(store, consent, context, attempts, resolver,
                selectedRates);
        return new Services(journeys, consentService, contextService, bindings);
    }

    private UUID activeAndSharing(Services services, UUID actor) {
        UUID journey = start(services, actor);
        services.consents.submitIntent(actor, journey, 0, true);
        return journey;
    }

    private UUID start(Services services, UUID actor) {
        UUID journey = UUID.randomUUID();
        services.journeys.start(actor, journey, Journey.Kind.TRIP);
        return journey;
    }

    private UUID account() {
        UUID actor = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, actor.toString());
        return actor;
    }

    private String state(UUID actor) {
        return jdbc().queryForObject("SELECT state FROM route_binding_attempt WHERE actor_id = ?",
                String.class, actor);
    }

    private boolean waitForBlocked(String queryPattern, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity activity
                    WHERE activity.datname = current_database()
                      AND cardinality(pg_blocking_pids(activity.pid)) > 0
                      AND activity.query LIKE ?)
                """, Boolean.class, queryPattern);
            if (Boolean.TRUE.equals(waiting)) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    private DataSourceTransactionManager manager() { return new DataSourceTransactionManager(dataSource); }

    private static List<RouteOption> routeThroughAnchor() {
        return List.of(new RouteOption(10_000, 600, List.of(REQUEST.origin(),
                new RouteRequest.Coordinate(0.05, 0), REQUEST.destination())));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted");
        }
    }

    private enum Invalidation { CONSENT, COMPLETION, CONTEXT }
    private record Services(JourneyService journeys, PresenceConsentService consents,
            LiveRouteContextService contexts, RouteBindingService bindings) {}

    private static final class ControlledProvider implements RouteProvider {
        private final Function<RouteRequest, List<RouteOption>> behavior;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean observedTransaction;
        private ControlledProvider(Function<RouteRequest, List<RouteOption>> behavior) {
            this.behavior = behavior;
        }
        @Override public Identity identity() { return Identity.VALHALLA; }
        @Override public List<RouteOption> routes(RouteRequest request) {
            calls.incrementAndGet();
            observedTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            return behavior.apply(request);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private MutableClock(Instant current) { this.current = new AtomicReference<>(current); }
        private void set(Instant value) { current.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
