package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.publiclive.application.CommunityTrafficCandidateStore;
import com.routiqo.core.publiclive.application.CommunityTrafficConflict;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class JdbcCommunityTrafficCandidateStoreTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant WINDOW = com.routiqo.core.publiclive.RecentTrafficWindow.now();
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test void oneAccountWindowAcrossValuesAndAnchorsDebitsOnlyCommittedNewCandidate() {
        var store = new JdbcCommunityTrafficCandidateStore(jdbc);
        var tx = new TransactionTemplate(manager);
        UUID actor = account();
        var first = candidate(actor, WINDOW, "TRAFFIC_SLOW");
        CommunityTrafficCandidateStore.Candidate inserted = tx.execute(status -> store.insert(first));
        CommunityTrafficCandidateStore.Candidate recovered =
                tx.execute(status -> store.find(actor, first.commandId()));
        assertThat(inserted).isEqualTo(first);
        assertThat(recovered).isEqualTo(first);
        var conflicting = candidate(actor, WINDOW, "TRAFFIC_STOPPED");
        assertThatThrownBy(() -> tx.execute(status -> store.insert(conflicting)))
                .isInstanceOf(CommunityTrafficConflict.class);
        assertThat(jdbc.queryForObject("""
                SELECT used FROM community_traffic_daily_debit_v3
                WHERE actor_id = ? AND utc_day = ?
                """, Integer.class, actor, java.sql.Date.valueOf(WINDOW.atOffset(java.time.ZoneOffset.UTC).toLocalDate()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM community_traffic_candidate_v3 WHERE actor_id = ?
                """, Integer.class, actor)).isEqualTo(1);
    }

    @Test void dailyCapAndStopRecoveryAreOwnerBound() {
        var store = new JdbcCommunityTrafficCandidateStore(jdbc);
        var tx = new TransactionTemplate(manager);
        UUID actor = account(), stranger = account();
        var first = candidate(actor, WINDOW, "TRAFFIC_SLOW");
        for (int index = 0; index < 12; index++) {
            var submitted = index == 0 ? first : candidate(actor,
                    WINDOW.plusSeconds(index * 300L), "TRAFFIC_SLOW");
            tx.execute(status -> store.insert(submitted));
        }
        var denied = candidate(actor, WINDOW.plusSeconds(12 * 300L), "TRAFFIC_SLOW");
        assertThatThrownBy(() -> tx.execute(status -> store.insert(denied)))
                .isInstanceOf(CommunityTrafficConflict.class);
        CommunityTrafficCandidateStore.Candidate recovered =
                tx.execute(status -> store.find(actor, first.commandId()));
        assertThat(recovered).isEqualTo(first);
        var stopped = tx.execute(status -> store.stop(actor, first.journeyId(),
                first.commandId(), WINDOW.plusSeconds(240)));
        assertThat(stopped.state()).isEqualTo(CommunityTrafficCandidateStore.State.STOPPED);
        assertThat(tx.execute(status -> store.stop(actor, first.journeyId(),
                first.commandId(), WINDOW.plusSeconds(241))).state())
                .isEqualTo(CommunityTrafficCandidateStore.State.STOPPED);
        assertThatThrownBy(() -> tx.execute(status -> store.stop(stranger, first.journeyId(),
                first.commandId(), WINDOW.plusSeconds(242)))).isInstanceOf(SecurityException.class);
        var foreign = tx.execute(status -> store.recent(stranger, WINDOW.minusSeconds(1)));
        var owner = tx.execute(status -> store.recent(actor, WINDOW.minusSeconds(1)));
        assertThat(foreign).isEmpty();
        assertThat(owner).hasSize(12);
        assertThat(jdbc.queryForObject("""
                SELECT used FROM community_traffic_daily_debit_v3
                WHERE actor_id = ? AND utc_day = ?
                """, Integer.class, actor, java.sql.Date.valueOf(WINDOW.atOffset(java.time.ZoneOffset.UTC).toLocalDate()))).isEqualTo(12);
    }

    // --- ADR 0065 pilot suites: contribution isolation and daily quota under concurrency ---

    private String attempt(CommunityTrafficCandidateStore store, TransactionTemplate tx,
            CommunityTrafficCandidateStore.Candidate candidate, java.util.concurrent.CountDownLatch start) {
        try {
            start.await(5, java.util.concurrent.TimeUnit.SECONDS);
            tx.execute(status -> store.insert(candidate));
            return "accepted";
        } catch (CommunityTrafficConflict conflict) {
            return "refused";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private int used(UUID actor, Instant day) {
        return jdbc.queryForObject("SELECT used FROM community_traffic_daily_debit_v3 WHERE actor_id = ? AND utc_day = ?",
                Integer.class, actor, java.sql.Date.valueOf(day.atOffset(java.time.ZoneOffset.UTC).toLocalDate()));
    }

    @Test void concurrentSharesAtTheDailyLimitAdmitExactlyOneWithoutOverDebit() throws Exception {
        var store = new JdbcCommunityTrafficCandidateStore(jdbc);
        var tx = new TransactionTemplate(manager);
        UUID actor = account();
        for (int index = 0; index < 11; index++) {
            var submitted = candidate(actor, WINDOW.plusSeconds(index * 300L), "TRAFFIC_SLOW");
            tx.execute(status -> store.insert(submitted));
        }
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 11; index < 14; index++) {
                var contender = candidate(actor, WINDOW.plusSeconds(index * 300L), "TRAFFIC_SLOW");
                results.add(pool.submit(() -> attempt(store, tx, contender, start)));
            }
            start.countDown();
            var outcomes = new java.util.ArrayList<String>();
            for (var result : results) outcomes.add(result.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("accepted", "refused", "refused");
        }
        assertThat(used(actor, WINDOW)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_candidate_v3 WHERE actor_id = ?",
                Integer.class, actor)).isEqualTo(12);
    }

    @Test void concurrentSharesForOneWindowKeepOneCandidateAndOneDebit() throws Exception {
        var store = new JdbcCommunityTrafficCandidateStore(jdbc);
        var tx = new TransactionTemplate(manager);
        UUID actor = account();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (String value : java.util.List.of("TRAFFIC_SLOW", "TRAFFIC_STOPPED", "TRAFFIC_MOVING")) {
                var contender = candidate(actor, WINDOW, value);
                results.add(pool.submit(() -> attempt(store, tx, contender, start)));
            }
            start.countDown();
            var outcomes = new java.util.ArrayList<String>();
            for (var result : results) outcomes.add(result.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("accepted", "refused", "refused");
        }
        assertThat(used(actor, WINDOW)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_candidate_v3 WHERE actor_id = ?",
                Integer.class, actor)).isEqualTo(1);
    }

    @Test void dailyLimitIsPerUtcDay() {
        var store = new JdbcCommunityTrafficCandidateStore(jdbc);
        var tx = new TransactionTemplate(manager);
        UUID actor = account();
        // The last window of one UTC day and the first of the next, a day before the fixture window.
        Instant midnight = WINDOW.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        var lastOfDay = candidate(actor, midnight.minusSeconds(300), "TRAFFIC_SLOW");
        var firstOfNext = candidate(actor, midnight, "TRAFFIC_SLOW");
        tx.execute(status -> store.insert(lastOfDay));
        tx.execute(status -> store.insert(firstOfNext));
        assertThat(used(actor, lastOfDay.acceptedAt())).isEqualTo(1);
        assertThat(used(actor, firstOfNext.acceptedAt())).isEqualTo(1);
    }

    private UUID account() {
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, "v3-" + actor);
        return actor;
    }

    private static CommunityTrafficCandidateStore.Candidate candidate(UUID actor,
            Instant window, String value) {
        return new CommunityTrafficCandidateStore.Candidate(UUID.randomUUID(), actor,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                value, window, UUID.randomUUID(), 7, window.plusSeconds(20),
                window.plusSeconds(30), window.plusSeconds(300 + 24 * 3600),
                CommunityTrafficCandidateStore.State.ACTIVE);
    }
}
