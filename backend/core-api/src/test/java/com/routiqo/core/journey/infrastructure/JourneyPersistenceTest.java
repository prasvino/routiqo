package com.routiqo.core.journey.infrastructure;

import com.routiqo.core.journey.application.*;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("persistence")
class JourneyPersistenceTest {
    // Testcontainers owns this disposable database; never uses the developer's Compose volume.
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }
    @Autowired DataSource dataSource;
    @Autowired JourneyService configuredService;
    @Autowired Flyway flyway;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    static final Instant START = Instant.parse("2026-09-07T12:00:00.123456789Z");

    UUID owner() {
        var id = UUID.randomUUID();
        new JdbcTemplate(dataSource).update("INSERT INTO routiqo_account (id, google_subject) VALUES (?, ?)", id, id.toString());
        return id;
    }
    JourneyService service(Instant time) {
        var manager = new DataSourceTransactionManager(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        return new JourneyService(new JdbcJourneyStore(jdbc,
                new JdbcAccountWriteAuthority(jdbc, manager)), Clock.fixed(time, ZoneOffset.UTC));
    }

    @Test void migrationAndConfiguredServiceWork() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var owner = owner();
        var journey = configuredService.start(owner, UUID.randomUUID(), Journey.Kind.TRIP);
        assertThat(configuredService.get(owner, journey.id())).isEqualTo(journey);
    }
    @Test void accountDeletionWinningBeforeStartCannotLeaveAnOrphan() {
        var owner = owner();
        new JdbcTemplate(dataSource).update("DELETE FROM routiqo_account WHERE id = ?", owner);
        assertThatThrownBy(() -> service(START).start(owner, UUID.randomUUID(), Journey.Kind.TRIP)).isInstanceOf(SecurityException.class);
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM journey WHERE owner_id = ?", Long.class, owner)).isZero();
    }

    @Test void persistenceProfileKeepsUnauthenticatedWritesClosed() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://localhost:" + port + "/api/v1/journeys"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json").build();
        var response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(401, 403);
    }

    @Test void databaseRejectsInvalidLifecycleEvenOutsideApplication() {
        var owner = owner(); var id = UUID.randomUUID();
        service(START).start(owner, id, Journey.Kind.TRIP);
        assertThatThrownBy(() -> new JdbcTemplate(dataSource).update(
                "UPDATE journey SET status = 'COMPLETED' WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service(START).get(owner, id).status()).isEqualTo(Journey.Status.ACTIVE);
    }

    @Test void retriesSurviveNewAdapterAndNeverRestartCompletedJourney() {
        var owner = owner(); var id = UUID.randomUUID();
        var first = service(START).start(owner, id, Journey.Kind.TRIP);
        assertThat(first.startedAt()).isEqualTo(Instant.parse("2026-09-07T12:00:00.123456Z"));
        assertThat(service(START.plusSeconds(10)).start(owner, id, Journey.Kind.TRIP)).isEqualTo(first);
        assertThatThrownBy(() -> service(START).start(owner, id, Journey.Kind.COMMUTE))
                .isInstanceOf(JourneyConflict.class);
        var completed = service(START.plusSeconds(20)).complete(owner, id);
        assertThat(service(START.plusSeconds(30)).complete(owner, id)).isEqualTo(completed);
        service(START.plusSeconds(40)).start(owner, UUID.randomUUID(), Journey.Kind.COMMUTE);
        assertThat(service(START.plusSeconds(50)).start(owner, id, Journey.Kind.TRIP)).isEqualTo(completed);
    }

    @Test void ownershipAndActiveJourneyInvariantAreEnforced() {
        var owner = owner(); var stranger = owner(); var id = UUID.randomUUID();
        var service = service(START); service.start(owner, id, Journey.Kind.TRIP);
        assertThatThrownBy(() -> service.get(stranger, id)).isInstanceOf(JourneyNotFound.class);
        assertThatThrownBy(() -> service.complete(stranger, id)).isInstanceOf(JourneyNotFound.class);
        assertThatThrownBy(() -> service.get(stranger, UUID.randomUUID())).isInstanceOf(JourneyNotFound.class);
        assertThatThrownBy(() -> service.start(stranger, id, Journey.Kind.TRIP)).isInstanceOf(JourneyConflict.class);
        assertThatThrownBy(() -> service.start(owner, UUID.randomUUID(), Journey.Kind.TRIP)).isInstanceOf(JourneyConflict.class);
        assertThat(service.list(stranger, null, 50).journeys()).isEmpty();
        assertThat(service.get(owner, id).status()).isEqualTo(Journey.Status.ACTIVE);
    }

    List<Object> race(Callable<Journey> first, Callable<Journey> second) throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (var task : List.of(first, second)) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                try { return task.call(); } catch (JourneyConflict conflict) { return conflict; }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test void simultaneousRetryCreatesOneJourney() throws Exception {
        var owner = owner(); var id = UUID.randomUUID();
        var results = race(() -> service(START).start(owner, id, Journey.Kind.TRIP),
                () -> service(START.plusSeconds(1)).start(owner, id, Journey.Kind.TRIP));
        assertThat(results.get(0)).isInstanceOf(Journey.class).isEqualTo(results.get(1));
        assertThat(service(START).list(owner, null, 50).journeys()).hasSize(1);
    }

    @Test void simultaneousDistinctStartsCannotCreateTwoActiveJourneys() throws Exception {
        var owner = owner();
        var results = race(() -> service(START).start(owner, UUID.randomUUID(), Journey.Kind.TRIP),
                () -> service(START).start(owner, UUID.randomUUID(), Journey.Kind.COMMUTE));
        assertThat(results.stream().filter(Journey.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(JourneyConflict.class::isInstance).count()).isEqualTo(1);
    }

    @Test void simultaneousCompletionPreservesFirstCommittedTime() throws Exception {
        var owner = owner(); var id = UUID.randomUUID();
        service(START).start(owner, id, Journey.Kind.TRIP);
        var results = race(() -> service(START.plusSeconds(10)).complete(owner, id),
                () -> service(START.plusSeconds(20)).complete(owner, id));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(service(START).get(owner, id)).isEqualTo(results.get(0));
    }

    @Test void historyPaginationHandlesEqualTimestampsAndBounds() {
        var owner = owner(); var service = service(START);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            var id = UUID.randomUUID(); ids.add(id);
            service.start(owner, id, Journey.Kind.TRIP); service.complete(owner, id);
        }
        var first = service.list(owner, null, 2);
        var second = service.list(owner, first.next(), 2);
        assertThat(first.journeys()).hasSize(2); assertThat(second.journeys()).hasSize(1);
        assertThat(second.next()).isNull();
        var actual = new HashSet<UUID>();
        first.journeys().forEach(j -> actual.add(j.id())); second.journeys().forEach(j -> actual.add(j.id()));
        assertThat(actual).isEqualTo(ids);
        assertThatThrownBy(() -> service.list(owner, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(owner, null, 51)).isInstanceOf(IllegalArgumentException.class);
    }
}
