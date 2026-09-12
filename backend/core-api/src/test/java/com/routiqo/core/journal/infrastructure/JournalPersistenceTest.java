package com.routiqo.core.journal.infrastructure;

import com.routiqo.core.journal.application.JournalConflict;
import com.routiqo.core.journal.application.JournalIneligible;
import com.routiqo.core.journal.application.JournalService;
import com.routiqo.core.journal.application.JournalStore;
import com.routiqo.core.journal.domain.JournalAnnotation;
import com.routiqo.core.journal.domain.JournalMutation;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.journey.infrastructure.JdbcJourneyStore;
import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("persistence")
class JournalPersistenceTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }
    static final Instant START = Instant.parse("2026-09-12T07:00:00.123456789Z");
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;

    JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    DataSourceTransactionManager transactions() { return new DataSourceTransactionManager(dataSource); }
    UUID owner() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account (id, google_subject) VALUES (?, ?)", id, id.toString());
        return id;
    }
    JourneyService journeys(Instant now) {
        var jdbc = jdbc();
        return new JourneyService(new JdbcJourneyStore(jdbc,
                new JdbcAccountWriteAuthority(jdbc, transactions())), Clock.fixed(now, ZoneOffset.UTC));
    }
    JournalService journals(Instant now) {
        return new JournalService(journeys(now), new JdbcJournalStore(jdbc(), transactions()), Clock.fixed(now, ZoneOffset.UTC));
    }
    UUID completed(UUID owner, Journey.Kind kind) {
        UUID id = UUID.randomUUID();
        journeys(START).start(owner, id, kind);
        journeys(START.plusSeconds(60)).complete(owner, id);
        return id;
    }
    JournalMutation mutation(String title, String notes, long expected) {
        return new JournalMutation(title, notes, expected, UUID.randomUUID());
    }

    @Test void migrationIsCurrentAndNeverWrittenTripDerivesVersionZero() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        UUID owner = owner(); UUID id = completed(owner, Journey.Kind.TRIP);
        var view = journals(START.plusSeconds(90)).get(owner, id);
        assertThat(view.annotation()).isEqualTo(JournalAnnotation.empty());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM journey_journal_annotation WHERE journey_id = ?",
                Long.class, id)).isZero();
    }

    @Test void firstSaveReplayConflictAndClearKeepMonotonicVersion() {
        UUID owner = owner(); UUID id = completed(owner, Journey.Kind.TRIP);
        JournalService service = journals(START.plusSeconds(90));
        JournalMutation firstMutation = mutation(" Coast road ", "Rain\nStopped for tea", 0);
        JournalAnnotation first = service.save(owner, id, firstMutation).annotation();
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-09-12T07:01:30.123456Z"));
        assertThat(service.save(owner, id, firstMutation).annotation()).isEqualTo(first);
        assertThatThrownBy(() -> service.save(owner, id,
                new JournalMutation("changed", firstMutation.notes(), 0, firstMutation.mutationId())))
                .isInstanceOf(JournalConflict.class);
        assertThatThrownBy(() -> service.save(owner, id, mutation("stale", "", 0)))
                .isInstanceOf(JournalConflict.class);

        JournalAnnotation second = journals(START.plusSeconds(120)).save(owner, id, mutation("Next", "entry", 1)).annotation();
        assertThat(second.version()).isEqualTo(2);
        JournalAnnotation cleared = journals(START.plusSeconds(150)).save(owner, id, mutation("", "", 2)).annotation();
        assertThat(cleared.title()).isEmpty();
        assertThat(cleared.notes()).isEmpty();
        assertThat(cleared.version()).isEqualTo(3);
        assertThat(cleared.updatedAt()).isNotNull();
        assertThat(journals(START.plusSeconds(180)).get(owner, id).annotation()).isEqualTo(cleared);
    }

    @Test void ownerIsolationAndEligibilityAreEnforcedBeforeAnnotationAccess() {
        UUID owner = owner(); UUID stranger = owner(); UUID trip = completed(owner, Journey.Kind.TRIP);
        journals(START.plusSeconds(90)).save(owner, trip, mutation("Private", "Only mine", 0));
        assertThatThrownBy(() -> journals(START.plusSeconds(90)).get(stranger, trip))
                .isInstanceOf(JourneyNotFound.class);
        assertThat(new JdbcJournalStore(jdbc(), transactions()).find(stranger, trip)).isEmpty();

        UUID active = UUID.randomUUID();
        journeys(START.plusSeconds(180)).start(owner, active, Journey.Kind.TRIP);
        assertThatThrownBy(() -> journals(START.plusSeconds(180)).get(owner, active))
                .isInstanceOf(JournalIneligible.class);
        journeys(START.plusSeconds(190)).complete(owner, active);
        UUID commute = completed(owner, Journey.Kind.COMMUTE);
        assertThatThrownBy(() -> journals(START.plusSeconds(200)).save(owner, commute, mutation("", "", 0)))
                .isInstanceOf(JournalIneligible.class);
    }

    @Test void databaseArbitratesConcurrentFirstWritersAndExactReplays() throws Exception {
        UUID owner = owner(); UUID id = completed(owner, Journey.Kind.TRIP);
        JournalMutation same = mutation("same", "retry", 0);
        List<Object> replays = race(
                () -> journals(START.plusSeconds(90)).save(owner, id, same).annotation(),
                () -> journals(START.plusSeconds(91)).save(owner, id, same).annotation());
        assertThat(replays.get(0)).isInstanceOf(JournalAnnotation.class).isEqualTo(replays.get(1));
        assertThat(jdbc().queryForObject("SELECT version FROM journey_journal_annotation WHERE journey_id = ?", Long.class, id)).isEqualTo(1);

        UUID another = completed(owner, Journey.Kind.TRIP);
        List<Object> competitors = race(
                () -> journals(START.plusSeconds(120)).save(owner, another, mutation("one", "", 0)).annotation(),
                () -> journals(START.plusSeconds(121)).save(owner, another, mutation("two", "", 0)).annotation());
        assertThat(competitors.stream().filter(JournalAnnotation.class::isInstance).count()).isEqualTo(1);
        assertThat(competitors.stream().filter(JournalConflict.class::isInstance).count()).isEqualTo(1);
    }

    @Test void accountAndJourneyDeletionCascadeAndDeleteWinningSaveLeavesNoOrphan() {
        UUID firstOwner = owner(); UUID firstTrip = completed(firstOwner, Journey.Kind.TRIP);
        journals(START.plusSeconds(90)).save(firstOwner, firstTrip, mutation("saved", "", 0));
        jdbc().update("DELETE FROM journey WHERE id = ?", firstTrip);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM journey_journal_annotation WHERE journey_id = ?", Long.class, firstTrip)).isZero();

        UUID secondOwner = owner(); UUID secondTrip = completed(secondOwner, Journey.Kind.TRIP);
        journals(START.plusSeconds(90)).save(secondOwner, secondTrip, mutation("saved", "", 0));
        jdbc().update("DELETE FROM routiqo_account WHERE id = ?", secondOwner);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM journey_journal_annotation WHERE journey_id = ?", Long.class, secondTrip)).isZero();

        UUID racingOwner = owner(); UUID racingTrip = completed(racingOwner, Journey.Kind.TRIP);
        JournalStore jdbcStore = new JdbcJournalStore(jdbc(), transactions());
        JournalStore deletingStore = new JournalStore() {
            @Override public Optional<JournalAnnotation> find(UUID actorId, UUID journeyId) { return jdbcStore.find(actorId, journeyId); }
            @Override public JournalAnnotation save(UUID actorId, UUID journeyId, JournalMutation mutation, Instant now) {
                jdbc().update("DELETE FROM journey WHERE id = ?", journeyId);
                return jdbcStore.save(actorId, journeyId, mutation, now);
            }
        };
        var service = new JournalService(journeys(START.plusSeconds(120)), deletingStore,
                Clock.fixed(START.plusSeconds(120), ZoneOffset.UTC));
        assertThatThrownBy(() -> service.save(racingOwner, racingTrip, mutation("lost race", "", 0)))
                .isInstanceOf(JourneyNotFound.class)
                .hasMessageNotContaining("lost race");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM journey_journal_annotation WHERE journey_id = ?", Long.class, racingTrip)).isZero();
    }

    @Test void versionExhaustionFailsClosed() {
        UUID owner = owner(); UUID id = completed(owner, Journey.Kind.TRIP);
        jdbc().update("""
            INSERT INTO journey_journal_annotation
                (journey_id, account_id, title, notes, version, updated_at, latest_mutation_id)
            VALUES (?, ?, '', '', ?, CURRENT_TIMESTAMP, ?)
            """, id, owner, JournalAnnotation.MAX_EXPECTED_VERSION, UUID.randomUUID());
        JournalAnnotation last = journals(START.plusSeconds(90)).save(owner, id,
                mutation("last safe write", "", JournalAnnotation.MAX_EXPECTED_VERSION)).annotation();
        assertThat(last.version()).isEqualTo(JournalAnnotation.MAX_VERSION);
        assertThatThrownBy(() -> mutation("cannot advance", "", JournalAnnotation.MAX_VERSION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc().queryForObject("SELECT version FROM journey_journal_annotation WHERE journey_id = ?", Long.class, id))
                .isEqualTo(JournalAnnotation.MAX_VERSION);
    }

    List<Object> race(Callable<JournalAnnotation> first, Callable<JournalAnnotation> second) throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<JournalAnnotation> task : List.of(first, second)) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
                try { return task.call(); } catch (JournalConflict conflict) { return conflict; }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
