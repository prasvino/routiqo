package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.publiclive.application.PublicSignalIntentConflict;
import com.routiqo.core.publiclive.application.PublicSignalIntentStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
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
class JdbcPublicSignalIntentStoreTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant RECEIVED = Instant.parse("2026-09-23T10:00:30Z");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test void exactRetryCannotReviveStoppedShareOrReuseOnePersonWindow() {
        JdbcPublicSignalIntentStore store = new JdbcPublicSignalIntentStore(jdbc);
        UUID account = UUID.randomUUID(), journey = UUID.randomUUID();
        UUID command = UUID.randomUUID(), person = UUID.randomUUID(), anchor = UUID.randomUUID();
        insertReceipt(account, journey, command, anchor);
        var intent = intent(account, journey, command, person, anchor, UUID.randomUUID());
        var tx = new TransactionTemplate(manager);
        PublicSignalIntentStore.Intent saved = tx.execute(status -> store.share(intent));
        assertThat(saved).isEqualTo(intent);
        assertThat(tx.execute(status -> store.share(intent)).shareRequestId())
                .isEqualTo(intent.shareRequestId());
        assertThatThrownBy(() -> tx.execute(status -> store.share(intent(account, journey,
                command, person, anchor, UUID.randomUUID()))))
                .isInstanceOf(PublicSignalIntentConflict.class);

        UUID secondAccount = UUID.randomUUID(), secondJourney = UUID.randomUUID();
        UUID secondCommand = UUID.randomUUID();
        insertReceipt(secondAccount, secondJourney, secondCommand, anchor);
        assertThatThrownBy(() -> tx.execute(status -> store.share(intent(secondAccount, secondJourney,
                secondCommand, person, anchor, UUID.randomUUID()))))
                .isInstanceOf(PublicSignalIntentConflict.class);

