package com.routiqo.core.journey.infrastructure;

import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.identity.infrastructure.JdbcSessionStore;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.journey.domain.Journey;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class WriteAuthorityPersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant START = Instant.parse("2026-09-12T12:00:00.123456Z");

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired com.routiqo.core.journey.application.JourneyStore configuredStore;
    @Autowired JourneyWriteAuthority configuredAuthority;

    @Test void persistenceProfileExposesOneStoreThroughBothJourneyInterfaces() {
        assertThat(configuredStore).isSameAs(configuredAuthority).isInstanceOf(JdbcJourneyStore.class);
    }

    @Test void accountBoundaryRejectsInvalidDisabledAndMissingAccountsWithoutCallbackWork() {
        UUID disabled = account(false);
        var invoked = new AtomicBoolean();
        AccountWriteAuthority authority = accountAuthority();

        assertDenied(() -> authority.withEnabledAccount(null, () -> invoked.getAndSet(true)));
        assertDenied(() -> authority.withEnabledAccount(new UUID(0, 0), () -> invoked.getAndSet(true)));
        assertDenied(() -> authority.withEnabledAccount(UUID.randomUUID(), () -> invoked.getAndSet(true)));
        assertDenied(() -> authority.withEnabledAccount(disabled, () -> invoked.getAndSet(true)));
        assertDenied(() -> authority.withEnabledAccount(disabled, null));
        assertThat(invoked).isFalse();
    }

    @Test void accountBoundaryRejectsAmbientTransactionsBeforeCallbackWork() {
        UUID actor = account(true);
        var invoked = new AtomicBoolean();
        var outer = new TransactionTemplate(manager());

        outer.executeWithoutResult(status -> assertThatThrownBy(
                () -> accountAuthority().withEnabledAccount(actor, () -> invoked.getAndSet(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account write authority cannot join a transaction")
                .hasNoCause());
        assertThat(invoked).isFalse();
    }

    @Test void callbackFailureRollsBackWritesReleasesTheLockAndDatabaseFailuresAreRedacted() {
        UUID actor = account(true);
        UUID journeyId = UUID.randomUUID();
        AccountWriteAuthority authority = accountAuthority();
        var callbackFailure = new IllegalStateException("private callback marker");

        assertThatThrownBy(() -> authority.withEnabledAccount(actor, () -> {
            jdbc().update("""
                INSERT INTO journey (id, owner_id, kind, status, started_at)
                VALUES (?, ?, 'TRIP', 'ACTIVE', ?)
                """, journeyId, actor, Timestamp.from(START));
            throw callbackFailure;
        })).isSameAs(callbackFailure);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM journey WHERE id = ?", Long.class, journeyId)).isZero();
        assertThat(authority.withEnabledAccount(actor, () -> "released")).isEqualTo("released");

        assertThatThrownBy(() -> authority.withEnabledAccount(actor, () -> jdbc().queryForObject(
                "SELECT private_secret_column FROM routiqo_account", String.class)))
                .isInstanceOf(AccountWriteUnavailable.class)
                .hasMessage("Account write authority is unavailable")
                .hasNoCause();
    }

    @Test void rollbackFailureCanLogOnlyTheAlreadyRedactedCallbackFailure() {
        UUID actor = account(true);
        PlatformTransactionManager rollbackFailing = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) {
                throw new TransactionSystemException("synthetic rollback failure");
            }
        };
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TransactionTemplate.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var authority = new JdbcAccountWriteAuthority(jdbc(), rollbackFailing);
            assertThatThrownBy(() -> authority.withEnabledAccount(actor, () -> {
                throw new DataAccessResourceFailureException("jdbc:postgresql://private/secret");
            })).isInstanceOf(AccountWriteUnavailable.class)
                    .hasMessage("Account write authority is unavailable")
                    .hasNoCause();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        assertThat(captured.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("private", "jdbc:", "secret");
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(AccountWriteUnavailable.class.getName());
            assertThat(event.getThrowableProxy().getMessage())
                    .isEqualTo("Account write authority is unavailable");
        });
    }

    @Test void realAccountLockTimeoutIsRedactedAndNeverInvokesTheCallback() throws Exception {
        UUID actor = account(true);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invoked = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holding = executor.submit(() -> {
                new TransactionTemplate(manager()).executeWithoutResult(status -> {
                    jdbc().queryForObject(
                            "SELECT id FROM routiqo_account WHERE id = ? FOR UPDATE", UUID.class, actor);
                    locked.countDown();
                    awaitLong(release);
                });
                return null;
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> accountAuthority().withEnabledAccount(
                        actor, () -> invoked.getAndSet(true)))
                        .isInstanceOf(AccountWriteUnavailable.class)
                        .hasMessage("Account write authority is unavailable")
                        .hasNoCause();
                assertThat(invoked).isFalse();
            } finally {
                release.countDown();
            }
            holding.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void journeyBoundaryLocksOwnedCurrentStateAndRollsBackCallbackWrites() {
        UUID actor = account(true);
        UUID stranger = account(true);
        UUID journeyId = UUID.randomUUID();
        JdbcJourneyStore store = journeyStore();
        store.start(actor, journeyId, Journey.Kind.TRIP, START);
        var invoked = new AtomicBoolean();

        assertThatThrownBy(() -> store.withOwnedJourney(stranger, journeyId, journey -> {
            invoked.set(true);
            return journey;
        })).isInstanceOf(JourneyNotFound.class).hasMessage("Journey not found").hasNoCause();
        assertThat(invoked).isFalse();

        assertThatThrownBy(() -> store.withOwnedJourney(actor, journeyId, journey -> {
            assertThat(journey.status()).isEqualTo(Journey.Status.ACTIVE);
            jdbc().update("""
                UPDATE journey SET status = 'COMPLETED', completed_at = ? WHERE id = ?
                """, Timestamp.from(START.plusSeconds(30)), journeyId);
            throw new IllegalArgumentException("rollback");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("rollback");
        assertThat(store.find(actor, journeyId).orElseThrow().status()).isEqualTo(Journey.Status.ACTIVE);

        journeyService(store, START.plusSeconds(60)).complete(actor, journeyId);
        assertThat(store.withOwnedJourney(actor, journeyId, Journey::status))
                .isEqualTo(Journey.Status.COMPLETED);
    }

    @Test void ownedJourneyCallbackHoldsConcurrentCompletionBehindTheAccountGate() throws Exception {
        UUID actor = account(true);
        UUID journeyId = UUID.randomUUID();
        JdbcJourneyStore holder = journeyStore();
        JdbcJourneyStore completer = journeyStore();
        holder.start(actor, journeyId, Journey.Kind.TRIP, START);

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> holder.withOwnedJourney(actor, journeyId, journey -> {
                entered.countDown();
                await(release);
                return journey.status();
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var completion = executor.submit(
                    () -> journeyService(completer, START.plusSeconds(60)).complete(actor, journeyId));
            try {
                assertThat(waitingForAccountLock()).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo(Journey.Status.ACTIVE);
            assertThat(completion.get(5, TimeUnit.SECONDS).status()).isEqualTo(Journey.Status.COMPLETED);
        }
    }

    @Test void accountDeletionWaitsForAuthorityAndDeletionWinningFirstDeniesLaterWork() throws Exception {
        UUID actor = account(true);
        UUID journeyId = UUID.randomUUID();
        JdbcJourneyStore holder = journeyStore();
        holder.start(actor, journeyId, Journey.Kind.TRIP, START);
        String tokenHash = session(actor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> holder.withOwnedJourney(actor, journeyId, journey -> {
                entered.countDown();
                await(release);
                return journey;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> {
                new JdbcSessionStore(new JdbcTemplate(dataSource), manager())
                        .deleteAccount(tokenHash, START.plusSeconds(1));
                return null;
            });
            try {
                assertThat(waitingForAccountLock()).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(holding.get(5, TimeUnit.SECONDS).id()).isEqualTo(journeyId);
            deletion.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM journey WHERE id = ?", Long.class, journeyId)).isZero();

        var invoked = new AtomicBoolean();
        assertDenied(() -> accountAuthority().withEnabledAccount(actor, () -> invoked.getAndSet(true)));
        assertThat(invoked).isFalse();
    }

    @Test void independentActorAuthorityDoesNotWaitForAHeldAccount() throws Exception {
        UUID first = account(true);
        UUID second = account(true);
        AccountWriteAuthority holder = accountAuthority();
        AccountWriteAuthority independent = accountAuthority();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> holder.withEnabledAccount(first, () -> {
                firstEntered.countDown();
                await(releaseFirst);
                return first;
            }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var other = executor.submit(() -> independent.withEnabledAccount(second, () -> {
                secondEntered.countDown();
                return second;
            }));
            try {
                assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(other.get(2, TimeUnit.SECONDS)).isEqualTo(second);
            } finally {
                releaseFirst.countDown();
            }
            assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo(first);
        }
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private DataSourceTransactionManager manager() {
        return new DataSourceTransactionManager(dataSource);
    }

    private AccountWriteAuthority accountAuthority() {
        return new JdbcAccountWriteAuthority(new JdbcTemplate(dataSource), manager());
    }

    private JdbcJourneyStore journeyStore() {
        var jdbc = new JdbcTemplate(dataSource);
        return new JdbcJourneyStore(jdbc, new JdbcAccountWriteAuthority(jdbc, manager()));
    }

    private static JourneyService journeyService(JdbcJourneyStore store, Instant now) {
        return new JourneyService(store, store, List.of(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private UUID account(boolean enabled) {
        UUID actor = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account (id, google_subject, enabled) VALUES (?, ?, ?)",
                actor, actor.toString(), enabled);
        return actor;
    }

    private String session(UUID actor) {
        String tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc().update("""
            INSERT INTO auth_session
                (token_hash, account_id, created_at, expires_at, authenticated_at)
            VALUES (?, ?, ?, ?, ?)
            """, tokenHash, actor, Timestamp.from(START), Timestamp.from(START.plusSeconds(900)),
                Timestamp.from(START));
        return tokenHash;
    }

    private boolean waitingForAccountLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc().queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity activity
                    WHERE activity.datname = current_database()
                      AND cardinality(pg_blocking_pids(activity.pid)) > 0
                      AND activity.query LIKE '%routiqo_account%FOR UPDATE%'
                )
                """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test release timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted");
        }
    }

    private static void awaitLong(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test release timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted");
        }
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(SecurityException.class)
                .hasMessage("Account write authority denied")
                .hasNoCause();
    }
}
