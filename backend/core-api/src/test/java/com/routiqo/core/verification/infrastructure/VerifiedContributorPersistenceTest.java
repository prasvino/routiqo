package com.routiqo.core.verification.infrastructure;

import com.routiqo.core.identity.infrastructure.JdbcEnabledAccountPairAuthority;
import com.routiqo.core.verification.application.VerifiedContributorService;
import com.routiqo.core.verification.application.VerifiedContributorService.Action;
import com.routiqo.core.verification.application.VerifiedContributorService.Command;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class VerifiedContributorPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-09-23T06:00:00Z");
    static { DATABASE.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired DataSource dataSource;

    @Test
    void twoDistinctAuthorizedReviewersIssueAndRevocationFailsClosed() {
        UUID target = account(), first = account(), second = account(), person = UUID.randomUUID();
        UUID caseId = caseFor(target, person, NOW.plusSeconds(3600));
        grant(first, caseId, Action.REVIEW);
        grant(second, caseId, Action.REVIEW);
        grant(second, caseId, Action.REVOKE);
        Command firstCommand = command(caseId, 0, Action.REVIEW);
        assertThat(service().execute(first, firstCommand).active()).isFalse();
        assertThat(reader().current(target, NOW)).isEmpty();
        assertThat(service().execute(first, firstCommand).revision()).isEqualTo(1);
        assertThatThrownBy(() -> service().execute(first, command(caseId, 1, Action.REVIEW)))
                .isInstanceOf(SecurityException.class);
        assertThat(service().execute(second, command(caseId, 1, Action.REVIEW)).active()).isTrue();
        assertThat(reader().current(target, NOW)).get().extracting("personRef").isEqualTo(person);
        assertThat(reader().current(target, NOW.plusSeconds(3600))).isEmpty();

        assertThat(service().execute(second, command(caseId, 2, Action.REVOKE)).revision()).isEqualTo(3);
        assertThat(reader().current(target, NOW)).isEmpty();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM live_verification_audit WHERE account_id = ?",
                Integer.class, target)).isEqualTo(3);
    }

    @Test
    void grantsCaseScopeDisabledAccountsAndOneActivePersonAreEnforced() {
        UUID firstTarget = account(), secondTarget = account(), first = account(), second = account();
        UUID person = UUID.randomUUID();
        UUID firstCase = caseFor(firstTarget, person, NOW.plusSeconds(3600));
        UUID secondCase = caseFor(secondTarget, person, NOW.plusSeconds(3600));
        grant(first, firstCase, Action.REVIEW);
        grant(second, firstCase, Action.REVIEW);
        assertThatThrownBy(() -> service().execute(first, command(secondCase, 0, Action.REVIEW)))
                .isInstanceOf(SecurityException.class);
        service().execute(first, command(firstCase, 0, Action.REVIEW));
        service().execute(second, command(firstCase, 1, Action.REVIEW));
        grant(first, secondCase, Action.REVIEW);
        grant(second, secondCase, Action.REVIEW);
        service().execute(first, command(secondCase, 0, Action.REVIEW));
        assertThatThrownBy(() -> service().execute(second, command(secondCase, 1, Action.REVIEW)))
                .isInstanceOf(RuntimeException.class);
        assertThat(reader().current(secondTarget, NOW)).isEmpty();
        jdbc().update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", firstTarget);
        assertThat(reader().current(firstTarget, NOW)).isEmpty();
    }

    @Test
    void staleGrantAndAccountDeletionCloseAuthorityAndDeleteAudit() {
        UUID target = account(), first = account(), second = account();
        UUID caseId = caseFor(target, UUID.randomUUID(), NOW.plusSeconds(3600));
        grant(first, caseId, Action.REVIEW);
        grant(second, caseId, Action.REVIEW);
        Command firstCommand = command(caseId, 0, Action.REVIEW);
        service().execute(first, firstCommand);
        jdbc().update("UPDATE live_verification_reviewer_grant SET expires_at = ? WHERE reviewer_id = ?",
                Timestamp.from(NOW), first);
        assertThatThrownBy(() -> service().execute(first, firstCommand))
                .isInstanceOf(SecurityException.class);
        service().execute(second, command(caseId, 1, Action.REVIEW));
        assertThat(reader().current(target, NOW)).isPresent();
        jdbc().update("DELETE FROM routiqo_account WHERE id = ?", target);
        assertThat(reader().current(target, NOW)).isEmpty();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM live_verification_audit WHERE account_id = ?",
                Integer.class, target)).isZero();
    }

    @Test
    void expiredAuthorityCanStillBeRevokedToPermitReassignment() {
        UUID target = account(), nextTarget = account(), first = account(), second = account();
        UUID person = UUID.randomUUID();
        UUID caseId = caseFor(target, person, NOW.plusSeconds(30));
        grant(first, caseId, Action.REVIEW);
        grant(second, caseId, Action.REVIEW);
        service().execute(first, command(caseId, 0, Action.REVIEW));
        service().execute(second, command(caseId, 1, Action.REVIEW));
        assertThat(reader().current(target, NOW.plusSeconds(31))).isEmpty();
        jdbc().update("""
            INSERT INTO live_verification_reviewer_grant(reviewer_id, case_id, action, issued_at, expires_at)
            VALUES (?, ?, 'revoke', ?, ?)
            """, second, caseId, Timestamp.from(NOW.plusSeconds(30)),
                Timestamp.from(NOW.plusSeconds(90)));
        assertThat(service(NOW.plusSeconds(31)).execute(second, command(caseId, 2, Action.REVOKE))
                .revision()).isEqualTo(3);
        UUID nextCase = caseFor(nextTarget, person, NOW.plusSeconds(3600));
        grant(first, nextCase, Action.REVIEW);
        grant(second, nextCase, Action.REVIEW);
        service(NOW.plusSeconds(32)).execute(first, command(nextCase, 0, Action.REVIEW));
        service(NOW.plusSeconds(32)).execute(second, command(nextCase, 1, Action.REVIEW));
        assertThat(reader().current(nextTarget, NOW.plusSeconds(32))).isPresent();
    }

    private VerifiedContributorService service() {
        return service(NOW);
    }
    private VerifiedContributorService service(Instant now) {
        return new VerifiedContributorService(
                new JdbcEnabledAccountPairAuthority(jdbc(), new DataSourceTransactionManager(dataSource)),
                new JdbcVerifiedContributorAuthority(jdbc()), Clock.fixed(now, ZoneOffset.UTC));
    }
    private JdbcVerifiedContributorAuthority reader() { return new JdbcVerifiedContributorAuthority(jdbc()); }
    private Command command(UUID caseId, long revision, Action action) {
        return new Command(UUID.randomUUID(), caseId, revision, action);
    }
    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", id, id.toString());
        return id;
    }
    private UUID caseFor(UUID target, UUID person, Instant expiry) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO live_verification_case(id, account_id, person_ref, expires_at) VALUES (?, ?, ?, ?)",
                id, target, person, Timestamp.from(expiry));
        return id;
    }
    private void grant(UUID reviewer, UUID caseId, Action action) {
        jdbc().update("""
            INSERT INTO live_verification_reviewer_grant(reviewer_id, case_id, action, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, reviewer, caseId, action.name().toLowerCase(java.util.Locale.ROOT),
                Timestamp.from(NOW.minusSeconds(10)), Timestamp.from(NOW.plusSeconds(3600)));
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
}
