package com.routiqo.core.publiclive.privacy;

import java.util.UUID;
import java.util.concurrent.Executors;
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
class JdbcPilotPersonClaimStoreTest {
    private static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test void exactReplaySurvivesAccountDeletionButDifferentRequestOrKeyCannotRefund() {
        UUID pilot = activePilot();
        UUID account = UUID.randomUUID(), person = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                account, "pilot-claim-" + account);
        var firstReplica = new JdbcPilotPersonClaimStore(jdbc);
        var secondReplica = new JdbcPilotPersonClaimStore(jdbc);
        var tx = new TransactionTemplate(manager);
        PilotPersonClaimStore.Outcome firstClaim = tx.execute(status -> firstReplica.claim(
                pilot, person, request, "anchor:traffic:slow:window"));
        assertThat(firstClaim).isEqualTo(PilotPersonClaimStore.Outcome.NEW);
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", account);
        PilotPersonClaimStore.Outcome replay = tx.execute(status -> secondReplica.claim(
                pilot, person, request, "anchor:traffic:slow:window"));
        assertThat(replay).isEqualTo(PilotPersonClaimStore.Outcome.REPLAY);
        assertThatThrownBy(() -> tx.execute(status -> secondReplica.claim(pilot, person,
                UUID.randomUUID(), "anchor:traffic:slow:window")))
                .isInstanceOf(PilotPersonClaimConflict.class);
        assertThatThrownBy(() -> tx.execute(status -> secondReplica.claim(pilot, person,
                request, "anchor:traffic:fast:window")))
                .isInstanceOf(PilotPersonClaimConflict.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public_live_person_claim
            WHERE pilot_id = ? AND person_ref = ?
            """, Integer.class, pilot, person)).isEqualTo(1);
    }

    @Test void concurrentReplicasReserveExactlyOnePersonSlot() throws Exception {
        UUID pilot = activePilot(), person = UUID.randomUUID();
        UUID firstRequest = UUID.randomUUID(), secondRequest = UUID.randomUUID();
        var firstReplica = new JdbcPilotPersonClaimStore(jdbc);
        var secondReplica = new JdbcPilotPersonClaimStore(jdbc);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> attempt(firstReplica, pilot, person,
                    firstRequest, "a:traffic:slow:w"));
            var second = executor.submit(() -> attempt(secondReplica, pilot, person,
                    secondRequest, "b:traffic:slow:w"));
            assertThat(first.get() + second.get()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM public_live_person_claim
            WHERE pilot_id = ? AND person_ref = ?
            """, Integer.class, pilot, person)).isEqualTo(1);
    }

    @Test void retainedExactReplayWorksAfterPilotCloseWithoutNewClaims() {
        UUID closed = pilotAt("CURRENT_TIMESTAMP - INTERVAL '31 days'");
        UUID person = UUID.randomUUID(), request = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public_live_person_claim(pilot_id, person_ref, claim_request_id,
                public_key, claimed_at)
            SELECT ?, ?, ?, 'a:traffic:slow:w', starts_at + INTERVAL '1 hour'
            FROM public_live_pilot WHERE pilot_id = ?
            """, closed, person, request, closed);
        var store = new JdbcPilotPersonClaimStore(jdbc);
        var tx = new TransactionTemplate(manager);
        PilotPersonClaimStore.Outcome replay = tx.execute(status -> store.claim(
                closed, person, request, "a:traffic:slow:w"));
        assertThat(replay).isEqualTo(PilotPersonClaimStore.Outcome.REPLAY);
        assertThatThrownBy(() -> tx.execute(status -> store.claim(closed, person,
                UUID.randomUUID(), "a:traffic:slow:w")))
                .isInstanceOf(PilotPersonClaimConflict.class);
        assertThatThrownBy(() -> tx.execute(status -> store.claim(closed,
                UUID.randomUUID(), UUID.randomUUID(), "a:traffic:slow:w")))
                .isInstanceOf(PilotPersonClaimConflict.class);
    }

    @Test void finitePilotWindowAndBoundedCleanupAreEnforced() {
        UUID expired = pilotAt("CURRENT_TIMESTAMP - INTERVAL '61 days'");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE public_live_pilot SET ends_at = ends_at + INTERVAL '1 hour' WHERE pilot_id = ?",
                expired)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM public_live_pilot WHERE pilot_id = ?", expired))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID person = UUID.randomUUID();
        var store = new JdbcPilotPersonClaimStore(jdbc);
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> store.claim(expired, person,
                UUID.randomUUID(), "a:traffic:slow:w")))
                .isInstanceOf(PilotPersonClaimConflict.class);
        for (int index = 0; index < 101; index++) {
            jdbc.update("""
                INSERT INTO public_live_person_claim(pilot_id, person_ref,
                    claim_request_id, public_key, claimed_at)
                VALUES (?, ?, ?, 'a:traffic:slow:w',
                    CURRENT_TIMESTAMP - INTERVAL '61 days')
                """, expired, UUID.randomUUID(), UUID.randomUUID());
        }
        Integer firstBatch = tx.execute(status -> store.deleteExpired());
        Integer secondBatch = tx.execute(status -> store.deleteExpired());
        Integer lastBatch = tx.execute(status -> store.deleteExpired());
        assertThat(firstBatch).isEqualTo(100);
        assertThat(secondBatch).isEqualTo(1);
        assertThat(lastBatch).isZero();
    }

    private int attempt(JdbcPilotPersonClaimStore store, UUID pilot, UUID person,
            UUID request, String key) {
        try {
            new TransactionTemplate(manager).execute(status -> store.claim(pilot, person,
                    request, key));
            return 1;
        } catch (PilotPersonClaimConflict expected) {
            return 0;
        }
    }

    private UUID activePilot() { return pilotAt("CURRENT_TIMESTAMP - INTERVAL '1 day'"); }

    private UUID pilotAt(String startExpression) {
        UUID pilot = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public_live_pilot(pilot_id, starts_at, ends_at, retain_until)
            SELECT ?, start_at, start_at + INTERVAL '720 hours',
                   start_at + INTERVAL '1440 hours'
            FROM (SELECT %s AS start_at) fixed
            """.formatted(startExpression), pilot);
        return pilot;
    }
}
