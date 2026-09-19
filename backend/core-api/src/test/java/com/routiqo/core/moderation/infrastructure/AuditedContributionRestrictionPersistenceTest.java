package com.routiqo.core.moderation.infrastructure;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.infrastructure.JdbcEnabledAccountPairAuthority;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Action;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Command;
import com.routiqo.core.moderation.application.AuditedContributionRestrictionService.Reason;
import com.routiqo.core.moderation.application.ContributionRestrictionAuditCleanup;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
class AuditedContributionRestrictionPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-09-19T06:00:00.123456Z");
    static { DATABASE.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired ContributionRestrictionAuditCleanup configuredCleanup;

    @Test
    void requiresCurrentActionScopedGrantEvenForAnExactReplay() {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        grant(operator, Action.RESTORE, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        Command restrict = command(target, 0, Action.RESTRICT, Reason.HARASSMENT);

        var first = service(NOW).execute(operator, restrict);
        var restored = service(NOW.plusSeconds(1)).execute(operator,
                command(target, 1, Action.RESTORE, Reason.APPEAL_UPHELD));
        var replay = service(NOW.plusSeconds(2)).execute(operator, restrict);

        assertThat(first.afterRevision()).isEqualTo(1);
        assertThat(restored.afterRevision()).isEqualTo(2);
        assertThat(replay).isEqualTo(first);
        assertThat(restrictionRevision(target)).isEqualTo(2);
        assertThat(count("moderation_contribution_audit", operator)).isEqualTo(2);
        assertThat(count("moderation_restriction_action_slot", operator)).isEqualTo(2);
        assertDenied(() -> service(NOW.plusSeconds(2)).execute(operator,
                new Command(restrict.requestId(), target, 0, Action.RESTRICT,
                        Reason.SPAM_MANIPULATION)));

        jdbc().update("UPDATE moderation_restriction_action_slot SET used_at = ? WHERE operator_id = ?",
                Timestamp.from(NOW.plusSeconds(10)), operator);
        assertThat(service(NOW.plusSeconds(3)).execute(operator, restrict)).isEqualTo(first);
        UUID another = account();
        assertDenied(() -> service(NOW.plusSeconds(3)).execute(operator,
                command(another, 0, Action.RESTRICT, Reason.HARASSMENT)));

        jdbc().update("DELETE FROM moderation_operator_grant WHERE operator_id = ? AND permission = 'restrict'",
                operator);
        assertDenied(() -> service(NOW.plusSeconds(3)).execute(operator, restrict));
    }

    @Test
    void grantAbsenceScopeExpiryFutureIssueAndSelfFailClosed() {
        UUID operator = account();
        UUID target = account();
        assertDenied(() -> service(NOW).execute(operator,
                command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));

        grant(operator, Action.RESTORE, NOW.minusSeconds(1), NOW.plusSeconds(60));
        assertDenied(() -> service(NOW).execute(operator,
                command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));
        jdbc().update("DELETE FROM moderation_operator_grant WHERE operator_id = ?", operator);
        grant(operator, Action.RESTRICT, NOW.minusSeconds(60), NOW);
        assertDenied(() -> service(NOW).execute(operator,
                command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));
        jdbc().update("DELETE FROM moderation_operator_grant WHERE operator_id = ?", operator);
        grant(operator, Action.RESTRICT, NOW.plusSeconds(1), NOW.plusSeconds(60));
        assertDenied(() -> service(NOW).execute(operator,
                command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));
        assertDenied(() -> service(NOW).execute(operator,
                command(operator, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));
        jdbc().update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", target);
        assertDenied(() -> service(NOW.plusSeconds(2)).execute(operator,
                command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));
        assertThat(count("moderation_contribution_audit", operator)).isZero();
    }

    @Test
    void expiredReceiptCannotReplayAndCleanupEndsItsFingerprintHorizon() {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        grant(operator, Action.RESTORE, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        Command original = command(target, 0, Action.RESTRICT, Reason.HARASSMENT);
        assertThat(service(NOW).execute(operator, original).afterRevision()).isEqualTo(1);
        jdbc().update("""
            UPDATE moderation_contribution_audit SET issued_at = ?, expires_at = ?
            WHERE operator_id = ? AND request_id = ?
            """, Timestamp.from(NOW.minus(Duration.ofDays(31))),
                Timestamp.from(NOW.minus(Duration.ofDays(1))), operator, original.requestId());
        assertDenied(() -> service(NOW).execute(operator, original));
        for (int attempt = 0; attempt < 3 && count("moderation_contribution_audit", operator) > 0;
                attempt++) configuredCleanup.deleteExpired(500);
        assertThat(count("moderation_contribution_audit", operator)).isZero();
        Command replacement = new Command(original.requestId(), target, 1, Action.RESTORE,
                Reason.ERROR_CORRECTION);
        assertThat(service(NOW).execute(operator, replacement).afterRevision()).isEqualTo(2);
    }

    @Test
    void terminalRestrictionRevisionCannotProduceAnAuditOrDebit() {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        jdbc().update("""
            INSERT INTO live_contribution_restriction(actor_id, revision, restricted)
            VALUES (?, ?, TRUE)
            """, target, Long.MAX_VALUE);
        assertDenied(() -> service(NOW).execute(operator,
                command(target, Long.MAX_VALUE, Action.RESTRICT, Reason.HARASSMENT)));
        assertThat(count("moderation_contribution_audit", operator)).isZero();
        assertThat(count("moderation_restriction_action_slot", operator)).isZero();
    }

    @Test
    void durableQuotaSurvivesTargetDeletionAndReusesOnlyExpiredSlots() {
        UUID operator = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        UUID firstTarget = null;
        for (int index = 0; index < 20; index++) {
            UUID target = account();
            if (firstTarget == null) firstTarget = target;
            service(NOW.plusMillis(index)).execute(operator,
                    command(target, 0, Action.RESTRICT, Reason.SPAM_MANIPULATION));
        }
        UUID overflow = account();
        assertThatThrownBy(() -> service(NOW.plusSeconds(30)).execute(operator,
                command(overflow, 0, Action.RESTRICT, Reason.SPAM_MANIPULATION)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Audited restriction quota reached").hasNoCause();

        jdbc().update("DELETE FROM routiqo_account WHERE id = ?", firstTarget);
        assertThat(count("moderation_restriction_action_slot", operator)).isEqualTo(20);
        assertThatThrownBy(() -> service(NOW.plusSeconds(30)).execute(operator,
                command(overflow, 0, Action.RESTRICT, Reason.SPAM_MANIPULATION)))
                .isInstanceOf(IllegalStateException.class);
        jdbc().update("UPDATE moderation_restriction_action_slot SET used_at = ? WHERE operator_id = ?",
                Timestamp.from(NOW.minus(Duration.ofHours(1))), operator);
        assertThat(service(NOW).execute(operator,
                command(overflow, 0, Action.RESTRICT, Reason.SPAM_MANIPULATION)).afterRevision())
                .isEqualTo(1);
        assertThat(count("moderation_restriction_action_slot", operator)).isEqualTo(20);
    }

    @Test
    void auditInsertFailureRollsBackEffectAndDebit() {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        jdbc().execute("""
            CREATE FUNCTION reject_moderation_audit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'private test failure'; END $$
            """);
        jdbc().execute("""
            CREATE TRIGGER reject_moderation_audit BEFORE INSERT ON moderation_contribution_audit
            FOR EACH ROW EXECUTE FUNCTION reject_moderation_audit()
            """);
        try {
            assertThatThrownBy(() -> service(NOW).execute(operator,
                    command(target, 0, Action.RESTRICT, Reason.HARASSMENT)))
                    .isInstanceOf(AccountWriteUnavailable.class).hasNoCause();
            assertThat(jdbc().queryForObject("SELECT count(*) FROM live_contribution_restriction WHERE actor_id = ?",
                    Integer.class, target)).isZero();
            assertThat(count("moderation_restriction_action_slot", operator)).isZero();
        } finally {
            jdbc().execute("DROP TRIGGER reject_moderation_audit ON moderation_contribution_audit");
            jdbc().execute("DROP FUNCTION reject_moderation_audit()");
        }
    }

    @Test
    void physicalAuditCapacityAndBoundedLeafCleanupAreFailClosed() {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        jdbc().update("""
            INSERT INTO moderation_contribution_audit(
                operator_id, request_id, target_id, expected_revision, action, reason,
                before_revision, after_revision, restricted, issued_at, expires_at)
            SELECT ?, md5((? || value)::text)::uuid, ?, 0, 'restrict', 'harassment',
                   0, 1, TRUE, ?, ?
            FROM generate_series(1, 1000) value
            """, operator, operator.toString(), target, Timestamp.from(NOW.minus(Duration.ofDays(31))),
                Timestamp.from(NOW.minus(Duration.ofDays(1))));
        UUID next = account();
        assertThatThrownBy(() -> service(NOW).execute(operator,
                command(next, 0, Action.RESTRICT, Reason.HARASSMENT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Audited restriction capacity reached").hasNoCause();

        assertThat(configuredCleanup.deleteExpired(500)).isEqualTo(500);
        for (int attempt = 0; attempt < 3 && count("moderation_contribution_audit", operator) > 0;
                attempt++) configuredCleanup.deleteExpired(500);
        assertThat(count("moderation_contribution_audit", operator)).isZero();
        assertThat(service(NOW).execute(operator,
                command(next, 0, Action.RESTRICT, Reason.HARASSMENT)).afterRevision()).isEqualTo(1);
        assertThatThrownBy(() -> new TransactionTemplate(manager()).execute(
                status -> configuredCleanup.deleteExpired(1)))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThatThrownBy(() -> configuredCleanup.purgeExpiredDebits(501))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void competingOperatorsCannotBothApplyTheSameTargetRevision() throws Exception {
        UUID firstOperator = account();
        UUID secondOperator = account();
        UUID target = account();
        grant(firstOperator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        grant(secondOperator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> executeAfter(ready, go, firstOperator,
                    command(target, 0, Action.RESTRICT, Reason.HARASSMENT)));
            Future<Object> second = executor.submit(() -> executeAfter(ready, go, secondOperator,
                    command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)))
                    .filteredOn(AuditedContributionRestrictionService.Receipt.class::isInstance)
                    .hasSize(1);
        }
        assertThat(restrictionRevision(target)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM moderation_contribution_audit WHERE target_id = ?",
                Integer.class, target)).isEqualTo(1);
    }

    @Test
    void grantRevokedWhileCommandWaitsIsCheckedAfterTheLock() throws Exception {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        CountDownLatch grantLocked = new CountDownLatch(1);
        CountDownLatch revoke = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(manager())
                    .executeWithoutResult(status -> {
                        jdbc().queryForObject("""
                            SELECT permission FROM moderation_operator_grant
                            WHERE operator_id = ? AND permission = 'restrict' FOR UPDATE
                            """, String.class, operator);
                        grantLocked.countDown();
                        await(revoke);
                        jdbc().update("""
                            UPDATE moderation_operator_grant SET expires_at = ?
                            WHERE operator_id = ? AND permission = 'restrict'
                            """, Timestamp.from(NOW), operator);
                    }));
            assertThat(grantLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> action = executor.submit(() -> {
                try {
                    return service(NOW).execute(operator,
                            command(target, 0, Action.RESTRICT, Reason.HARASSMENT));
                } catch (RuntimeException denied) {
                    return denied;
                }
            });
            assertThat(waitingFor("%moderation_operator_grant%FOR UPDATE%")).isTrue();
            revoke.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(action.get(10, TimeUnit.SECONDS)).isInstanceOf(SecurityException.class);
        }
        assertThat(jdbc().queryForObject("SELECT count(*) FROM live_contribution_restriction WHERE actor_id = ?",
                Integer.class, target)).isZero();
    }

    @Test
    void grantExpiryUsesFreshTimeAfterWaitingForItsLock() throws Exception {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(60));
        AtomicReference<Instant> time = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) {
                if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException();
                return this;
            }
            @Override public Instant instant() { return time.get(); }
        };
        CountDownLatch grantLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(manager())
                    .executeWithoutResult(status -> {
                        jdbc().queryForObject("""
                            SELECT permission FROM moderation_operator_grant
                            WHERE operator_id = ? AND permission = 'restrict' FOR UPDATE
                            """, String.class, operator);
                        grantLocked.countDown();
                        await(release);
                    }));
            assertThat(grantLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> action = executor.submit(() -> {
                try {
                    return service(clock).execute(operator,
                            command(target, 0, Action.RESTRICT, Reason.HARASSMENT));
                } catch (RuntimeException denied) {
                    return denied;
                }
            });
            assertThat(waitingFor("%moderation_operator_grant%FOR UPDATE%")).isTrue();
            time.set(NOW.plusSeconds(60));
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(action.get(10, TimeUnit.SECONDS)).isInstanceOf(SecurityException.class);
        }
        assertThat(count("moderation_restriction_action_slot", operator)).isZero();
    }

    @Test
    void debitCleanupIsBoundedAndDoesNotDeleteFutureRows() {
        UUID operator = account();
        Instant actual = Instant.now();
        jdbc().update("""
            INSERT INTO moderation_restriction_action_slot(operator_id, slot, used_at)
            VALUES (?, 0, ?), (?, 1, ?)
            """, operator, Timestamp.from(actual.minus(Duration.ofHours(2))),
                operator, Timestamp.from(actual.plus(Duration.ofHours(1))));
        assertThat(configuredCleanup.purgeExpiredDebits(1)).isEqualTo(1);
        for (int attempt = 0; attempt < 100 && jdbc().queryForObject("""
                SELECT count(*) FROM moderation_restriction_action_slot
                WHERE operator_id = ? AND slot = 0
                """, Integer.class, operator) > 0; attempt++) {
            configuredCleanup.purgeExpiredDebits(1);
        }
        assertThat(count("moderation_restriction_action_slot", operator)).isEqualTo(1);
        // Other test accounts may still have eligible rows; only this account's
        // future debit must survive a further global maintenance pass.
        assertThat(configuredCleanup.purgeExpiredDebits(500)).isBetween(0, 500);
        assertThat(jdbc().queryForObject("""
            SELECT slot FROM moderation_restriction_action_slot WHERE operator_id = ?
                """, Integer.class, operator)).isEqualTo(1);
    }

    @Test
    void debitCleanupDeletionSerializesBeforeWriterCreatesFreshCharge() throws Exception {
        UUID operator = account();
        UUID target = account();
        Instant current = Instant.now();
        grant(operator, Action.RESTRICT, current.minusSeconds(1), current.plusSeconds(3600));
        jdbc().update("""
            INSERT INTO moderation_restriction_action_slot(operator_id, slot, used_at)
            VALUES (?, 0, ?)
            """, operator, Timestamp.from(current.minus(Duration.ofHours(2))));
        jdbc().execute("""
            CREATE FUNCTION pause_restriction_debit_delete() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              PERFORM pg_advisory_xact_lock(80421967);
              RETURN OLD;
            END $$
            """);
        jdbc().execute("""
            CREATE TRIGGER pause_restriction_debit_delete
            BEFORE DELETE ON moderation_restriction_action_slot
            FOR EACH ROW EXECUTE FUNCTION pause_restriction_debit_delete()
            """);
        CountDownLatch advisoryLocked = new CountDownLatch(1);
        CountDownLatch releaseAdvisory = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(manager())
                    .executeWithoutResult(status -> {
                        jdbc().query("SELECT pg_advisory_xact_lock(80421967)", row -> { });
                        advisoryLocked.countDown();
                        await(releaseAdvisory);
                    }));
            assertThat(advisoryLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> cleanup = executor.submit(
                    () -> configuredCleanup.purgeExpiredDebits(500));
            assertThat(waitingFor("%DELETE FROM moderation_restriction_action_slot%"))
                    .isTrue();
            Future<?> action = executor.submit(() -> service(current).execute(operator,
                    command(target, 0, Action.RESTRICT, Reason.HARASSMENT)));
            assertThat(waitingFor(
                    "%SELECT slot, used_at FROM moderation_restriction_action_slot%"))
                    .isTrue();
            releaseAdvisory.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(cleanup.get(10, TimeUnit.SECONDS)).isPositive();
            action.get(10, TimeUnit.SECONDS);
        } finally {
            releaseAdvisory.countDown();
            jdbc().execute("DROP TRIGGER pause_restriction_debit_delete ON moderation_restriction_action_slot");
            jdbc().execute("DROP FUNCTION pause_restriction_debit_delete()");
        }
        assertThat(count("moderation_restriction_action_slot", operator)).isEqualTo(1);
        configuredCleanup.purgeExpiredDebits(500);
        assertThat(count("moderation_restriction_action_slot", operator)).isEqualTo(1);
    }

    @Test
    void targetDeletionSerializesAfterTheAtomicActionAndLeavesOperatorDebit() throws Exception {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        CountDownLatch grantLocked = new CountDownLatch(1);
        CountDownLatch releaseGrant = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(manager())
                    .executeWithoutResult(status -> {
                        jdbc().queryForObject("""
                            SELECT permission FROM moderation_operator_grant
                            WHERE operator_id = ? AND permission = 'restrict' FOR UPDATE
                            """, String.class, operator);
                        grantLocked.countDown();
                        await(releaseGrant);
                    }));
            assertThat(grantLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> action = executor.submit(() -> service(NOW).execute(operator,
                    command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)));
            assertThat(waitingFor("%moderation_operator_grant%FOR UPDATE%")).isTrue();
            Future<?> deletion = executor.submit(() -> jdbc().update(
                    "DELETE FROM routiqo_account WHERE id = ?", target));
            assertThat(waitingFor("%DELETE FROM routiqo_account%")).isTrue();
            releaseGrant.countDown();
            holder.get(10, TimeUnit.SECONDS);
            action.get(10, TimeUnit.SECONDS);
            deletion.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc().queryForObject("SELECT count(*) FROM moderation_contribution_audit WHERE target_id = ?",
                Integer.class, target)).isZero();
        assertThat(count("moderation_restriction_action_slot", operator)).isEqualTo(1);
    }

    @Test
    void operatorDeletionRemovesAuthorityAuditAndDebitsButPreservesTargetRestriction() {
        UUID operator = account();
        UUID target = account();
        grant(operator, Action.RESTRICT, NOW.minusSeconds(1), NOW.plusSeconds(3600));
        assertThat(service(NOW).execute(operator,
                command(target, 0, Action.RESTRICT, Reason.UNSAFE_CONTENT)).afterRevision())
                .isEqualTo(1);

        jdbc().update("DELETE FROM routiqo_account WHERE id = ?", operator);

        assertThat(count("moderation_operator_grant", operator)).isZero();
        assertThat(count("moderation_contribution_audit", operator)).isZero();
        assertThat(count("moderation_restriction_action_slot", operator)).isZero();
        assertThat(restrictionRevision(target)).isEqualTo(1);
    }

    @Test
    void databaseConstrainsGrantScopeLifetimeAndFiniteTimestamps() {
        UUID operator = account();
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
            VALUES (?, 'review', ?, ?)
            """, operator, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(60))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> grant(operator, Action.RESTRICT, NOW,
                NOW.plus(Duration.ofHours(24)).plusNanos(1_000)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc().update("""
            INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
            VALUES (?, 'restrict', '-infinity'::timestamptz, 'infinity'::timestamptz)
            """, operator)).isInstanceOf(RuntimeException.class);
        assertThat(count("moderation_operator_grant", operator)).isZero();
    }

    @Test
    void auditLifetimeIsExactlySevenHundredTwentyHoursAcrossLondonDst() {
        UUID operator = account();
        UUID target = account();
        Instant issued = Instant.parse("2026-03-28T12:00:00Z");
        Instant expires = issued.plus(Duration.ofHours(720));
        new TransactionTemplate(manager()).executeWithoutResult(status -> {
            jdbc().execute("SET LOCAL TIME ZONE 'Europe/London'");
            assertThat(jdbc().update("""
                INSERT INTO moderation_contribution_audit(
                    operator_id, request_id, target_id, expected_revision, action, reason,
                    before_revision, after_revision, restricted, issued_at, expires_at)
                VALUES (?, ?, ?, 0, 'restrict', 'harassment', 0, 1, TRUE, ?, ?)
                """, operator, UUID.randomUUID(), target,
                    Timestamp.from(issued), Timestamp.from(expires))).isEqualTo(1);
        });
        assertThat(count("moderation_contribution_audit", operator)).isEqualTo(1);
    }

    private AuditedContributionRestrictionService service(Instant now) {
        return service(Clock.fixed(now, ZoneOffset.UTC));
    }

    private AuditedContributionRestrictionService service(Clock clock) {
        JdbcTemplate jdbc = jdbc();
        var restriction = new JdbcContributionRestrictionParticipant(jdbc);
        return new AuditedContributionRestrictionService(
                new JdbcEnabledAccountPairAuthority(jdbc, manager()),
                new JdbcAuditedContributionRestrictionParticipant(jdbc, restriction),
                clock);
    }

    private Object executeAfter(CountDownLatch ready, CountDownLatch go, UUID operator,
            Command command) throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        try {
            return service(NOW).execute(operator, command);
        } catch (RuntimeException denied) {
            return denied;
        }
    }

    private boolean waitingFor(String pattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc().queryForObject("""
                SELECT count(*) FROM pg_stat_activity activity
                WHERE activity.datname = current_database()
                  AND cardinality(pg_blocking_pids(activity.pid)) > 0
                  AND activity.query LIKE ?
                """, Integer.class, pattern);
            if (waiting > 0) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }

    private Command command(UUID target, long revision, Action action, Reason reason) {
        return new Command(UUID.randomUUID(), target, revision, action, reason);
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                id, id.toString());
        return id;
    }

    private void grant(UUID operator, Action action, Instant issued, Instant expires) {
        jdbc().update("""
            INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
            VALUES (?, ?, ?, ?)
            """, operator, action.name().toLowerCase(java.util.Locale.ROOT),
                Timestamp.from(issued), Timestamp.from(expires));
    }

    private long restrictionRevision(UUID target) {
        return jdbc().queryForObject("SELECT revision FROM live_contribution_restriction WHERE actor_id = ?",
                Long.class, target);
    }

    private int count(String table, UUID operator) {
        return jdbc().queryForObject("SELECT count(*) FROM " + table + " WHERE operator_id = ?",
                Integer.class, operator);
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    private DataSourceTransactionManager manager() { return new DataSourceTransactionManager(dataSource); }
    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SecurityException.class)
                .hasMessageMatching(".*denied").hasNoCause();
    }
}
