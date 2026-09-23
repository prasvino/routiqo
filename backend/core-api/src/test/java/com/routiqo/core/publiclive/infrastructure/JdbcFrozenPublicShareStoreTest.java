package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.publiclive.application.FrozenPublicShareConflict;
import com.routiqo.core.publiclive.privacy.JdbcPilotPersonClaimStore;
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
class JdbcFrozenPublicShareStoreTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test void exactOwnerRecoveryIsDurableAndAccountDeletionDoesNotRefundClaim() {
        UUID pilot = pilot(), person = UUID.randomUUID();
        UUID actor = UUID.randomUUID(), journey = UUID.randomUUID();
        UUID command = UUID.randomUUID(), request = UUID.randomUUID();
        UUID anchor = UUID.randomUUID(), version = UUID.randomUUID();
        manifest(pilot, version, anchor);
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, "frozen-" + actor);
        var first = store();
        var second = store();
        String key = "v2:" + anchor + ":TRAFFIC_SLOW:1000000000";
        var tx = new TransactionTemplate(manager);
        var accepted = tx.execute(status -> first.freeze(pilot, person, actor,
                journey, command, request, key));
        assertThat(accepted).isNotNull();
        assertThat(accepted.publicKey()).isEqualTo(key);
        var recovered = tx.execute(status -> second.findOwner(pilot, actor,
                journey, command, request));
        assertThat(recovered).isEqualTo(accepted);
        var replay = tx.execute(status -> second.freeze(pilot, person, actor,
                journey, command, request, key));
        assertThat(replay).isEqualTo(accepted);
        assertThatThrownBy(() -> tx.execute(status -> second.findOwner(pilot, actor,
                journey, command, UUID.randomUUID())))
                .isInstanceOf(FrozenPublicShareConflict.class);
        assertThatThrownBy(() -> tx.execute(status -> second.findOwner(pilot, actor,
                UUID.randomUUID(), command, request)))
                .isInstanceOf(FrozenPublicShareConflict.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM public_live_frozen_share_v2 WHERE pilot_id = ?",
                pilot)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", actor);
        var afterDeletion = tx.execute(status -> second.findOwner(pilot, actor,
                journey, command, request));
        assertThat(afterDeletion).isNull();
        assertThatThrownBy(() -> tx.execute(status -> second.freeze(pilot, person,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), key))).isInstanceOf(FrozenPublicShareConflict.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_person_claim WHERE pilot_id = ?",
                Integer.class, pilot)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_frozen_share_v2 WHERE pilot_id = ?",
                Integer.class, pilot)).isZero();
    }

    @Test void rollbackRemovesClaimAndFrozenRowTogether() {
        UUID pilot = pilot(), person = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        manifest(pilot, UUID.randomUUID(), anchor);
        UUID actor = account();
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> {
            store().freeze(pilot, person, actor, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(),
                    "v2:" + anchor + ":TRAFFIC_SLOW:1000000000");
            throw new IllegalStateException("abort");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_person_claim WHERE pilot_id = ?",
                Integer.class, pilot)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_frozen_share_v2 WHERE pilot_id = ?",
                Integer.class, pilot)).isZero();
    }

    @Test void twoReplicasAndAccountsForSamePersonCannotBothFreeze() throws Exception {
        UUID pilot = pilot(), person = UUID.randomUUID(), anchor = UUID.randomUUID();
        manifest(pilot, UUID.randomUUID(), anchor);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> attempt(pilot, person, anchor));
            var second = executor.submit(() -> attempt(pilot, person, anchor));
            assertThat(first.get() + second.get()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_person_claim WHERE pilot_id = ?",
                Integer.class, pilot)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_live_frozen_share_v2 WHERE pilot_id = ?",
                Integer.class, pilot)).isEqualTo(1);
    }

    @Test void unalignedPilotCannotAcquireFrozenManifest() {
        UUID pilot = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public_live_pilot(pilot_id, starts_at, ends_at, retain_until)
            VALUES (?, TIMESTAMPTZ '2026-09-23 10:02:00+00',
                TIMESTAMPTZ '2026-10-23 10:02:00+00',
                TIMESTAMPTZ '2026-11-22 10:02:00+00')
            """, pilot);
        assertThatThrownBy(() -> manifest(pilot, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private int attempt(UUID pilot, UUID person, UUID anchor) {
        try {
            UUID actor = account();
            new TransactionTemplate(manager).execute(status -> store().freeze(
                    pilot, person, actor, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(),
                    "v2:" + anchor + ":TRAFFIC_SLOW:1000000000"));
            return 1;
        } catch (FrozenPublicShareConflict expected) {
            return 0;
        }
    }

    private JdbcFrozenPublicShareStore store() {
        return new JdbcFrozenPublicShareStore(jdbc, new JdbcPilotPersonClaimStore(jdbc));
    }

    private UUID account() {
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, "frozen-" + actor);
        return actor;
    }

    private UUID pilot() {
        UUID pilot = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO public_live_pilot(pilot_id, starts_at, ends_at, retain_until)
            SELECT ?, start_at, start_at + INTERVAL '720 hours',
                start_at + INTERVAL '1440 hours'
            FROM (SELECT date_bin('5 minutes', clock_timestamp(),
                TIMESTAMPTZ '1970-01-01') - INTERVAL '1 day' AS start_at) fixed
            """, pilot);
        return pilot;
    }

    private void manifest(UUID pilot, UUID version, UUID anchor) {
        jdbc.update("""
            INSERT INTO public_live_pilot_manifest(pilot_id, catalog_version, anchor_ids)
            VALUES (?, ?, ARRAY[?]::UUID[])
            """, pilot, version, anchor);
    }
}
