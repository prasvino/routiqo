package com.routiqo.core.privacy.infrastructure;

import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.identity.infrastructure.JdbcSessionStore;
import com.routiqo.core.journey.application.JourneyCompletionParticipant;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.journey.infrastructure.JdbcJourneyStore;
import com.routiqo.core.privacy.application.PresenceConsentConflict;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.routiqo.core.privacy.domain.PresenceConsent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class PresenceConsentPersistenceTest {
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
    @Autowired JourneyService configuredJourneys;
    @Autowired PresenceConsentService configuredConsents;
    @Autowired JdbcPresenceConsentParticipant configuredParticipant;

    @Test void migrationEnforcesOwnershipStateAndOneLatestRowThenAccountDeletionCascades() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        UUID actor = account();
        UUID other = account();
        UUID journey = start(configuredJourneys, actor);

        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO presence_consent
                (actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, -1, FALSE, TRUE)
            """, actor, journey)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO presence_consent
                (actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, 0, TRUE, FALSE)
            """, actor, journey)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO presence_consent
                (actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, 0, FALSE, TRUE)
            """, other, journey)).isInstanceOf(DataIntegrityViolationException.class);

        configuredConsents.change(actor, journey, 0, false);
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO presence_consent
                (actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, 0, FALSE, TRUE)
            """, actor, journey)).isInstanceOf(DataIntegrityViolationException.class);

        String tokenHash = session(actor);
        new JdbcSessionStore(jdbc(), manager()).deleteAccount(tokenHash, START.plusSeconds(1));
        assertThat(rowCount(actor)).isZero();
    }

    @Test void readsReturnJourneyScopedOptOutWithoutCreatingOrReplacingRows() {
        UUID actor = account();
        UUID first = start(configuredJourneys, actor);

        assertThat(configuredConsents.read(actor, first))
                .isEqualTo(PresenceConsent.initial(actor, first));
        assertThat(rowCount(actor)).isZero();

        configuredConsents.change(actor, first, 0, true);
        configuredJourneys.complete(actor, first);
        UUID second = start(configuredJourneys, actor);
        PresenceConsent secondOff = configuredConsents.change(actor, second, 0, false);
        assertThat(secondOff).isEqualTo(PresenceConsent.initial(actor, second));

        PresenceConsent oldRead = configuredConsents.read(actor, first);
        assertThat(oldRead).isEqualTo(new PresenceConsent(actor, first, 0, false, false));
        assertThat(stored(actor)).isEqualTo(secondOff);
        assertThat(rowCount(actor)).isEqualTo(1);
    }

    @Test void compareAndSetHandlesSameStateGhostAndReorderedEnableWithoutReplayClaims() {
        UUID actor = account();
        UUID journey = start(configuredJourneys, actor);

        PresenceConsent enabled = configuredConsents.change(actor, journey, 0, true);
        assertThat(enabled.generation()).isEqualTo(1);
        assertThat(configuredConsents.change(actor, journey, 1, true)).isEqualTo(enabled);

        PresenceConsent ghost = configuredConsents.change(actor, journey, 1, false);
        assertThat(ghost.generation()).isEqualTo(2);
        assertConflict(() -> configuredConsents.change(actor, journey, 1, true));
        PresenceConsent reenabled = configuredConsents.change(actor, journey, 2, true);
        assertThat(reenabled.generation()).isEqualTo(3);
        assertConflict(() -> configuredConsents.change(actor, journey, 2, true));
        assertThat(stored(actor)).isEqualTo(reenabled);
    }

    @Test void generationOverflowRollsBackWithoutChangingPrivateState() {
        UUID actor = account();
        UUID journey = start(configuredJourneys, actor);
        jdbc().update("""
            INSERT INTO presence_consent
                (actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, ?, FALSE, TRUE)
            """, actor, journey, Long.MAX_VALUE);

        assertConflict(() -> configuredConsents.change(actor, journey, Long.MAX_VALUE, true));
        assertThat(stored(actor)).isEqualTo(
                new PresenceConsent(actor, journey, Long.MAX_VALUE, false, true));
        assertThat(configuredJourneys.get(actor, journey).status()).isEqualTo(Journey.Status.ACTIVE);
    }

    @Test void unchangedInitialOptOutDoesNotFenceALaterEnableWithTheSameGeneration() {
        UUID actor = account();
        UUID journey = start(configuredJourneys, actor);

        PresenceConsent unchanged = configuredConsents.change(actor, journey, 0, false);
        assertThat(unchanged.generation()).isZero();
        PresenceConsent laterEnable = configuredConsents.change(actor, journey, 0, true);
        assertThat(laterEnable).isEqualTo(new PresenceConsent(actor, journey, 1, true, true));
    }

    @Test void aNewActiveJourneyReplacesTheSingleLatestRowOnlyOnExplicitChange() {
        UUID actor = account();
        UUID first = start(configuredJourneys, actor);
        configuredConsents.change(actor, first, 0, true);
        configuredJourneys.complete(actor, first);
        UUID second = start(configuredJourneys, actor);

        assertThat(configuredConsents.read(actor, second))
                .isEqualTo(PresenceConsent.initial(actor, second));
        assertThat(stored(actor).journeyId()).isEqualTo(first);
        PresenceConsent replacement = configuredConsents.change(actor, second, 0, false);
        assertThat(replacement).isEqualTo(PresenceConsent.initial(actor, second));
        assertThat(stored(actor)).isEqualTo(replacement);
        assertThat(rowCount(actor)).isEqualTo(1);
    }

    @Test void independentAdaptersSerializeTheSameExpectedGenerationAcrossReplicas() throws Exception {
        UUID actor = account();
        Services setup = services(START);
        UUID journey = start(setup.journeys(), actor);
        PresenceConsentService first = services(START.plusSeconds(1)).consents();
        PresenceConsentService second = services(START.plusSeconds(1)).consents();

        List<Object> results = race(
                () -> first.change(actor, journey, 0, true),
                () -> second.change(actor, journey, 0, true));
        assertThat(results.stream().filter(PresenceConsent.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(PresenceConsentConflict.class::isInstance)).hasSize(1);
        assertThat(stored(actor)).isEqualTo(new PresenceConsent(actor, journey, 1, true, true));
    }

    @Test void configuredCompletionRevokesConsentAtomicallyAndTerminalRetryDoesNotIncrement() {
        UUID actor = account();
        UUID journey = start(configuredJourneys, actor);
        assertThat(configuredConsents.change(actor, journey, 0, true).generation()).isEqualTo(1);

        assertThat(configuredJourneys.complete(actor, journey).status()).isEqualTo(Journey.Status.COMPLETED);
        PresenceConsent revoked = stored(actor);
        assertThat(revoked).isEqualTo(new PresenceConsent(actor, journey, 2, false, false));
        configuredJourneys.complete(actor, journey);
        assertThat(stored(actor)).isEqualTo(revoked);
        assertConflict(() -> configuredConsents.change(actor, journey, 2, true));
        assertThat(configuredConsents.change(actor, journey, 2, false)).isEqualTo(revoked);
    }

    @Test void completionWithoutMatchingConsentIsANoOpForTheSingleLatestRow() {
        UUID actor = account();
        UUID first = start(configuredJourneys, actor);
        configuredConsents.change(actor, first, 0, true);
        configuredJourneys.complete(actor, first);
        PresenceConsent retained = stored(actor);

        UUID second = start(configuredJourneys, actor);
        configuredJourneys.complete(actor, second);
        assertThat(stored(actor)).isEqualTo(retained);

        UUID other = account();
        UUID noConsent = start(configuredJourneys, other);
        configuredJourneys.complete(other, noConsent);
        assertThat(rowCount(other)).isZero();
    }

    @Test void completionGenerationOverflowRollsBackTheJourneyUpdate() {
        UUID actor = account();
        UUID journey = start(configuredJourneys, actor);
        jdbc().update("""
            INSERT INTO presence_consent
                (actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, ?, FALSE, TRUE)
            """, actor, journey, Long.MAX_VALUE);

        assertConflict(() -> configuredJourneys.complete(actor, journey));
        assertThat(configuredJourneys.get(actor, journey).status()).isEqualTo(Journey.Status.ACTIVE);
        assertThat(stored(actor)).isEqualTo(
                new PresenceConsent(actor, journey, Long.MAX_VALUE, false, true));
    }

    @Test void laterCompletionParticipantFailureRollsBackJourneyAndConsentTogether() {
        UUID actor = account();
        Services services = services(START);
        UUID journey = start(services.journeys(), actor);
        services.consents().change(actor, journey, 0, true);
        JourneyCompletionParticipant failing = completed -> {
            throw new IllegalStateException("synthetic completion failure");
        };
        JourneyService failingCompletion = new JourneyService(services.store(), services.store(),
                List.of(services.participant(), failing), Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC));

        assertThatThrownBy(() -> failingCompletion.complete(actor, journey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic completion failure");
        assertThat(services.journeys().get(actor, journey).status()).isEqualTo(Journey.Status.ACTIVE);
        assertThat(stored(actor)).isEqualTo(new PresenceConsent(actor, journey, 1, true, true));
    }

    @Test void consentChangeRacingConfiguredCompletionAlwaysEndsInactive() throws Exception {
        UUID actor = account();
        UUID journey = start(configuredJourneys, actor);
        configuredConsents.change(actor, journey, 0, true);

        List<Object> results = race(
                () -> configuredConsents.change(actor, journey, 1, false),
                () -> configuredJourneys.complete(actor, journey));
        assertThat(results.stream().filter(Journey.class::isInstance)).hasSize(1);
        assertThat(configuredJourneys.get(actor, journey).status()).isEqualTo(Journey.Status.COMPLETED);
        PresenceConsent finalState = stored(actor);
        assertThat(finalState.sharing()).isFalse();
        assertThat(finalState.journeyActive()).isFalse();
        assertThat(finalState.generation()).isIn(2L, 3L);
    }

    @Test void staleEnableWaitsForCompletionThenFailsAgainstTheRevokedGeneration() throws Exception {
        UUID actor = account();
        Services completion = services(START.plusSeconds(60));
        UUID journey = start(completion.journeys(), actor);
        completion.consents().change(actor, journey, 0, true);
        completion.consents().change(actor, journey, 1, false);
        var completionReached = new CountDownLatch(1);
        var releaseCompletion = new CountDownLatch(1);
        JourneyCompletionParticipant blocker = completed -> {
            completionReached.countDown();
            await(releaseCompletion);
        };
        JourneyService completing = new JourneyService(completion.store(), completion.store(),
                List.of(completion.participant(), blocker),
                Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC));
        PresenceConsentService staleWriter = services(START.plusSeconds(60)).consents();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var completingFuture = executor.submit(() -> completing.complete(actor, journey));
            assertThat(completionReached.await(5, TimeUnit.SECONDS)).isTrue();
            var enableFuture = executor.submit(() -> {
                try {
                    return staleWriter.change(actor, journey, 2, true);
                } catch (PresenceConsentConflict conflict) {
                    return conflict;
                }
            });
            try {
                assertThat(waitingForAccountLock()).isTrue();
            } finally {
                releaseCompletion.countDown();
            }
            assertThat(completingFuture.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(Journey.Status.COMPLETED);
            assertThat(enableFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(PresenceConsentConflict.class);
        }
        assertThat(stored(actor)).isEqualTo(new PresenceConsent(actor, journey, 3, false, false));
    }

    @Test void jdbcParticipantRequiresExistingTransactionAndDiagnosticsRevealNoIdentifiers() {
        UUID actor = UUID.randomUUID();
        UUID journeyId = UUID.randomUUID();
        Journey journey = Journey.start(journeyId, actor, Journey.Kind.TRIP, START);

        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable call
                : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> configuredParticipant.read(journey),
                () -> configuredParticipant.change(journey, 0, true),
                () -> configuredParticipant.onCompleted(journey.complete(actor, START.plusSeconds(1))))) {
            assertThatThrownBy(call)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Presence consent transaction is required")
                    .hasNoCause();
        }
        assertThat(new PresenceConsentConflict().toString())
                .doesNotContain(actor.toString(), journeyId.toString());
        assertThat(configuredParticipant.toString()).isEqualTo("JdbcPresenceConsentParticipant[private]")
                .doesNotContain(actor.toString(), journeyId.toString());
    }

    private Services services(Instant now) {
        var jdbc = jdbc();
        var store = new JdbcJourneyStore(jdbc, new JdbcAccountWriteAuthority(jdbc, manager()));
        var participant = new JdbcPresenceConsentParticipant(jdbc);
        var journeys = new JourneyService(store, store, List.of(participant), Clock.fixed(now, ZoneOffset.UTC));
        return new Services(store, participant, journeys, new PresenceConsentService(store, participant));
    }

    private record Services(JdbcJourneyStore store, JdbcPresenceConsentParticipant participant,
            JourneyService journeys, PresenceConsentService consents) {}

    private UUID account() {
        UUID actor = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account (id, google_subject) VALUES (?, ?)", actor, actor.toString());
        return actor;
    }

    private static UUID start(JourneyService journeys, UUID actor) {
        UUID journey = UUID.randomUUID();
        journeys.start(actor, journey, Journey.Kind.TRIP);
        return journey;
    }

    private long rowCount(UUID actor) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM presence_consent WHERE actor_id = ?", Long.class, actor);
    }

    private PresenceConsent stored(UUID actor) {
        return jdbc().queryForObject("""
            SELECT actor_id, journey_id, generation, sharing, journey_active
            FROM presence_consent WHERE actor_id = ?
            """, (row, number) -> new PresenceConsent(
                row.getObject("actor_id", UUID.class), row.getObject("journey_id", UUID.class),
                row.getLong("generation"), row.getBoolean("sharing"), row.getBoolean("journey_active")), actor);
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

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private DataSourceTransactionManager manager() {
        return new DataSourceTransactionManager(dataSource);
    }

    private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
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
                    } catch (PresenceConsentConflict conflict) {
                        return conflict;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }

    private boolean waitingForAccountLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity activity
                    WHERE activity.datname = current_database()
                      AND cardinality(pg_blocking_pids(activity.pid)) > 0
                      AND activity.query LIKE '%routiqo_account%FOR UPDATE%'
                )
                """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
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
                .isInstanceOf(PresenceConsentConflict.class)
                .hasMessage("Presence consent changed")
                .hasNoCause();
    }
}
