package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.identity.infrastructure.JdbcSessionStore;
import com.routiqo.core.journey.application.JourneyCompletionParticipant;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.journey.infrastructure.JdbcJourneyStore;
import com.routiqo.core.privacy.infrastructure.JdbcPresenceConsentParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextConflict;
import com.routiqo.core.routeupdate.application.LiveRouteContextExpiryMaintenance;
import com.routiqo.core.routeupdate.application.LiveRouteContextService;
import com.routiqo.core.routeupdate.domain.LiveRouteContext;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class LiveRouteContextPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant START = Instant.parse("2026-09-13T08:00:00.123456Z");

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired JdbcLiveRouteContextParticipant configuredParticipant;
    @Autowired LiveRouteContextService configuredContexts;
    @Autowired LiveRouteContextExpiryMaintenance configuredMaintenance;
    @Autowired JdbcPresenceConsentParticipant configuredConsent;

    @Test
    void migrationEnforcesIdentityOwnershipAnchorsRevisionAndLifetimeThenAccountDeletionCascades() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        UUID actor = account();
        UUID other = account();
        UUID journey = start(services(START).journeys(), actor);
        UUID context = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();

        assertInvalidInsert(actor, journey, new UUID(0, 0), 0, List.of(anchor),
                START, START.plusSeconds(1));
        assertInvalidInsert(actor, journey, context, -1, List.of(anchor),
                START, START.plusSeconds(1));
        assertInvalidInsert(other, journey, context, 0, List.of(anchor),
                START, START.plusSeconds(1));
        assertInvalidInsert(actor, journey, context, 0, List.of(),
                START, START.plusSeconds(1));
        assertInvalidInsert(actor, journey, context, 0, List.of(new UUID(0, 0)),
                START, START.plusSeconds(1));
        assertInvalidInsert(actor, journey, context, 0, List.of(anchor, anchor),
                START, START.plusSeconds(1));
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(129).toList();
        assertInvalidInsert(actor, journey, context, 0, tooMany,
                START, START.plusSeconds(1));
        assertInvalidInsert(actor, journey, context, 0, List.of(anchor), START, START);
        assertInvalidInsert(actor, journey, context, 0, List.of(anchor),
                START, START.plus(Duration.ofHours(24)).plusNanos(1_000));

        Services services = services(START);
        StoredLiveRouteContext stored = services.contexts().replace(actor, journey, Set.of(anchor),
                Duration.ofMinutes(5), Optional.empty());
        UUID secondActor = account();
        UUID secondJourney = start(services(START).journeys(), secondActor);
        assertInvalidInsert(secondActor, secondJourney, stored.context().contextId(), 0,
                List.of(UUID.randomUUID()), START, START.plusSeconds(1));
        String tokenHash = session(actor);
        new JdbcSessionStore(jdbc(), manager()).deleteAccount(tokenHash, START.plusSeconds(1));
        assertThat(rowCount(actor)).isZero();
        assertThat(stored.toString()).isEqualTo("StoredLiveRouteContext[private]");
    }

    @Test
    void readIsNonMutatingAndRejectsMissingFutureExpiredDifferentAndCompletedRows() {
        Services services = services(START);
        UUID actor = account();
        UUID first = start(services.journeys(), actor);
        assertThat(services.contexts().read(actor, first)).isEmpty();
        assertThat(rowCount(actor)).isZero();

        StoredLiveRouteContext current = services.contexts().replace(actor, first, anchors(2),
                Duration.ofMinutes(5), Optional.empty());
        assertThat(services.contexts().read(actor, first)).contains(current);

        services.clock().set(START.minusSeconds(1));
        assertThat(services.contexts().read(actor, first)).isEmpty();
        services.clock().set(current.expiresAt());
        assertThat(services.contexts().read(actor, first)).isEmpty();
        assertThat(rowCount(actor)).isEqualTo(1);

        services.clock().set(START.plusSeconds(1));
        services.journeys().complete(actor, first);
        assertThat(services.contexts().read(actor, first)).isEmpty();
        UUID second = start(services.journeys(), actor);
        assertThat(services.contexts().read(actor, second)).isEmpty();
    }

    @Test
    void replacementUsesFreshIdentityExactCasAndRevisionWithoutRenewingOnRead() {
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);

        StoredLiveRouteContext first = services.contexts().replace(actor, journey, anchors(2),
                Duration.ofMinutes(5), Optional.empty());
        assertThat(first.context().revision()).isZero();
        assertThat(first.issuedAt()).isEqualTo(START);
        services.clock().set(START.plusSeconds(10));
        assertThat(services.contexts().read(actor, journey)).contains(first);
        assertThat(stored(actor)).isEqualTo(first);

        StoredLiveRouteContext second = services.contexts().replace(actor, journey, anchors(3),
                Duration.ofMinutes(10), Optional.of(first.context().contextId()));
        assertThat(second.context().contextId()).isNotEqualTo(first.context().contextId());
        assertThat(second.context().revision()).isEqualTo(1);
        assertThat(second.issuedAt()).isEqualTo(START.plusSeconds(10));
        assertThat(second.expiresAt()).isEqualTo(START.plusSeconds(610));
        assertThat(rowCount(actor)).isEqualTo(1);
        assertConflict(() -> services.contexts().replace(actor, journey, anchors(1),
                Duration.ofMinutes(1), Optional.of(first.context().contextId())));
    }

    @Test
    void replacementFloorsExpiryToDatabaseMicrosAndRejectsSubmicroLifetimeWithoutWriting() {
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        assertConflict(() -> services.contexts().replace(actor, journey, anchors(1),
                Duration.ofNanos(999), Optional.empty()));
        assertThat(rowCount(actor)).isZero();

        StoredLiveRouteContext returned = services.contexts().replace(actor, journey, anchors(1),
                Duration.ofSeconds(60).plusNanos(999), Optional.empty());
        assertThat(returned.expiresAt()).isEqualTo(START.plusSeconds(60));
        assertThat(stored(actor)).isEqualTo(returned);
        assertThat(services.contexts().read(actor, journey)).contains(returned);
    }

    @Test
    void expectedIdRulesFailClosedForMissingExpiredAndDifferentJourneyContexts() {
        Services services = services(START);
        UUID actor = account();
        UUID firstJourney = start(services.journeys(), actor);
        UUID unknown = UUID.randomUUID();
        assertConflict(() -> services.contexts().replace(actor, firstJourney, anchors(1),
                Duration.ofMinutes(1), Optional.of(unknown)));

        StoredLiveRouteContext first = services.contexts().replace(actor, firstJourney, anchors(1),
                Duration.ofSeconds(10), Optional.empty());
        services.clock().set(first.expiresAt());
        assertConflict(() -> services.contexts().replace(actor, firstJourney, anchors(1),
                Duration.ofMinutes(1), Optional.of(first.context().contextId())));
        StoredLiveRouteContext afterExpiry = services.contexts().replace(actor, firstJourney,
                anchors(1), Duration.ofMinutes(1), Optional.empty());
        assertThat(afterExpiry.context().revision()).isEqualTo(1);

        services.journeys().complete(actor, firstJourney);
        UUID secondJourney = start(services.journeys(), actor);
        StoredLiveRouteContext second = services.contexts().replace(actor, secondJourney,
                anchors(1), Duration.ofMinutes(1), Optional.empty());
        assertThat(second.context().revision()).isZero();
        assertThat(second.context().contextId()).isNotEqualTo(afterExpiry.context().contextId());
    }

    @Test
    void futureStoredRowsAndCompletedJourneysDenyReplacementWithoutMutation() {
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        UUID context = UUID.randomUUID();
        insertContext(actor, journey, context, 0, List.of(UUID.randomUUID()),
                START.plusSeconds(1), START.plusSeconds(60));
        assertConflict(() -> services.contexts().replace(actor, journey, anchors(1),
                Duration.ofMinutes(1), Optional.empty()));
        assertThat(stored(actor).context().contextId()).isEqualTo(context);

        services.clock().set(START.plusSeconds(2));
        services.journeys().complete(actor, journey);
        assertConflict(() -> services.contexts().replace(actor, journey, anchors(1),
                Duration.ofMinutes(1), Optional.empty()));
        assertThat(rowCount(actor)).isZero();
    }

    @Test
    void invalidReplacementAndRevisionOverflowAreCauseFreeAndDoNotMutate() {
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        UUID context = UUID.randomUUID();
        insertContext(actor, journey, context, Long.MAX_VALUE, List.of(UUID.randomUUID()),
                START.minusSeconds(1), START.plusSeconds(60));

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable invalid : List
                .<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                        () -> services.contexts().replace(actor, journey, Set.of(),
                                Duration.ofMinutes(1), Optional.of(context)),
                        () -> services.contexts().replace(actor, journey, anchors(1),
                                Duration.ZERO, Optional.of(context)),
                        () -> services.contexts().replace(actor, journey, anchors(1),
                                Duration.ofHours(24).plusNanos(1), Optional.of(context)),
                        () -> services.contexts().replace(actor, journey, anchors(1),
                                Duration.ofMinutes(1), Optional.of(context)))) {
            assertConflict(invalid);
        }
        assertThat(stored(actor).context().contextId()).isEqualTo(context);
        assertThat(stored(actor).context().revision()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void independentAdaptersSerializeTheSameExpectedContextAcrossReplicas() throws Exception {
        Services setup = services(START);
        UUID actor = account();
        UUID journey = start(setup.journeys(), actor);
        StoredLiveRouteContext initial = setup.contexts().replace(actor, journey, anchors(1),
                Duration.ofMinutes(5), Optional.empty());
        Services first = services(START.plusSeconds(1));
        Services second = services(START.plusSeconds(1));

        List<Object> outcomes = race(
                () -> first.contexts().replace(actor, journey, anchors(2), Duration.ofMinutes(5),
                        Optional.of(initial.context().contextId())),
                () -> second.contexts().replace(actor, journey, anchors(3), Duration.ofMinutes(5),
                        Optional.of(initial.context().contextId())));
        assertThat(outcomes.stream().filter(StoredLiveRouteContext.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(LiveRouteContextConflict.class::isInstance)).hasSize(1);
        assertThat(stored(actor).context().revision()).isEqualTo(1);
    }

    @Test
    void replacementSamplesTimeAfterWaitingForTheContextRowLock() throws Exception {
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        StoredLiveRouteContext initial = services.contexts().replace(actor, journey, anchors(1),
                Duration.ofSeconds(10), Optional.empty());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> holdContextRow(actor, locked, release));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> replacing = executor.submit(() -> {
                try {
                    return services.contexts().replace(actor, journey, anchors(1),
                            Duration.ofMinutes(1), Optional.of(initial.context().contextId()));
                } catch (LiveRouteContextConflict conflict) {
                    return conflict;
                }
            });
            assertThat(waitingForContextLock()).isTrue();
            services.clock().set(initial.expiresAt());
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(replacing.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(LiveRouteContextConflict.class);
        }
        assertThat(stored(actor)).isEqualTo(initial);
    }

    @Test
    void configuredCompletionDeletesMatchingContextAfterConsentAndRollbackRestoresIt() {
        Services services = services(START);
        assertThat(services.consent().completionOrder())
                .isLessThan(services.participant().completionOrder());
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        StoredLiveRouteContext context = services.contexts().replace(actor, journey, anchors(1),
                Duration.ofMinutes(5), Optional.empty());
        services.journeys().complete(actor, journey);
        assertThat(rowCount(actor)).isZero();

        UUID actor2 = account();
        UUID journey2 = start(services.journeys(), actor2);
        StoredLiveRouteContext retained = services.contexts().replace(actor2, journey2, anchors(1),
                Duration.ofMinutes(5), Optional.empty());
        JourneyCompletionParticipant failure = completed -> {
            throw new IllegalStateException("synthetic participant failure");
        };
        JourneyService failing = new JourneyService(services.store(), services.store(),
                List.of(services.participant(), services.consent(), failure),
                Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC));
        assertThatThrownBy(() -> failing.complete(actor2, journey2))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stored(actor2)).isEqualTo(retained);
        assertThat(services.journeys().get(actor2, journey2).status()).isEqualTo(Journey.Status.ACTIVE);
        assertThat(context.context().journeyId()).isEqualTo(journey);
    }

    @Test
    void completionLeavesADifferentlyBoundLatestContextUntouched() {
        Services services = services(START);
        UUID actor = account();
        UUID first = start(services.journeys(), actor);
        services.journeys().complete(actor, first);
        UUID second = start(services.journeys(), actor);
        StoredLiveRouteContext secondContext = services.contexts().replace(actor, second, anchors(1),
                Duration.ofMinutes(5), Optional.empty());

        services.journeys().complete(actor, first);
        assertThat(stored(actor)).isEqualTo(secondContext);
    }

    @Test
    void cleanupIsBoundedUsesLogicalExpiryAndRejectsAmbientTransactions() {
        jdbc().update("DELETE FROM live_route_context");
        Services services = services(START);
        List<UUID> actors = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID actor = account();
            UUID journey = start(services.journeys(), actor);
            services.contexts().replace(actor, journey, anchors(1), Duration.ofSeconds(index + 1),
                    Optional.empty());
            actors.add(actor);
        }
        JdbcLiveRouteContextExpiryMaintenance maintenance = maintenance(START.plusSeconds(2));
        assertThat(maintenance.purgeExpired(1)).isEqualTo(1);
        assertThat(actors.stream().mapToLong(this::rowCount).sum()).isEqualTo(2);
        assertThat(maintenance.purgeExpired(100)).isEqualTo(1);
        assertThat(actors.stream().mapToLong(this::rowCount).sum()).isEqualTo(1);

        for (int limit : List.of(0, 101)) {
            assertThatThrownBy(() -> maintenance.purgeExpired(limit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Live route context cleanup limit is invalid")
                    .hasNoCause();
        }
        TransactionTemplate transaction = new TransactionTemplate(manager());
        assertThatThrownBy(() -> transaction.execute(status -> maintenance.purgeExpired(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Live route context cleanup cannot join a transaction")
                .hasNoCause();
    }

    @Test
    void cleanupSkipsLockedRowsAndLaterRemovesThem() throws Exception {
        jdbc().update("DELETE FROM live_route_context");
        Services services = services(START);
        UUID lockedActor = account();
        UUID otherActor = account();
        UUID lockedJourney = start(services.journeys(), lockedActor);
        UUID otherJourney = start(services.journeys(), otherActor);
        services.contexts().replace(lockedActor, lockedJourney, anchors(1),
                Duration.ofSeconds(1), Optional.empty());
        services.contexts().replace(otherActor, otherJourney, anchors(1),
                Duration.ofSeconds(1), Optional.empty());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> holdContextRow(lockedActor, locked, release));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            JdbcLiveRouteContextExpiryMaintenance maintenance = maintenance(START.plusSeconds(2));
            assertThat(maintenance.purgeExpired(100)).isEqualTo(1);
            assertThat(rowCount(lockedActor)).isEqualTo(1);
            assertThat(rowCount(otherActor)).isZero();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(maintenance.purgeExpired(100)).isEqualTo(1);
        }
        assertThat(rowCount(lockedActor)).isZero();
    }

    @Test
    void leafCleanupDoesNotWaitForAnAccountRowLock() throws Exception {
        jdbc().update("DELETE FROM live_route_context");
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        services.contexts().replace(actor, journey, anchors(1), Duration.ofSeconds(1),
                Optional.empty());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> holdAccountRow(actor, locked, release));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maintenance(START.plusSeconds(2)).purgeExpired(1)).isEqualTo(1);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(rowCount(actor)).isZero();
    }

    @Test
    void expiredCleanupCannotDeleteAReplacementThatWaitsBehindItsExactOldRow() throws Exception {
        jdbc().update("DELETE FROM live_route_context");
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        services.contexts().replace(actor, journey, anchors(1), Duration.ofSeconds(1),
                Optional.empty());
        long advisoryKey = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        CountDownLatch deleting = new CountDownLatch(1);
        createBlockingDeleteTrigger(advisoryKey);

        try (Connection blocker = dataSource.getConnection();
                var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, advisoryKey);
                statement.execute();
            }
            Future<Integer> cleanup = executor.submit(() -> maintenance(START.plusSeconds(2))
                    .purgeExpired(1));
            assertThat(waitingFor("%DELETE FROM live_route_context%")).isTrue();
            deleting.countDown();
            services.clock().set(START.plusSeconds(2));
            Future<StoredLiveRouteContext> replacement = executor.submit(() -> {
                await(deleting);
                return services.contexts().replace(actor, journey, anchors(2),
                        Duration.ofMinutes(5), Optional.empty());
            });
            assertThat(waitingForContextLock()).isTrue();
            blocker.commit();
            assertThat(cleanup.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            StoredLiveRouteContext newContext = replacement.get(5, TimeUnit.SECONDS);
            assertThat(stored(actor)).isEqualTo(newContext);
            assertThat(newContext.context().revision()).isZero();
        } finally {
            jdbc().execute("DROP TRIGGER IF EXISTS live_route_context_test_delay ON live_route_context");
            jdbc().execute("DROP FUNCTION IF EXISTS live_route_context_test_delay()");
        }
    }

    @Test
    void maintenanceFailureIsRedactedBeforeRollbackAndConfiguredBeansAreLeafOnly() {
        JdbcTemplate failingJdbc = new JdbcTemplate(dataSource) {
            @Override
            public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper,
                    Object... args) {
                throw new DataAccessResourceFailureException("private SQL and identifiers");
            }
        };
        var maintenance = new JdbcLiveRouteContextExpiryMaintenance(
                failingJdbc, manager(), Clock.fixed(START, ZoneOffset.UTC));
        assertThatThrownBy(() -> maintenance.purgeExpired(1))
                .isInstanceOf(AccountWriteUnavailable.class)
                .hasMessage("Account write authority is unavailable")
                .hasNoCause();
        assertThat(configuredMaintenance).isInstanceOf(JdbcLiveRouteContextExpiryMaintenance.class);
        assertThat(configuredContexts).isNotNull();
        assertThat(configuredParticipant.completionOrder())
                .isGreaterThan(configuredConsent.completionOrder());
    }

    @Test
    void maintenanceHasARealFiveSecondDatabaseBudgetAndRedactsTimeout() throws Exception {
        jdbc().update("DELETE FROM live_route_context");
        Services services = services(START);
        UUID actor = account();
        UUID journey = start(services.journeys(), actor);
        services.contexts().replace(actor, journey, anchors(1), Duration.ofSeconds(1),
                Optional.empty());
        long advisoryKey = Math.abs(UUID.randomUUID().getMostSignificantBits());
        createBlockingDeleteTrigger(advisoryKey);

        try (Connection blocker = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, advisoryKey);
                statement.execute();
            }
            Future<Object> cleanup = executor.submit(() -> {
                try {
                    return maintenance(START.plusSeconds(2)).purgeExpired(1);
                } catch (AccountWriteUnavailable unavailable) {
                    return unavailable;
                }
            });
            assertThat(waitingFor("%DELETE FROM live_route_context%")).isTrue();
            Object result = cleanup.get(7, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(AccountWriteUnavailable.class);
            assertThat(result.toString()).doesNotContain(actor.toString(), journey.toString());
            blocker.rollback();
        } finally {
            jdbc().execute("DROP TRIGGER IF EXISTS live_route_context_test_delay ON live_route_context");
            jdbc().execute("DROP FUNCTION IF EXISTS live_route_context_test_delay()");
        }
        assertThat(rowCount(actor)).isEqualTo(1);
    }

    @Test
    void participantRequiresExistingTransactionAndDiagnosticsAreRedacted() {
        UUID actor = UUID.randomUUID();
        Journey journey = Journey.start(UUID.randomUUID(), actor, Journey.Kind.TRIP, START);
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call : List
                .<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                        () -> configuredParticipant.read(journey),
                        () -> configuredParticipant.replace(journey, anchors(1),
                                Duration.ofMinutes(1), Optional.empty(), UUID.randomUUID()),
                        () -> configuredParticipant.onCompleted(
                                journey.complete(actor, START.plusSeconds(1))))) {
            assertThatThrownBy(call)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Live route context transaction is required")
                    .hasNoCause();
        }
        assertThat(new LiveRouteContextConflict().toString())
                .doesNotContain(actor.toString(), journey.id().toString());
        assertThat(configuredParticipant.toString())
                .isEqualTo("JdbcLiveRouteContextParticipant[private]")
                .doesNotContain(actor.toString(), journey.id().toString());
    }

    private Services services(Instant now) {
        return services(new MutableClock(now));
    }

    private Services services(MutableClock clock) {
        JdbcTemplate jdbc = jdbc();
        var store = new JdbcJourneyStore(jdbc, new JdbcAccountWriteAuthority(jdbc, manager()));
        var consent = new JdbcPresenceConsentParticipant(jdbc);
        var participant = new JdbcLiveRouteContextParticipant(jdbc, clock);
        var journeys = new JourneyService(store, store, List.of(participant, consent), clock);
        return new Services(store, consent, participant, journeys,
                new LiveRouteContextService(store, participant), clock);
    }

    private record Services(
            JdbcJourneyStore store,
            JdbcPresenceConsentParticipant consent,
            JdbcLiveRouteContextParticipant participant,
            JourneyService journeys,
            LiveRouteContextService contexts,
            MutableClock clock) {}

    private JdbcLiveRouteContextExpiryMaintenance maintenance(Instant now) {
        return new JdbcLiveRouteContextExpiryMaintenance(
                jdbc(), manager(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private UUID account() {
        UUID actor = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account (id, google_subject) VALUES (?, ?)",
                actor, actor.toString());
        return actor;
    }

    private static UUID start(JourneyService journeys, UUID actor) {
        UUID journey = UUID.randomUUID();
        journeys.start(actor, journey, Journey.Kind.TRIP);
        return journey;
    }

    private Set<UUID> anchors(int count) {
        var result = new java.util.LinkedHashSet<UUID>();
        while (result.size() < count) {
            result.add(UUID.randomUUID());
        }
        return Set.copyOf(result);
    }

    private void assertInvalidInsert(UUID actor, UUID journey, UUID context, long revision,
            List<UUID> anchors, Instant issuedAt, Instant expiresAt) {
        assertThatThrownBy(() -> insertContext(actor, journey, context, revision, anchors,
                issuedAt, expiresAt)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertContext(UUID actor, UUID journey, UUID context, long revision,
            List<UUID> anchors, Instant issuedAt, Instant expiresAt) {
        jdbc().update("""
            INSERT INTO live_route_context
                (actor_id, journey_id, context_id, revision, anchor_ids, issued_at, expires_at)
            VALUES (?, ?, ?, ?, CAST(? AS UUID[]), ?, ?)
            """, actor, journey, context, revision, arrayLiteral(anchors),
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    private static String arrayLiteral(List<UUID> values) {
        return "{" + String.join(",", values.stream().map(UUID::toString).toList()) + "}";
    }

    private StoredLiveRouteContext stored(UUID actor) {
        return jdbc().queryForObject("""
            SELECT context_id, actor_id, journey_id, revision, anchor_ids, issued_at, expires_at
            FROM live_route_context WHERE actor_id = ?
            """, (row, number) -> {
                UUID[] values = (UUID[]) row.getArray("anchor_ids").getArray();
                LiveRouteContext context = new LiveRouteContext(
                        row.getObject("context_id", UUID.class),
                        row.getObject("actor_id", UUID.class),
                        row.getObject("journey_id", UUID.class), row.getLong("revision"),
                        Set.copyOf(List.of(values)));
                return new StoredLiveRouteContext(context,
                        row.getTimestamp("issued_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant());
            }, actor);
    }

    private long rowCount(UUID actor) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM live_route_context WHERE actor_id = ?", Long.class, actor);
    }

    private String session(UUID actor) {
        String tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc().update("""
            INSERT INTO auth_session
                (token_hash, account_id, created_at, expires_at, authenticated_at)
            VALUES (?, ?, ?, ?, ?)
            """, tokenHash, actor, Timestamp.from(START), Timestamp.from(START.plusSeconds(900)),
                Timestamp.from(START));
        return tokenHash;
    }

    private void holdContextRow(UUID actor, CountDownLatch locked, CountDownLatch release) {
        TransactionTemplate transaction = new TransactionTemplate(manager());
        transaction.executeWithoutResult(status -> {
            jdbc().queryForObject("""
                SELECT context_id FROM live_route_context WHERE actor_id = ? FOR UPDATE
                """, UUID.class, actor);
            locked.countDown();
            await(release);
        });
    }

    private void holdAccountRow(UUID actor, CountDownLatch locked, CountDownLatch release) {
        TransactionTemplate transaction = new TransactionTemplate(manager());
        transaction.executeWithoutResult(status -> {
            jdbc().queryForObject("""
                SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE
                """, UUID.class, actor);
            locked.countDown();
            await(release);
        });
    }

    private boolean waitingForContextLock() throws InterruptedException {
        return waitingFor("%live_route_context%FOR UPDATE%");
    }

    private boolean waitingFor(String queryPattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity activity
                    WHERE activity.datname = current_database()
                      AND cardinality(pg_blocking_pids(activity.pid)) > 0
                      AND activity.query LIKE ?
                )
                """, Boolean.class, queryPattern);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private void createBlockingDeleteTrigger(long advisoryKey) {
        jdbc().execute("""
            CREATE OR REPLACE FUNCTION live_route_context_test_delay()
            RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN
                PERFORM pg_advisory_xact_lock(%d);
                RETURN OLD;
            END
            $$
            """.formatted(advisoryKey));
        jdbc().execute("""
            CREATE TRIGGER live_route_context_test_delay
            BEFORE DELETE ON live_route_context
            FOR EACH ROW EXECUTE FUNCTION live_route_context_test_delay()
            """);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private DataSourceTransactionManager manager() {
        return new DataSourceTransactionManager(dataSource);
    }

    private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Race did not start");
                    }
                    try {
                        return task.call();
                    } catch (LiveRouteContextConflict conflict) {
                        return conflict;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test release timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted");
        }
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(LiveRouteContextConflict.class)
                .hasMessage("Live route context changed")
                .hasNoCause();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("UTC is required");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
