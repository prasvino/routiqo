package com.routiqo.core.identity.infrastructure;

import com.routiqo.core.identity.application.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class GoogleSessionServiceTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static DriverManagerDataSource source;
    static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");
    @BeforeAll static void setup() {
        DATABASE.start();
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
    }
    @AfterAll static void stop() { DATABASE.stop(); }
    GoogleSessionService service(Instant now, GoogleIdentityVerifier verifier) {
        return new GoogleSessionService(new JdbcSessionStore(new JdbcTemplate(source), new DataSourceTransactionManager(source)),
                verifier, Clock.fixed(now, ZoneOffset.UTC));
    }
    GoogleSessionService service(Instant now) {
        // Test-only verifier: cryptographic verification is covered separately with signed tokens.
        return service(now, (token, nonce) -> new GoogleIdentityVerifier.Identity("google", token));
    }
    @Test void exchangeStoresOnlyDigestsAndRevocationIsIdempotent() {
        var service = service(NOW); var challenge = service.begin();
        var session = service.exchange(challenge.id(), challenge.binding(), UUID.randomUUID().toString());
        assertThat(service.authenticate(session.credential())).isEqualTo(session.accountId());
        var jdbc = new JdbcTemplate(source);
        String stored = jdbc.queryForObject("SELECT token_hash FROM auth_session WHERE account_id = ?", String.class, session.accountId());
        assertThat(stored).hasSize(64).isNotEqualTo(session.credential());
        assertThat(jdbc.queryForObject("SELECT binding_hash FROM login_challenge WHERE id = ?", String.class, challenge.id()))
                .hasSize(64).isNotEqualTo(challenge.binding());
        assertThat(session.toString()).doesNotContain(session.credential());
        assertThat(challenge.toString()).doesNotContain(challenge.binding(), challenge.nonce());
        assertThatThrownBy(() -> service.exchange(challenge.id(), challenge.binding(), "ignored")).isInstanceOf(SecurityException.class);
        service.revoke(session.credential()); service.revoke(session.credential());
        assertThatThrownBy(() -> service.authenticate(session.credential())).isInstanceOf(SecurityException.class);
    }
    @Test void rejectsWrongBindingAndExpiryWithoutConsumingChallenge() {
        var service = service(NOW); var challenge = service.begin(); var other = service.begin();
        assertThatThrownBy(() -> service.exchange(challenge.id(), other.binding(), "ignored")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service(NOW.plusSeconds(300)).exchange(challenge.id(), challenge.binding(), "ignored"))
                .isInstanceOf(SecurityException.class);
        var session = service.exchange(challenge.id(), challenge.binding(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> service(NOW.plusSeconds(900)).authenticate(session.credential())).isInstanceOf(SecurityException.class);
    }
    @Test void failedVerificationLeavesChallengeAvailableAndUsesItsStoredNonce() {
        var challenge = service(NOW).begin();
        var rejected = service(NOW, (token, nonce) -> {
            assertThat(nonce).isEqualTo(challenge.nonce()); throw new SecurityException("test rejection");
        });
        assertThatThrownBy(() -> rejected.exchange(challenge.id(), challenge.binding(), "invalid")).isInstanceOf(SecurityException.class);
        assertThat(service(NOW).exchange(challenge.id(), challenge.binding(), UUID.randomUUID().toString())).isNotNull();
    }
    @Test void disablingAccountRejectsExistingSessions() {
        var service = service(NOW); var challenge = service.begin();
        var session = service.exchange(challenge.id(), challenge.binding(), UUID.randomUUID().toString());
        new JdbcTemplate(source).update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", session.accountId());
        assertThatThrownBy(() -> service.authenticate(session.credential())).isInstanceOf(SecurityException.class);
    }
    @Test void challengeExpiringDuringProviderVerificationCannotIssueSession() {
        var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var service = new GoogleSessionService(new JdbcSessionStore(new JdbcTemplate(source), new DataSourceTransactionManager(source)),
                (token, nonce) -> {
                    time.set(NOW.plusSeconds(300));
                    return new GoogleIdentityVerifier.Identity("google", UUID.randomUUID().toString());
                }, clock);
        var challenge = service.begin();
        assertThatThrownBy(() -> service.exchange(challenge.id(), challenge.binding(), "test"))
                .isInstanceOf(SecurityException.class);
        assertThat(new JdbcTemplate(source).queryForObject("SELECT consumed_at IS NULL FROM login_challenge WHERE id = ?",
                Boolean.class, challenge.id())).isTrue();
    }
    @Test void concurrentExchangeAllowsOneWinner() throws Exception {
        var challenge = service(NOW).begin(); var barrier = new CyclicBarrier(2); String subject = UUID.randomUUID().toString();
        GoogleIdentityVerifier verifier = (token, nonce) -> {
            try { barrier.await(10, TimeUnit.SECONDS); }
            catch (Exception error) { throw new IllegalStateException(error); }
            return new GoogleIdentityVerifier.Identity("google", subject);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> exchange = () -> {
                try { service(NOW, verifier).exchange(challenge.id(), challenge.binding(), "test"); return true; }
                catch (SecurityException replay) { return false; }
            };
            var first = executor.submit(exchange); var second = executor.submit(exchange);
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(new JdbcTemplate(source).queryForObject("""
            SELECT count(*) FROM auth_session s JOIN routiqo_account a ON a.id = s.account_id WHERE a.google_subject = ?
            """, Long.class, subject)).isEqualTo(1L);
    }
    @Test void sessionInsertFailureRollsBackConsumptionAndAccountCreation() {
        var service = service(NOW); var challenge = service.begin(); var jdbc = new JdbcTemplate(source);
        String subject = UUID.randomUUID().toString();
        // A temporary constraint rejects new inserts in this isolated, sequential test database.
        jdbc.execute("ALTER TABLE auth_session ADD CONSTRAINT test_reject_sessions CHECK (FALSE) NOT VALID");
        try {
            assertThatThrownBy(() -> service.exchange(challenge.id(), challenge.binding(), subject))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM routiqo_account WHERE google_subject = ?", Long.class, subject)).isZero();
        } finally { jdbc.execute("ALTER TABLE auth_session DROP CONSTRAINT test_reject_sessions"); }
        assertThat(service.exchange(challenge.id(), challenge.binding(), subject)).isNotNull();
    }
    GoogleSessionService.Session signedIn() {
        var service = service(NOW); var challenge = service.begin();
        return service.exchange(challenge.id(), challenge.binding(), UUID.randomUUID().toString());
    }
    @Test void renewalRotatesOnlyNearExpiryAndPreservesAuthenticationTime() {
        var original = signedIn();
        assertThat(service(NOW.plusSeconds(599)).renew(original.credential())).isEqualTo(original);
        var replacement = service(NOW.plusSeconds(600)).renew(original.credential());
        assertThat(replacement.credential()).isNotEqualTo(original.credential());
        assertThat(replacement.expiresAt()).isEqualTo(NOW.plusSeconds(1500));
        assertThat(service(NOW.plusSeconds(600)).authenticate(replacement.credential())).isEqualTo(original.accountId());
        assertThatThrownBy(() -> service(NOW.plusSeconds(600)).authenticate(original.credential())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service(NOW.plusSeconds(600)).renew(original.credential())).isInstanceOf(SecurityException.class);
        assertThat(new JdbcTemplate(source).queryForObject(
                "SELECT count(*) FROM auth_session WHERE account_id = ? AND authenticated_at = ?", Long.class,
                original.accountId(), java.sql.Timestamp.from(NOW))).isEqualTo(2L);
    }
    @Test void renewalCannotExtendBeyondTwelveHoursOrReviveExpiredOrDisabledSessions() {
        var session = signedIn();
        for (int elapsed = 600; elapsed < 43200; elapsed += 600) session = service(NOW.plusSeconds(elapsed)).renew(session.credential());
        var capped = session;
        assertThat(capped.expiresAt()).isEqualTo(NOW.plusSeconds(43200));
        assertThatThrownBy(() -> service(NOW.plusSeconds(43200)).renew(capped.credential())).isInstanceOf(SecurityException.class);
        var expired = signedIn();
        assertThatThrownBy(() -> service(NOW.plusSeconds(900)).renew(expired.credential())).isInstanceOf(SecurityException.class);
        var disabled = signedIn();
        new JdbcTemplate(source).update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", disabled.accountId());
        assertThatThrownBy(() -> service(NOW.plusSeconds(600)).renew(disabled.credential())).isInstanceOf(SecurityException.class);
    }
    @Test void concurrentRenewalAllowsExactlyOneReplacement() throws Exception {
        var original = signedIn(); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> renew = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { service(NOW.plusSeconds(600)).renew(original.credential()); return true; }
                catch (SecurityException replay) { return false; }
            };
            var first = executor.submit(renew); var second = executor.submit(renew);
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }
    @Test void failedRenewalInsertLeavesOriginalSessionValid() {
        var original = signedIn(); var jdbc = new JdbcTemplate(source);
        jdbc.execute("ALTER TABLE auth_session ADD CONSTRAINT test_reject_renewal CHECK (revoked_at IS NOT NULL) NOT VALID");
        try {
            assertThatThrownBy(() -> service(NOW.plusSeconds(600)).renew(original.credential()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { jdbc.execute("ALTER TABLE auth_session DROP CONSTRAINT test_reject_renewal"); }
        assertThat(service(NOW.plusSeconds(600)).authenticate(original.credential())).isEqualTo(original.accountId());
    }
    @Test void deletionCascadesOnlyTheAuthenticatedAccountAndAllItsSessions() {
        var target = signedIn(); var other = signedIn(); var jdbc = new JdbcTemplate(source);
        String subject = jdbc.queryForObject("SELECT google_subject FROM routiqo_account WHERE id = ?", String.class, target.accountId());
        var challenge = service(NOW).begin();
        var secondSession = service(NOW).exchange(challenge.id(), challenge.binding(), subject);
        for (var account : java.util.List.of(target.accountId(), other.accountId()))
            jdbc.update("INSERT INTO journey (id, owner_id, kind, status, started_at) VALUES (?, ?, 'TRIP', 'ACTIVE', ?)",
                    UUID.randomUUID(), account, java.sql.Timestamp.from(NOW));
        service(NOW.plusSeconds(299)).deleteAccount(target.credential());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routiqo_account WHERE id = ?", Long.class, target.accountId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journey WHERE owner_id = ?", Long.class, target.accountId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE account_id = ?", Long.class, target.accountId())).isZero();
        assertThatThrownBy(() -> service(NOW).authenticate(secondSession.credential())).isInstanceOf(SecurityException.class);
        assertThat(service(NOW).authenticate(other.credential())).isEqualTo(other.accountId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journey WHERE owner_id = ?", Long.class, other.accountId())).isEqualTo(1L);
        var fresh = service(NOW).begin();
        assertThat(service(NOW).exchange(fresh.id(), fresh.binding(), subject).accountId()).isNotEqualTo(target.accountId());
    }
    @Test void deletionRequiresFreshGoogleAuthenticationEvenAfterRenewal() {
        var original = signedIn();
        assertThatThrownBy(() -> service(NOW.plusSeconds(300)).deleteAccount(original.credential()))
                .isInstanceOf(RecentAuthenticationRequired.class);
        var renewed = service(NOW.plusSeconds(600)).renew(original.credential());
        assertThatThrownBy(() -> service(NOW.plusSeconds(601)).deleteAccount(renewed.credential()))
                .isInstanceOf(RecentAuthenticationRequired.class);
        assertThat(service(NOW.plusSeconds(601)).authenticate(renewed.credential())).isEqualTo(original.accountId());
        service(NOW.plusSeconds(602)).revoke(renewed.credential());
        assertThatThrownBy(() -> service(NOW.plusSeconds(603)).deleteAccount(renewed.credential())).isInstanceOf(SecurityException.class);
        var disabled = signedIn();
        new JdbcTemplate(source).update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", disabled.accountId());
        assertThatThrownBy(() -> service(NOW).deleteAccount(disabled.credential())).isInstanceOf(SecurityException.class);
    }
    @Test void logoutOfRotatedCredentialRevokesItsReplacement() {
        var original = signedIn();
        var replacement = service(NOW.plusSeconds(600)).renew(original.credential());
        service(NOW.plusSeconds(601)).revoke(original.credential());
        assertThatThrownBy(() -> service(NOW.plusSeconds(602)).authenticate(replacement.credential()))
                .isInstanceOf(SecurityException.class);
    }
    @Test void deletionAndRenewalAcrossTwoSessionsDoNotDeadlockOrLeaveAccess() throws Exception {
        var original = signedIn(); var jdbc = new JdbcTemplate(source);
        String subject = jdbc.queryForObject("SELECT google_subject FROM routiqo_account WHERE id = ?", String.class, original.accountId());
        var current = service(NOW.plusSeconds(600)); var challenge = current.begin();
        var fresh = current.exchange(challenge.id(), challenge.binding(), subject);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var renewing = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { return current.renew(original.credential()); }
                catch (SecurityException deleted) { return null; }
            });
            var deleting = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                current.deleteAccount(fresh.credential());
                return true;
            });
            assertThat(deleting.get(20, TimeUnit.SECONDS)).isTrue();
            var replacement = renewing.get(20, TimeUnit.SECONDS);
            if (replacement != null) assertThatThrownBy(() -> current.authenticate(replacement.credential())).isInstanceOf(SecurityException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE account_id = ?", Long.class, original.accountId())).isZero();
    }
}