        PublicSignalIntentStore.State stopped = tx.execute(status -> store.stop(account,
                journey, command, RECEIVED.plusSeconds(90)));
        assertThat(stopped).isEqualTo(PublicSignalIntentStore.State.STOPPED);
        stopped = tx.execute(status -> store.stop(account, journey, command,
                RECEIVED.plusSeconds(91)));
        assertThat(stopped).isEqualTo(PublicSignalIntentStore.State.STOPPED);
        assertThatThrownBy(() -> tx.execute(status -> store.share(intent)))
                .isInstanceOf(PublicSignalIntentConflict.class);
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", account);
        assertThatThrownBy(() -> tx.execute(status -> store.share(intent(secondAccount,
                secondJourney, secondCommand, person, anchor, UUID.randomUUID()))))
                .isInstanceOf(PublicSignalIntentConflict.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_person_window_slot WHERE person_ref = ?",
                Integer.class, person)).isEqualTo(1);
        var cleanup = new JdbcPublicSignalIntentCleanup(jdbc, manager,
                Clock.fixed(RECEIVED.plusSeconds(25 * 60 * 60), ZoneOffset.UTC));
        assertThat(cleanup.deleteExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_person_window_slot WHERE person_ref = ?",
                Integer.class, person)).isZero();
    }

    @Test void ownerRecoveryIncludesCompletedJourneyAndStoppedHandleWithoutForeignData() {
        JdbcPublicSignalIntentStore store = new JdbcPublicSignalIntentStore(jdbc);
        UUID account = UUID.randomUUID(), journey = UUID.randomUUID();
        UUID command = UUID.randomUUID(), person = UUID.randomUUID(), anchor = UUID.randomUUID();
        insertReceipt(account, journey, command, anchor);
        UUID foreign = UUID.randomUUID(), foreignJourney = UUID.randomUUID();
        UUID foreignCommand = UUID.randomUUID();
        insertReceipt(foreign, foreignJourney, foreignCommand, UUID.randomUUID());
        var tx = new TransactionTemplate(manager);
        tx.execute(status -> store.share(intent(account, journey, command, person,
                anchor, UUID.randomUUID())));
        tx.execute(status -> store.share(intent(foreign, foreignJourney, foreignCommand,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        jdbc.update("UPDATE journey SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.from(RECEIVED.plusSeconds(90)), journey);
        var page = tx.execute(status -> store.list(account,
                RECEIVED.minusSeconds(24 * 60 * 60), null));
        assertThat(page.handles()).containsExactly(new PublicSignalIntentStore.Handle(
                journey, command, PublicSignalIntentStore.State.ACTIVE,
                RECEIVED.plusSeconds(30)));
        assertThat(page.nextCursor()).isNull();
        tx.execute(status -> store.stop(account, journey, command, RECEIVED.plusSeconds(100)));
        assertThat(tx.execute(status -> store.list(account,
                RECEIVED.minusSeconds(24 * 60 * 60), null)).handles().getFirst().state())
                .isEqualTo(PublicSignalIntentStore.State.STOPPED);
        assertThat(tx.execute(status -> store.list(account,
                RECEIVED.plusSeconds(24 * 60 * 60), null)).handles()).isEmpty();
    }

    @Test void recoveryPaginatesWithoutDroppingEqualTimestampHandles() {
        JdbcPublicSignalIntentStore store = new JdbcPublicSignalIntentStore(jdbc);
        UUID account = UUID.randomUUID(), journey = UUID.randomUUID();
        var tx = new TransactionTemplate(manager);
        for (int index = 0; index < 101; index++) {
            UUID command = UUID.randomUUID(), anchor = UUID.randomUUID();
            insertReceipt(account, journey, command, anchor);
            tx.execute(status -> store.share(intent(account, journey, command,
                    UUID.randomUUID(), anchor, UUID.randomUUID())));
        }
        Instant since = RECEIVED.minusSeconds(24 * 60 * 60);
        var first = tx.execute(status -> store.list(account, since, null));
        assertThat(first.handles()).hasSize(100);
        assertThat(first.nextCursor()).isNotNull();
        var second = tx.execute(status -> store.list(account, since, first.nextCursor()));
        assertThat(second.handles()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        assertThat(first.handles().stream().map(PublicSignalIntentStore.Handle::commandId))
                .doesNotContain(second.handles().getFirst().commandId());
    }

    private void insertReceipt(UUID actor, UUID journey, UUID command, UUID anchor) {
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                actor, "public-intent-" + actor);
        jdbc.update("""
            INSERT INTO journey(id, owner_id, kind, status, started_at)
            VALUES (?, ?, 'TRIP', 'ACTIVE', ?)
            ON CONFLICT (id) DO NOTHING
            """, journey, actor, Timestamp.from(RECEIVED.minusSeconds(300)));
        jdbc.update("""
            INSERT INTO quick_signal_receipt(actor_id, command_id, journey_id, anchor_id,
                signal_value, category, consent_generation, context_id, route_revision,
                received_at, evidence_expires_at, retain_until, state)
            VALUES (?, ?, ?, ?, 'TRAFFIC_SLOW', 'TRAFFIC', 7, ?, 3, ?, ?, ?, 'ACTIVE')
            """, actor, command, journey, anchor, UUID.randomUUID(),
                Timestamp.from(RECEIVED), Timestamp.from(RECEIVED.plusSeconds(600)),
                Timestamp.from(RECEIVED.plusSeconds(3600)));
    }

    private static PublicSignalIntentStore.Intent intent(UUID actor, UUID journey, UUID command,
            UUID person, UUID anchor, UUID request) {
        return new PublicSignalIntentStore.Intent(actor, command, journey, person, 2, 0, anchor,
                "TRAFFIC", "TRAFFIC_SLOW", Instant.parse("2026-09-23T10:00:00Z"),
                RECEIVED, RECEIVED.plusSeconds(600), RECEIVED.plusSeconds(30), request,
                PublicSignalIntentStore.State.ACTIVE);
    }
}
