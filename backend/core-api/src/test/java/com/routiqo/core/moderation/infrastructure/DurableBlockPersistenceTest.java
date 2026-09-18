package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.application.EnabledAccountPairAuthority;
import com.routiqo.core.identity.infrastructure.JdbcEnabledAccountPairAuthority;
import com.routiqo.core.moderation.application.BlockEdgeCapacityExceeded;
import com.routiqo.core.moderation.application.DurableBlockPolicyService;
import com.routiqo.core.moderation.application.DirectionalBlockParticipant;
import com.routiqo.core.moderation.domain.DirectionalBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class DurableBlockPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final UUID NIL = new UUID(0, 0);
    static { DATABASE.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired DurableBlockPolicyService configured;
    @Autowired EnabledAccountPairAuthority configuredAuthority;

    @Test
    void bilateralDecisionUsesBothDurableDirectionsAndRetainsUnblockedRevision() {
        UUID first = account();
        UUID second = account();
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.CLEAR);
        assertThat(configured.block(first, second, 0).revision()).isEqualTo(1);
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.EXCLUDED);
        assertThat(configured.block(second, first, 0).revision()).isEqualTo(1);
        assertThat(configured.unblock(first, second, 1).revision()).isEqualTo(2);
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.EXCLUDED);
        assertThat(configured.unblock(second, first, 1).revision()).isEqualTo(2);
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.CLEAR);
        assertThat(configured.block(first, second, 0).revision()).isEqualTo(3);
        assertThat(configured.block(first, second, 0).revision()).isEqualTo(4);
        assertThat(outgoing(first)).isEqualTo(1);
        assertThat(outgoing(second)).isEqualTo(1);
        assertDenied(() -> configured.unblock(first, second, 3));
        assertDenied(() -> configured.block(first, second, 5));
        assertDenied(() -> configured.block(first, second, -1));
    }

    @Test
    void initialExactUnblockRetainsRevisionWithoutExclusion() {
        UUID first = account();
        UUID second = account();
        DirectionalBlock unblocked = configured.unblock(first, second, 0);
        assertThat(unblocked.revision()).isEqualTo(1);
        assertThat(unblocked.blocked()).isFalse();
        assertThat(outgoing(first)).isEqualTo(1);
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.CLEAR);
        assertThat(configured.block(first, second, 0).revision()).isEqualTo(2);
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.EXCLUDED);
    }

    @Test
    void pairAuthorityRejectsInvalidMissingDisabledAndAmbientTransactions() {
        UUID first = account();
        UUID second = account();
        UUID missing = UUID.randomUUID();
        assertThat(configuredAuthority).isInstanceOf(JdbcEnabledAccountPairAuthority.class);
        for (UUID invalid : new UUID[] { null, NIL, first, missing }) {
            assertThatThrownBy(() -> configured.evaluate(first, invalid))
                    .isInstanceOf(SecurityException.class).hasNoCause();
        }
        jdbc().update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", second);
        assertThatThrownBy(() -> configured.block(first, second, 0))
                .isInstanceOf(SecurityException.class).hasNoCause();
        assertThatThrownBy(() -> configured.evaluate(first, second))
                .isInstanceOf(SecurityException.class).hasNoCause();
        assertThatThrownBy(() -> new TransactionTemplate(manager()).execute(
                status -> configured.evaluate(first, missing)))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test
    void oppositeDirectionAdaptersSerializeWithoutDeadlock() throws Exception {
        // PostgreSQL UUID order differs from Java's signed UUID comparison here.
        UUID first = account(UUID.fromString("7fffffff-ffff-ffff-ffff-fffffffffff1"));
        UUID second = account(UUID.fromString("80000000-0000-0000-0000-000000000002"));
        DurableBlockPolicyService one = service();
        DurableBlockPolicyService two = service();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> holdAccount(first, locked, release));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<DirectionalBlock>> writes = new ArrayList<>();
            writes.add(executor.submit(() -> {
                ready.countDown(); go.await(5, TimeUnit.SECONDS);
                return one.block(first, second, 0);
            }));
            writes.add(executor.submit(() -> {
                ready.countDown(); go.await(5, TimeUnit.SECONDS);
                return two.block(second, first, 0);
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(waitingCount("%routiqo_account%ORDER BY id FOR UPDATE%", 2))
                    .isGreaterThanOrEqualTo(2);
            // Both transactions must be waiting on the lower PostgreSQL UUID;
            // neither may have acquired the higher account first.
            UUID higherStillFree = new TransactionTemplate(manager()).execute(status ->
                    jdbc().queryForObject("""
                        SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE NOWAIT
                        """, UUID.class, second));
            assertThat(higherStillFree).isEqualTo(second);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            for (Future<DirectionalBlock> write : writes) {
                assertThat(write.get(10, TimeUnit.SECONDS).revision()).isEqualTo(1);
            }
        }
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.EXCLUDED);
        assertThat(outgoing(first)).isEqualTo(1);
        assertThat(outgoing(second)).isEqualTo(1);
    }

    @Test
    void finalOutgoingCapacitySlotSerializesCompetingTargets() throws Exception {
        UUID blocker = account();
        for (int index = 0; index < 99; index++) {
            configured.block(blocker, account(), 0);
        }
        UUID firstTarget = account();
        UUID secondTarget = account();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> holdAccount(blocker, locked, release));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<Object>> attempts = new ArrayList<>();
            for (UUID target : List.of(firstTarget, secondTarget)) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    try { return service().block(blocker, target, 0); }
                    catch (BlockEdgeCapacityExceeded full) { return full; }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(waitingCount("%routiqo_account%ORDER BY id FOR UPDATE%", 2))
                    .isGreaterThanOrEqualTo(2);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<Object> results = List.of(attempts.get(0).get(10, TimeUnit.SECONDS),
                    attempts.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn(DirectionalBlock.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(BlockEdgeCapacityExceeded.class::isInstance).hasSize(1);
        }
        assertThat(outgoing(blocker)).isEqualTo(100);
    }

    @Test
    void deletionCascadesBothDirectionsAndCannotBecomeClear() {
        UUID first = account();
        UUID second = account();
        configured.block(first, second, 0);
        configured.block(second, first, 0);
        jdbc().update("DELETE FROM routiqo_account WHERE id = ?", second);
        assertThat(outgoing(first)).isZero();
        assertThat(outgoing(second)).isZero();
        assertThatThrownBy(() -> configured.evaluate(first, second))
                .isInstanceOf(SecurityException.class).hasNoCause();
        assertThatThrownBy(() -> configured.block(first, second, 1))
                .isInstanceOf(SecurityException.class).hasNoCause();
    }

    @Test
    void deletionHoldingTargetLockMakesConcurrentBlockDenyWithoutOrphan() throws Exception {
        UUID first = account();
        UUID second = account();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = executor.submit(() -> new TransactionTemplate(manager())
                    .executeWithoutResult(status -> {
                        jdbc().queryForObject("""
                            SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE
                            """, UUID.class, second);
                        locked.countDown();
                        await(release);
                        jdbc().update("DELETE FROM routiqo_account WHERE id = ?", second);
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> block = executor.submit(() -> {
                try { return service().block(first, second, 0); }
                catch (SecurityException denied) { return denied; }
            });
            assertThat(waitingFor("%routiqo_account%ORDER BY id FOR UPDATE%"))
                    .isTrue();
            release.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(block.get(10, TimeUnit.SECONDS)).isInstanceOf(SecurityException.class);
        }
        assertThat(outgoing(first)).isZero();
    }

    @Test
    void failedEdgeInsertRollsBackAndSanitizesPrivateSqlError() {
        UUID first = account();
        UUID second = account();
        jdbc().execute("""
            CREATE FUNCTION live_block_test_failure() RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'private block fixture'; END $$
            """);
        jdbc().execute("""
            CREATE TRIGGER live_block_test_failure BEFORE INSERT ON live_block_edge
            FOR EACH ROW EXECUTE FUNCTION live_block_test_failure()
            """);
        try {
            assertThatThrownBy(() -> configured.block(first, second, 0))
                    .isInstanceOf(AccountWriteUnavailable.class).hasNoCause()
                    .hasMessageNotContaining("private block fixture")
                    .hasMessageNotContaining(first.toString())
                    .hasMessageNotContaining(second.toString());
        } finally {
            jdbc().execute("DROP TRIGGER live_block_test_failure ON live_block_edge");
            jdbc().execute("DROP FUNCTION live_block_test_failure()");
        }
        assertThat(outgoing(first)).isZero();
        assertThat(configured.evaluate(first, second))
                .isEqualTo(DurableBlockPolicyService.PairDecision.CLEAR);
    }

    @Test
    void outgoingCapacityRetainsUnblockedRowsAndAllowsExistingRevocation() {
        UUID blocker = account();
        List<UUID> targets = new ArrayList<>();
        for (int index = 0; index < 101; index++) targets.add(account());
        for (int index = 0; index < 100; index++) {
            assertThat(configured.block(blocker, targets.get(index), 0).revision()).isEqualTo(1);
        }
        assertThat(outgoing(blocker)).isEqualTo(100);
        assertThat(configured.unblock(blocker, targets.getFirst(), 1).revision()).isEqualTo(2);
        assertThat(outgoing(blocker)).isEqualTo(100);
        assertThatThrownBy(() -> configured.block(blocker, targets.get(100), 0))
                .isInstanceOf(BlockEdgeCapacityExceeded.class).hasNoCause()
                .hasMessage("Block edge capacity reached");
        assertThatThrownBy(() -> configured.unblock(blocker, targets.get(100), 0))
                .isInstanceOf(BlockEdgeCapacityExceeded.class).hasNoCause();
        assertThat(outgoing(blocker)).isEqualTo(100);
        assertThat(configured.block(blocker, targets.getFirst(), 0).revision()).isEqualTo(3);
        jdbc().update("""
            UPDATE live_block_edge SET revision = ?, blocked = TRUE
            WHERE blocker_id = ? AND target_id = ?
            """, Long.MAX_VALUE - 1, blocker, targets.getFirst());
        assertThat(configured.block(blocker, targets.getFirst(), 0).revision())
                .isEqualTo(Long.MAX_VALUE);
        assertThat(configured.block(blocker, targets.getFirst(), 0).revision())
                .isEqualTo(Long.MAX_VALUE);
        assertDenied(() -> configured.unblock(blocker, targets.getFirst(), Long.MAX_VALUE));
        assertThatThrownBy(() -> jdbc().update("""
            UPDATE live_block_edge SET blocked = FALSE
            WHERE blocker_id = ? AND target_id = ?
            """, blocker, targets.getFirst())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rawParticipantEnforcesExactCasAndCannotJoinWithoutTransaction() {
        UUID first = account();
        UUID second = account();
        var raw = new JdbcDirectionalBlockParticipant(jdbc());
        assertThatThrownBy(() -> raw.read(first, second))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThatThrownBy(() -> configuredAuthority.withEnabledPair(first, second, () -> {
            DirectionalBlock prior = raw.read(first, second);
            raw.replace(prior, new DirectionalBlock(first, second, 0, true));
            return null;
        })).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(outgoing(first)).isZero();
    }

    @Test
    void missingOrMismatchedPairSnapshotsFailClosed() {
        UUID first = account();
        UUID second = account();
        for (DirectionalBlock snapshot : new DirectionalBlock[] {
                null, DirectionalBlock.initial(second, first) }) {
            DirectionalBlockParticipant invalid = new DirectionalBlockParticipant() {
                @Override public DirectionalBlock read(UUID blockerId, UUID targetId) {
                    return snapshot;
                }
                @Override public int outgoingCount(UUID blockerId) { throw new AssertionError(); }
                @Override public void replace(DirectionalBlock prior, DirectionalBlock updated) {
                    throw new AssertionError();
                }
            };
            var service = new DurableBlockPolicyService(configuredAuthority, invalid);
            assertDenied(() -> service.evaluate(first, second));
            assertDenied(() -> service.block(first, second, 0));
        }
        assertThat(outgoing(first)).isZero();
    }

    private DurableBlockPolicyService service() {
        return new DurableBlockPolicyService(
                new JdbcEnabledAccountPairAuthority(jdbc(), manager()),
                new JdbcDirectionalBlockParticipant(jdbc()));
    }
    private UUID account() {
        return account(UUID.randomUUID());
    }
    private UUID account(UUID id) {
        jdbc().update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                id, id.toString());
        return id;
    }
    private int outgoing(UUID blocker) {
        return jdbc().queryForObject("SELECT count(*) FROM live_block_edge WHERE blocker_id = ?",
                Integer.class, blocker);
    }
    private boolean waitingFor(String pattern) throws InterruptedException {
        return waitingCount(pattern, 1) >= 1;
    }
    private int waitingCount(String pattern, int minimum) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc().queryForObject("""
                SELECT count(*) FROM pg_stat_activity activity
                WHERE activity.datname = current_database()
                  AND cardinality(pg_blocking_pids(activity.pid)) > 0
                  AND activity.query LIKE ?
                """, Integer.class, pattern);
            if (waiting >= minimum) return waiting;
            Thread.sleep(20);
        }
        return 0;
    }
    private void holdAccount(UUID actorId, CountDownLatch locked, CountDownLatch release) {
        new TransactionTemplate(manager()).executeWithoutResult(status -> {
            jdbc().queryForObject("""
                SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE
                """, UUID.class, actorId);
            locked.countDown();
            await(release);
        });
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    private DataSourceTransactionManager manager() { return new DataSourceTransactionManager(dataSource); }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }
    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalStateException.class).hasNoCause();
    }
}
