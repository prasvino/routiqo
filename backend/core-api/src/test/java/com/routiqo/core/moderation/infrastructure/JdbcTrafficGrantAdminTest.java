package com.routiqo.core.moderation.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
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
class JdbcTrafficGrantAdminTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant NOW = Instant.now();
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    JdbcTrafficGrantAdmin service;
    UUID actor;
    UUID target;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM traffic_grant_action_audit_v3");
        jdbc.update("DELETE FROM traffic_grant_read_audit_v3");
        jdbc.update("DELETE FROM moderation_operator_grant WHERE permission IN ('traffic_review', 'traffic_suppress', 'traffic_grant_admin')");
        actor = account(); target = account();
        grant(actor, "traffic_grant_admin", NOW.plusSeconds(3600));
        service = new JdbcTrafficGrantAdmin(jdbc, manager);
    }
    UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", id, "grant-test-" + id);
        return id;
    }
    void grant(UUID id, String permission, Instant expires) {
        jdbc.update("INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at) VALUES (?, ?, ?, ?)",
                id, permission, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(expires));
    }
    @Test void issueReviewRevokeAndExactRetryDoNotRenew() {
        UUID issue = UUID.randomUUID(), revoke = UUID.randomUUID();
        var first = service.change(actor, target, issue, "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15, () -> {});
        assertThat(first.expiresAt()).isBetween(NOW.plusSeconds(900), Instant.now().plusSeconds(900));
        assertThat(service.review(actor, target, () -> {}).grants()).hasSize(1);
        assertThat(service.change(actor, target, issue, "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15, () -> {})
                .replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT expires_at FROM moderation_operator_grant WHERE operator_id = ? AND permission = 'traffic_review'",
                Timestamp.class, target).toInstant()).isEqualTo(first.expiresAt());
        assertThatThrownBy(() -> service.change(actor, target, UUID.randomUUID(), "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15, () -> {}))
                .isInstanceOf(JdbcTrafficGrantAdmin.Conflict.class);
        assertThatThrownBy(() -> service.change(actor, target, issue, "traffic_review", "ISSUE", "OPERATOR_TRIAL", 16, () -> {}))
                .isInstanceOf(JdbcTrafficGrantAdmin.Conflict.class);
        assertThat(service.change(actor, target, revoke, "traffic_review", "REVOKE", "COVERAGE_CHANGE", null, () -> {}).expiresAt())
                .isNull();
        assertThat(service.review(actor, target, () -> {}).grants()).isEmpty();
        assertThat(service.change(actor, target, revoke, "traffic_review", "REVOKE", "COVERAGE_CHANGE", null, () -> {})
                .replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic_grant_action_audit_v3", Integer.class)).isEqualTo(2);
    }
    @Test void authorityAndTargetRestrictionsFailClosed() {
        assertThatThrownBy(() -> service.review(actor, actor, () -> {})).isInstanceOf(JdbcTrafficGrantAdmin.Missing.class);
        grant(target, "traffic_grant_admin", NOW.plusSeconds(600));
        assertThatThrownBy(() -> service.review(actor, target, () -> {})).isInstanceOf(JdbcTrafficGrantAdmin.Missing.class);
        jdbc.update("DELETE FROM moderation_operator_grant WHERE operator_id = ? AND permission = 'traffic_grant_admin'", target);
        jdbc.update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", target);
        assertThatThrownBy(() -> service.review(actor, target, () -> {})).isInstanceOf(JdbcTrafficGrantAdmin.Missing.class);
        jdbc.update("UPDATE routiqo_account SET enabled = TRUE WHERE id = ?", target);
        jdbc.update("UPDATE moderation_operator_grant SET issued_at = ?, expires_at = ? WHERE operator_id = ? AND permission = 'traffic_grant_admin'",
                Timestamp.from(NOW.minusSeconds(120)), Timestamp.from(NOW.minusSeconds(1)), actor);
        assertThatThrownBy(() -> service.review(actor, target, () -> {}))
                .isInstanceOf(JdbcTrafficGrantAdmin.Denied.class);
    }
    @Test void moderatorOnlyCannotDistinguishEnabledUnknownOrDisabledTargets() {
        jdbc.update("DELETE FROM moderation_operator_grant WHERE operator_id = ? AND permission = 'traffic_grant_admin'", actor);
        grant(actor, "traffic_review", NOW.plusSeconds(600));
        UUID disabled = account();
        jdbc.update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", disabled);
        for (UUID attempted : java.util.List.of(target, UUID.randomUUID(), disabled, actor)) {
            assertThatThrownBy(() -> service.review(actor, attempted, () -> {}))
                    .isInstanceOf(JdbcTrafficGrantAdmin.Denied.class);
            assertThatThrownBy(() -> service.change(actor, attempted, UUID.randomUUID(), "traffic_review",
                    "ISSUE", "OPERATOR_TRIAL", 15, () -> {}))
                    .isInstanceOf(JdbcTrafficGrantAdmin.Denied.class);
        }
    }
    @Test void exactReplayRequiresCurrentRootGrant() {
        UUID request = UUID.randomUUID();
        service.change(actor, target, request, "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15, () -> {});
        jdbc.update("UPDATE moderation_operator_grant SET issued_at = ?, expires_at = ? WHERE operator_id = ? AND permission = 'traffic_grant_admin'",
                Timestamp.from(NOW.minusSeconds(120)), Timestamp.from(NOW.minusSeconds(1)), actor);
        assertThatThrownBy(() -> service.change(actor, target, request, "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15, () -> {}))
                .isInstanceOf(JdbcTrafficGrantAdmin.Denied.class);
        jdbc.update("DELETE FROM moderation_operator_grant WHERE operator_id = ? AND permission = 'traffic_grant_admin'", actor);
        assertThatThrownBy(() -> service.change(actor, target, request, "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15, () -> {}))
                .isInstanceOf(JdbcTrafficGrantAdmin.Denied.class);
    }
    @Test void retainedAuditCapacityFailsClosedAndAuditInsertionFailureRollsBack() {
        jdbc.update("""
                WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS at)
                INSERT INTO traffic_grant_action_audit_v3
                  (administrator_id, request_id, target_id, permission, action, reason,
                   duration_minutes, grant_expires_at, occurred_at, expires_at)
                SELECT ?, gen_random_uuid(), ?, 'traffic_review', 'ISSUE', 'OPERATOR_TRIAL',
                  15, moment.at + INTERVAL '15 minutes', moment.at, moment.at + INTERVAL '720 hours'
                FROM moment, generate_series(1, 1000)
                """, actor, target);
        assertThatThrownBy(() -> service.change(actor, target, UUID.randomUUID(), "traffic_review", "ISSUE",
                "OPERATOR_TRIAL", 15, () -> {})).isInstanceOf(JdbcTrafficGrantAdmin.Limited.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_operator_grant WHERE operator_id = ?", Integer.class, target)).isZero();
        jdbc.update("DELETE FROM traffic_grant_action_audit_v3 WHERE administrator_id = ?", actor);
        jdbc.execute("ALTER TABLE traffic_grant_action_audit_v3 ADD CONSTRAINT force_grant_audit_failure CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> service.change(actor, target, UUID.randomUUID(), "traffic_review", "ISSUE",
                    "OPERATOR_TRIAL", 15, () -> {})).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_operator_grant WHERE operator_id = ?", Integer.class, target)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE traffic_grant_action_audit_v3 DROP CONSTRAINT force_grant_audit_failure");
        }
    }
    @Test void failedAuditOrSessionRollsBackGrantAndAmbientTransactionIsRefused() {
        assertThatThrownBy(() -> service.change(actor, target, UUID.randomUUID(), "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15,
                () -> { throw new SecurityException("session expired"); })).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_operator_grant WHERE operator_id = ?", Integer.class, target)).isZero();
        var outer = new TransactionTemplate(manager);
        assertThatThrownBy(() -> outer.execute(status -> service.review(actor, target, () -> {})))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void deletionCascadesAuditAndCleanupIsBounded() {
        service.change(actor, target, UUID.randomUUID(), "traffic_suppress", "ISSUE", "OPERATOR_TRIAL", 15, () -> {});
        service.review(actor, target, () -> {});
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", target);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM traffic_grant_action_audit_v3", Integer.class)).isZero();
        UUID other = account();
        grant(other, "traffic_review", NOW.minusSeconds(1));
        jdbc.update("UPDATE traffic_grant_read_audit_v3 SET occurred_at = ?, expires_at = ?",
                Timestamp.from(NOW.minusSeconds(31L * 86400)), Timestamp.from(NOW.minusSeconds(86400)));
        JdbcTrafficGrantCleanup cleanup = new JdbcTrafficGrantCleanup(jdbc, manager);
        assertThat(cleanup.cleanup(1)).isBetween(1, 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_operator_grant WHERE operator_id = ?", Integer.class, other)).isZero();
    }
    @Test void revokeAndIssueSerializeAcrossAccountAndGrantLocks() throws Exception {
        grant(target, "traffic_review", NOW.plusSeconds(600));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var revoke = workers.submit(() -> service.change(actor, target, UUID.randomUUID(), "traffic_review",
                    "REVOKE", "COVERAGE_CHANGE", null, () -> {
                        if (checks.incrementAndGet() == 2) { locked.countDown(); await(release); }
                    }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var issue = workers.submit(() -> service.change(actor, target, UUID.randomUUID(),
                    "traffic_review", "ISSUE", "OPERATOR_TRIAL", 15, () -> {}));
            assertThat(issue.isDone()).isFalse();
            release.countDown();
            assertThat(revoke.get(5, TimeUnit.SECONDS).expiresAt()).isNull();
            assertThat(issue.get(5, TimeUnit.SECONDS).expiresAt()).isAfter(NOW.plusSeconds(900));
        }
    }
    @Test void moderatorPermissionCheckCannotPassAfterEarlierRevocation() throws Exception {
        grant(target, "traffic_review", NOW.plusSeconds(600));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var revoke = workers.submit(() -> service.change(actor, target, UUID.randomUUID(), "traffic_review",
                    "REVOKE", "SECURITY_RESPONSE", null, () -> {
                        if (checks.incrementAndGet() == 2) { locked.countDown(); await(release); }
                    }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var check = workers.submit(() -> moderatorPermissionCheck());
            assertThat(check.isDone()).isFalse();
            release.countDown();
            revoke.get(5, TimeUnit.SECONDS);
            assertThat(check.get(5, TimeUnit.SECONDS)).isFalse();
        }
    }
    @Test void earlierModeratorPermissionCheckFinishesBeforeRevocation() throws Exception {
        grant(target, "traffic_review", NOW.plusSeconds(600));
        CountDownLatch checked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var check = workers.submit(() -> moderatorPermissionCheck(() -> {
                checked.countDown(); await(release);
            }));
            assertThat(checked.await(3, TimeUnit.SECONDS)).isTrue();
            var revoke = workers.submit(() -> service.change(actor, target, UUID.randomUUID(), "traffic_review",
                    "REVOKE", "SECURITY_RESPONSE", null, () -> {}));
            assertThat(revoke.isDone()).isFalse();
            release.countDown();
            assertThat(check.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(revoke.get(5, TimeUnit.SECONDS).expiresAt()).isNull();
        }
    }
    private boolean moderatorPermissionCheck() { return moderatorPermissionCheck(() -> {}); }
    private boolean moderatorPermissionCheck(Runnable afterCheck) {
        // Mirrors JdbcTrafficReview: session account FOR SHARE, then current grant FOR UPDATE.
        return new TransactionTemplate(manager).execute(status -> {
            jdbc.query("SELECT id FROM routiqo_account WHERE id = ? FOR SHARE", (rs, n) -> rs.getObject(1, UUID.class), target);
            boolean current = !jdbc.query("""
                    SELECT TRUE FROM moderation_operator_grant
                    WHERE operator_id = ? AND permission = 'traffic_review' AND issued_at <= ? AND expires_at > ?
                    FOR UPDATE
                    """, (rs, n) -> true, target, Timestamp.from(NOW), Timestamp.from(NOW)).isEmpty();
            afterCheck.run();
            return current;
        });
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
