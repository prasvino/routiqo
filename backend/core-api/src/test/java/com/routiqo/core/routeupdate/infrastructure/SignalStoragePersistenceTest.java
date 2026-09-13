package com.routiqo.core.routeupdate.infrastructure;

import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.identity.infrastructure.JdbcSessionStore;
import com.routiqo.core.journey.application.JourneyService;
import com.routiqo.core.journey.domain.Journey;
import com.routiqo.core.journey.infrastructure.JdbcJourneyStore;
import com.routiqo.core.privacy.application.PresenceConsentService;
import com.routiqo.core.privacy.infrastructure.JdbcPresenceConsentParticipant;
import com.routiqo.core.routeupdate.application.LiveRouteContextService;
import com.routiqo.core.routeupdate.application.SignalCommandPolicy;
import com.routiqo.core.routeupdate.application.SignalStorageConflict;
import com.routiqo.core.routeupdate.application.SignalStorageDenied;
import com.routiqo.core.routeupdate.application.SignalStorageExpiryMaintenance;
import com.routiqo.core.routeupdate.application.SignalStorageRateLimited;
import com.routiqo.core.routeupdate.application.SignalStorageService;
import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import com.routiqo.core.routeupdate.domain.StoredLiveRouteContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
class SignalStoragePersistenceTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant START = Instant.parse("2026-09-13T09:00:00.123456Z");

    static { DATABASE.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired SignalStorageService configuredService;
    @Autowired SignalStorageExpiryMaintenance configuredMaintenance;
    @Autowired JdbcSignalStorageStore configuredStore;

    @Test
    void configuredStorageIssuesOnlyFromCurrentOwnedConsentAndContext() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        Fixture fixture = fixture(START, 2);
        SignalCommandGrant grant = fixture.signals().issue(fixture.actor(), fixture.journey(),
                fixture.anchors().getFirst(), Set.of(QuickSignalValue.Category.QUEUE));

        assertThat(grant.state()).isEqualTo(SignalCommandGrant.State.UNUSED);
        assertThat(grant.admission().expiresAt()).isEqualTo(START.plusSeconds(90));
        assertThat(grant.toString()).isEqualTo("SignalCommandGrant[private]");
        assertThat(grantCount(fixture.actor())).isEqualTo(1);
        assertThat(configuredService).isNotNull();
        assertThat(configuredMaintenance).isInstanceOf(JdbcSignalStorageExpiryMaintenance.class);
        assertThat(configuredStore.toString()).isEqualTo("JdbcSignalStorageStore[private]");

        assertDenied(() -> fixture.signals().issue(fixture.actor(), fixture.journey(),
                UUID.randomUUID(), Set.of(QuickSignalValue.Category.QUEUE)));
        UUID other = account();
        assertThatThrownBy(() -> fixture.signals().issue(other, fixture.journey(),
                fixture.anchors().getFirst(), Set.of(QuickSignalValue.Category.QUEUE)))
                .isInstanceOf(RuntimeException.class).hasNoCause();
    }

    @Test
    void acceptanceConsumesGrantPersistsMicrosAndRetainedReplayDoesNotChargeBudget() {
        Fixture fixture = fixture(START, 1);
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
        SignalCommandPolicy.SubmissionFingerprint submission = fingerprint(
                grant, QuickSignalValue.QUEUE_UNDER_5);
        QuickSignalReceipt accepted = fixture.signals().accept(fixture.actor(), grant.commandId(),
                submission, Duration.ofSeconds(30).plusNanos(999),
                Duration.ofMinutes(5).plusNanos(999));

        assertThat(accepted.signal().expiresAt()).isEqualTo(START.plusSeconds(30));
        assertThat(accepted.retainUntil()).isEqualTo(START.plusSeconds(300));
        assertThat(storedGrant(fixture.actor(), grant.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.CONSUMED);
        assertThat(storedReceipt(fixture.actor(), grant.commandId())).isEqualTo(accepted);
        assertThat(budget(fixture.actor(), "ACCEPT")).isEqualTo(1);

        fixture.clock().set(START.plusSeconds(2));
        QuickSignalReceipt replay = fixture.signals().accept(fixture.actor(), grant.commandId(),
                submission, Duration.ofNanos(1), Duration.ofNanos(1));
        assertThat(replay).isEqualTo(accepted);
        assertThat(budget(fixture.actor(), "ACCEPT")).isEqualTo(1);
        assertConflict(() -> fixture.signals().accept(fixture.actor(), grant.commandId(),
                fingerprint(grant, QuickSignalValue.QUEUE_OVER_30),
                Duration.ofMinutes(1), Duration.ofMinutes(2)));
    }

    @Test
    void durationsAreValidatedBeforeMicrosecondFloorAndRollbackAllWork() {
        Fixture fixture = fixture(START, 1);
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
        SignalCommandPolicy.SubmissionFingerprint submission = fingerprint(
                grant, QuickSignalValue.QUEUE_UNDER_5);
        assertConflict(() -> fixture.signals().accept(fixture.actor(), grant.commandId(), submission,
                Duration.ofNanos(999), Duration.ofMinutes(1)));
        assertConflict(() -> fixture.signals().accept(fixture.actor(), grant.commandId(), submission,
                Duration.ofSeconds(1).plusNanos(1), Duration.ofSeconds(1)));
        assertThat(receiptCount(fixture.actor())).isZero();
        assertThat(budget(fixture.actor(), "ACCEPT")).isZero();
        assertThat(storedGrant(fixture.actor(), grant.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.UNUSED);

        jdbc().execute("""
            CREATE FUNCTION signal_receipt_test_failure() RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'private fixture'; END $$
            """);
        jdbc().execute("""
            CREATE TRIGGER signal_receipt_test_failure BEFORE INSERT ON quick_signal_receipt
            FOR EACH ROW EXECUTE FUNCTION signal_receipt_test_failure()
            """);
        try {
            assertThatThrownBy(() -> fixture.signals().accept(fixture.actor(), grant.commandId(),
                    submission, Duration.ofMinutes(1), Duration.ofMinutes(2)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc().execute("DROP TRIGGER signal_receipt_test_failure ON quick_signal_receipt");
            jdbc().execute("DROP FUNCTION signal_receipt_test_failure()");
        }
        assertThat(receiptCount(fixture.actor())).isZero();
        assertThat(budget(fixture.actor(), "ACCEPT")).isZero();
        assertThat(storedGrant(fixture.actor(), grant.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.UNUSED);
    }

    @Test
    void concurrentDuplicateAcceptanceCreatesOneReceiptAndOneBudgetCharge() throws Exception {
        Fixture setup = fixture(START, 1);
        SignalCommandGrant grant = issue(setup, QuickSignalValue.Category.QUEUE);
        SignalCommandPolicy.SubmissionFingerprint submission = fingerprint(
                grant, QuickSignalValue.QUEUE_5_TO_15);
        Services first = services(setup.clock());
        Services second = services(setup.clock());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<QuickSignalReceipt>> futures = new ArrayList<>();
            for (SignalStorageService service : List.of(first.signals(), second.signals())) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return service.accept(setup.actor(), grant.commandId(), submission,
                            Duration.ofMinutes(1), Duration.ofMinutes(2));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS))
                    .isEqualTo(futures.get(1).get(10, TimeUnit.SECONDS));
        }
        assertThat(receiptCount(setup.actor())).isEqualTo(1);
        assertThat(budget(setup.actor(), "ACCEPT")).isEqualTo(1);
    }

    @Test
    void activeContributionSlotSupersedesAcrossValuesAndJourneys() {
        Fixture first = fixture(START, 1);
        SignalCommandGrant queue1 = issue(first, QuickSignalValue.Category.QUEUE);
        QuickSignalReceipt original = accept(first, queue1, QuickSignalValue.QUEUE_UNDER_5);
        SignalCommandGrant queue2 = issue(first, QuickSignalValue.Category.QUEUE);
        QuickSignalReceipt replacement = accept(first, queue2, QuickSignalValue.QUEUE_OVER_30);
        assertThat(storedReceipt(first.actor(), original.signal().signalId()).state())
                .isEqualTo(QuickSignalReceipt.State.SUPERSEDED);
        assertThat(replacement.state()).isEqualTo(QuickSignalReceipt.State.ACTIVE);

        first.journeys().complete(first.actor(), first.journey());
        Fixture second = fixtureForActor(first.actor(), START.plusSeconds(1),
                first.anchors().getFirst());
        SignalCommandGrant queue3 = issue(second, QuickSignalValue.Category.QUEUE);
        accept(second, queue3, QuickSignalValue.QUEUE_5_TO_15);
        assertThat(storedReceipt(first.actor(), replacement.signal().signalId()).state())
                .isEqualTo(QuickSignalReceipt.State.SUPERSEDED);
        assertThat(activeSlots(first.actor(), first.anchors().getFirst(), "QUEUE")).isEqualTo(1);
    }

    @Test
    void ownerWithdrawalIsTerminalRetainedAndDoesNotRenewTimes() {
        Fixture fixture = fixture(START, 1);
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.TRAFFIC);
        QuickSignalReceipt receipt = accept(fixture, grant, QuickSignalValue.TRAFFIC_SLOW);
        fixture.clock().set(START.plusSeconds(1));
        QuickSignalReceipt withdrawn = fixture.signals().withdraw(
                fixture.actor(), fixture.journey(), grant.commandId());
        assertThat(withdrawn.state()).isEqualTo(QuickSignalReceipt.State.WITHDRAWN);
        assertThat(withdrawn.retainUntil()).isEqualTo(receipt.retainUntil());
        assertThat(fixture.signals().withdraw(fixture.actor(), fixture.journey(), grant.commandId()))
                .isEqualTo(withdrawn);
        assertThat(fixture.signals().accept(fixture.actor(), grant.commandId(),
                fingerprint(grant, QuickSignalValue.TRAFFIC_SLOW),
                Duration.ofMinutes(1), Duration.ofMinutes(2))).isEqualTo(withdrawn);
    }

    @Test
    void currentConsentContextAndCompletionDenyNewAcceptanceButNotRetainedReplay() {
        Fixture ghost = fixture(START, 1);
        SignalCommandGrant ghostGrant = issue(ghost, QuickSignalValue.Category.QUEUE);
        ghost.consents().change(ghost.actor(), ghost.journey(), 1, false);
        assertDenied(() -> accept(ghost, ghostGrant, QuickSignalValue.QUEUE_UNDER_5));

        Fixture changed = fixture(START, 1);
        SignalCommandGrant changedGrant = issue(changed, QuickSignalValue.Category.QUEUE);
        StoredLiveRouteContext old = changed.context();
        changed.contexts().replace(changed.actor(), changed.journey(), Set.copyOf(changed.anchors()),
                Duration.ofHours(1), Optional.of(old.context().contextId()));
        assertDenied(() -> accept(changed, changedGrant, QuickSignalValue.QUEUE_UNDER_5));

        Fixture completed = fixture(START, 1);
        SignalCommandGrant completedGrant = issue(completed, QuickSignalValue.Category.QUEUE);
        QuickSignalReceipt prior = accept(completed, completedGrant, QuickSignalValue.QUEUE_UNDER_5);
        completed.journeys().complete(completed.actor(), completed.journey());
        assertThat(completed.signals().accept(completed.actor(), completedGrant.commandId(),
                fingerprint(completedGrant, QuickSignalValue.QUEUE_UNDER_5),
                Duration.ofNanos(1), Duration.ofNanos(1))).isEqualTo(prior);
    }

    @Test
    void contextExpiryAfterWaitingForGrantLockDeniesAndRollsBack() throws Exception {
        Fixture fixture = fixture(START, 1, Duration.ofSeconds(2));
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> holdGrant(grant, locked, release));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> accepting = executor.submit(() -> {
                try {
                    return accept(fixture, grant, QuickSignalValue.QUEUE_UNDER_5);
                } catch (SignalStorageDenied denied) {
                    return denied;
                }
            });
            assertThat(waitingFor("%signal_command_grant%FOR UPDATE%")).isTrue();
            fixture.clock().set(START.plusSeconds(2));
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(accepting.get(5, TimeUnit.SECONDS)).isInstanceOf(SignalStorageDenied.class);
        }
        assertThat(receiptCount(fixture.actor())).isZero();
        assertThat(storedGrant(fixture.actor(), grant.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.UNUSED);
    }

    @Test
    void acceptanceWaitsBehindGhostThenDeniesWithoutConsumptionOrBudget() throws Exception {
        Fixture fixture = fixture(START, 1);
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
        JdbcTemplate jdbc = jdbc();
        var store = new JdbcJourneyStore(jdbc, new JdbcAccountWriteAuthority(jdbc, manager()));
        var consent = new JdbcPresenceConsentParticipant(jdbc);
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> ghost = executor.submit(() -> store.withOwnedJourney(
                    fixture.actor(), fixture.journey(), journey -> {
                        consent.change(journey, 1, false);
                        revoked.countDown();
                        await(release);
                        return null;
                    }));
            assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> accepting = executor.submit(() -> {
                try {
                    return accept(fixture, grant, QuickSignalValue.QUEUE_UNDER_5);
                } catch (SignalStorageDenied denied) {
                    return denied;
                }
            });
            assertThat(waitingFor("%routiqo_account%FOR UPDATE%")).isTrue();
            release.countDown();
            ghost.get(5, TimeUnit.SECONDS);
            assertThat(accepting.get(5, TimeUnit.SECONDS)).isInstanceOf(SignalStorageDenied.class);
        }
        assertThat(receiptCount(fixture.actor())).isZero();
        assertThat(budget(fixture.actor(), "ACCEPT")).isZero();
        assertThat(storedGrant(fixture.actor(), grant.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.UNUSED);
    }

    @Test
    void acceptanceWaitsBehindCompletionThenDeniesWithoutConsumptionOrBudget() throws Exception {
        Fixture fixture = fixture(START, 1);
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
        JdbcTemplate jdbc = jdbc();
        var store = new JdbcJourneyStore(jdbc, new JdbcAccountWriteAuthority(jdbc, manager()));
        var consent = new JdbcPresenceConsentParticipant(jdbc);
        var context = new JdbcLiveRouteContextParticipant(jdbc, fixture.clock());
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        com.routiqo.core.journey.application.JourneyCompletionParticipant blocker = journey -> {
            completed.countDown();
            await(release);
        };
        JourneyService completing = new JourneyService(store, store,
                List.of(context, consent, blocker), fixture.clock());
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> completion = executor.submit(
                    () -> completing.complete(fixture.actor(), fixture.journey()));
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> accepting = executor.submit(() -> {
                try {
                    return accept(fixture, grant, QuickSignalValue.QUEUE_UNDER_5);
                } catch (SignalStorageDenied denied) {
                    return denied;
                }
            });
            assertThat(waitingFor("%routiqo_account%FOR UPDATE%")).isTrue();
            release.countDown();
            completion.get(5, TimeUnit.SECONDS);
            assertThat(accepting.get(5, TimeUnit.SECONDS)).isInstanceOf(SignalStorageDenied.class);
        }
        assertThat(receiptCount(fixture.actor())).isZero();
        assertThat(budget(fixture.actor(), "ACCEPT")).isZero();
        assertThat(storedGrant(fixture.actor(), grant.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.UNUSED);
    }

    @Test
    void fixedMinuteBudgetsSpanAdaptersAndJourneyChangesAndRejectClockRollback() {
        Fixture fixture = fixture(START, 1);
        for (int count = 0; count < 5; count++) {
            SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
            accept(fixture, grant, QuickSignalValue.QUEUE_UNDER_5);
        }
        SignalCommandGrant sixth = issue(fixture, QuickSignalValue.Category.QUEUE);
        assertThatThrownBy(() -> accept(fixture, sixth, QuickSignalValue.QUEUE_UNDER_5))
                .isInstanceOf(SignalStorageRateLimited.class).hasNoCause();
        assertThat(budget(fixture.actor(), "ACCEPT")).isEqualTo(5);

        for (int count = 6; count < 10; count++) issue(fixture, QuickSignalValue.Category.QUEUE);
        fixture.journeys().complete(fixture.actor(), fixture.journey());
        Fixture next = fixtureForActor(fixture.actor(), START.plusSeconds(1),
                fixture.anchors().getFirst());
        assertThatThrownBy(() -> issue(next, QuickSignalValue.Category.QUEUE))
                .isInstanceOf(SignalStorageRateLimited.class).hasNoCause();
        jdbc().update("UPDATE signal_actor_budget SET bucket_start = ? WHERE actor_id = ?",
                Timestamp.from(START.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)),
                next.actor());
        assertDenied(() -> next.signals().issue(next.actor(), next.journey(),
                next.anchors().getFirst(), Set.of(QuickSignalValue.Category.TRAFFIC)));
    }

    @Test
    void cleanupKeepsReceiptsIndependentAndConsumedGrantPreventsReplayAfterReceiptPurge() {
        Fixture retained = fixture(START, 1);
        SignalCommandGrant retainedGrant = issue(retained, QuickSignalValue.Category.QUEUE);
        QuickSignalReceipt retainedReceipt = retained.signals().accept(retained.actor(),
                retainedGrant.commandId(), fingerprint(retainedGrant, QuickSignalValue.QUEUE_UNDER_5),
                Duration.ofSeconds(1), Duration.ofMinutes(5));
        JdbcSignalStorageExpiryMaintenance afterGrant = maintenance(START.plusSeconds(91));
        assertThat(afterGrant.purgeExpiredGrants(100)).isGreaterThanOrEqualTo(1);
        assertThat(grantExists(retained.actor(), retainedGrant.commandId())).isFalse();
        assertThat(receiptExists(retained.actor(), retainedGrant.commandId())).isTrue();
        retained.clock().set(START.plusSeconds(91));
        assertThat(retained.signals().accept(retained.actor(), retainedGrant.commandId(),
                fingerprint(retainedGrant, QuickSignalValue.QUEUE_UNDER_5),
                Duration.ofNanos(1), Duration.ofNanos(1))).isEqualTo(retainedReceipt);

        Fixture shortReceipt = fixture(START, 1);
        SignalCommandGrant consumed = issue(shortReceipt, QuickSignalValue.Category.TRAFFIC);
        accept(shortReceipt, consumed, QuickSignalValue.TRAFFIC_MOVING,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(maintenance(START.plusSeconds(2)).purgeExpiredReceipts(100))
                .isGreaterThanOrEqualTo(1);
        assertThat(receiptExists(shortReceipt.actor(), consumed.commandId())).isFalse();
        assertThat(grantExists(shortReceipt.actor(), consumed.commandId())).isTrue();
        assertThat(storedGrant(shortReceipt.actor(), consumed.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.CONSUMED);
        shortReceipt.clock().set(START.plusSeconds(2));
        assertDenied(() -> accept(shortReceipt, consumed, QuickSignalValue.TRAFFIC_MOVING));
        assertThat(storedGrant(shortReceipt.actor(), consumed.commandId()).state())
                .isEqualTo(SignalCommandGrant.State.CONSUMED);
    }

    @Test
    void expiredStoredReceiptAndMissingGrantDenyWithoutNewBudget() {
        Fixture fixture = fixture(START, 1);
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
        accept(fixture, grant, QuickSignalValue.QUEUE_UNDER_5,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        fixture.clock().set(START.plusSeconds(1));
        assertDenied(() -> accept(fixture, grant, QuickSignalValue.QUEUE_UNDER_5));
        assertThat(budget(fixture.actor(), "ACCEPT")).isEqualTo(1);

        UUID missing = UUID.randomUUID();
        var fake = new SignalCommandPolicy.SubmissionFingerprint(fixture.journey(),
                fixture.anchors().getFirst(), QuickSignalValue.QUEUE_UNDER_5, 1,
                fixture.context().context().contextId(), fixture.context().context().revision());
        assertDenied(() -> fixture.signals().accept(fixture.actor(), missing, fake,
                Duration.ofMinutes(1), Duration.ofMinutes(2)));
    }

    @Test
    void cleanupIsBoundedSkipsLockedRowsAndRejectsAmbientTransactions() throws Exception {
        Fixture first = fixture(START, 1);
        Fixture second = fixture(START, 1);
        SignalCommandGrant firstGrant = issue(first, QuickSignalValue.Category.QUEUE);
        issue(second, QuickSignalValue.Category.QUEUE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> holdGrant(firstGrant, locked, release));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            JdbcSignalStorageExpiryMaintenance maintenance = maintenance(START.plusSeconds(91));
            assertThat(maintenance.purgeExpiredGrants(1)).isEqualTo(1);
            assertThat(grantCount(first.actor())).isEqualTo(1);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(maintenance.purgeExpiredGrants(100)).isGreaterThanOrEqualTo(1);
        }
        for (int limit : List.of(0, 101)) {
            assertThatThrownBy(() -> maintenance(START).purgeExpiredReceipts(limit))
                    .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        }
        TransactionTemplate transaction = new TransactionTemplate(manager());
        assertThatThrownBy(() -> transaction.execute(
                status -> maintenance(START).purgeExpiredGrants(1)))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test
    void accountDeletionCascadesGrantsReceiptsAndBudgets() {
        Fixture fixture = fixture(START, 1);
        SignalCommandGrant grant = issue(fixture, QuickSignalValue.Category.QUEUE);
        accept(fixture, grant, QuickSignalValue.QUEUE_UNDER_5);
        String tokenHash = session(fixture.actor());
        new JdbcSessionStore(jdbc(), manager()).deleteAccount(tokenHash, START.plusSeconds(1));
        assertThat(grantCount(fixture.actor())).isZero();
        assertThat(receiptCount(fixture.actor())).isZero();
        assertThat(budget(fixture.actor(), "GRANT")).isZero();
    }

    @Test
    void rawStoreRequiresTransactionAndErrorsAreGeneric() {
        UUID actor = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        assertThatThrownBy(() -> configuredStore.findGrant(actor, command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Signal storage transaction is required").hasNoCause();
        for (RuntimeException error : List.of(new SignalStorageDenied(),
                new SignalStorageConflict(), new SignalStorageRateLimited())) {
            assertThat(error).hasNoCause();
            assertThat(error.toString()).doesNotContain(actor.toString(), command.toString());
        }
    }

    private Fixture fixture(Instant now, int anchors) {
        return fixture(now, anchors, Duration.ofHours(1));
    }

    private Fixture fixture(Instant now, int anchorCount, Duration contextLifetime) {
        UUID actor = account();
        MutableClock clock = new MutableClock(now);
        Services services = services(clock);
        UUID journey = start(services.journeys(), actor);
        List<UUID> anchors = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(anchorCount).toList();
        services.consents().change(actor, journey, 0, true);
        StoredLiveRouteContext context = services.contexts().replace(actor, journey,
                Set.copyOf(anchors), contextLifetime, Optional.empty());
        return new Fixture(actor, journey, anchors, context, clock, services.journeys(),
                services.consents(), services.contexts(), services.signals());
    }

    private Fixture fixtureForActor(UUID actor, Instant now, UUID anchor) {
        MutableClock clock = new MutableClock(now);
        Services services = services(clock);
        UUID journey = start(services.journeys(), actor);
        services.consents().change(actor, journey, 0, true);
        StoredLiveRouteContext context = services.contexts().replace(actor, journey,
                Set.of(anchor), Duration.ofHours(1), Optional.empty());
        return new Fixture(actor, journey, List.of(anchor), context, clock, services.journeys(),
                services.consents(), services.contexts(), services.signals());
    }

    private Services services(MutableClock clock) {
        JdbcTemplate jdbc = jdbc();
        var store = new JdbcJourneyStore(jdbc, new JdbcAccountWriteAuthority(jdbc, manager()));
        var consentParticipant = new JdbcPresenceConsentParticipant(jdbc);
        var contextParticipant = new JdbcLiveRouteContextParticipant(jdbc, clock);
        var journeys = new JourneyService(store, store,
                List.of(contextParticipant, consentParticipant), clock);
        var consents = new PresenceConsentService(store, consentParticipant);
        var contexts = new LiveRouteContextService(store, contextParticipant);
        var signals = new SignalStorageService(store, consentParticipant, contextParticipant,
                new JdbcSignalStorageStore(jdbc), clock);
        return new Services(journeys, consents, contexts, signals);
    }

    private SignalCommandGrant issue(Fixture fixture, QuickSignalValue.Category category) {
        return fixture.signals().issue(fixture.actor(), fixture.journey(),
                fixture.anchors().getFirst(), Set.of(category));
    }

    private QuickSignalReceipt accept(
            Fixture fixture, SignalCommandGrant grant, QuickSignalValue value) {
        return accept(fixture, grant, value, Duration.ofMinutes(1), Duration.ofMinutes(2));
    }

    private QuickSignalReceipt accept(Fixture fixture, SignalCommandGrant grant,
            QuickSignalValue value, Duration evidence, Duration retention) {
        return fixture.signals().accept(fixture.actor(), grant.commandId(), fingerprint(grant, value),
                evidence, retention);
    }

    private static SignalCommandPolicy.SubmissionFingerprint fingerprint(
            SignalCommandGrant grant, QuickSignalValue value) {
        var admission = grant.admission();
        return new SignalCommandPolicy.SubmissionFingerprint(admission.journeyId(),
                admission.anchorId(), value, admission.consentGeneration(), admission.contextId(),
                admission.routeRevision());
    }

    private SignalCommandGrant storedGrant(UUID actor, UUID command) {
        return new TransactionTemplate(manager()).execute(
                status -> configuredStore.findGrant(actor, command).orElseThrow());
    }

    private QuickSignalReceipt storedReceipt(UUID actor, UUID command) {
        return new TransactionTemplate(manager()).execute(
                status -> configuredStore.findReceipt(actor, command).orElseThrow());
    }

    private long grantCount(UUID actor) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM signal_command_grant WHERE actor_id = ?", Long.class, actor);
    }

    private long receiptCount(UUID actor) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM quick_signal_receipt WHERE actor_id = ?", Long.class, actor);
    }

    private boolean grantExists(UUID actor, UUID command) {
        return Boolean.TRUE.equals(jdbc().queryForObject("""
            SELECT EXISTS (SELECT 1 FROM signal_command_grant
            WHERE actor_id = ? AND command_id = ?)
            """, Boolean.class, actor, command));
    }

    private boolean receiptExists(UUID actor, UUID command) {
        return Boolean.TRUE.equals(jdbc().queryForObject("""
            SELECT EXISTS (SELECT 1 FROM quick_signal_receipt
            WHERE actor_id = ? AND command_id = ?)
            """, Boolean.class, actor, command));
    }

    private int activeSlots(UUID actor, UUID anchor, String category) {
        return jdbc().queryForObject("""
            SELECT count(*) FROM quick_signal_receipt
            WHERE actor_id = ? AND anchor_id = ? AND category = ? AND state = 'ACTIVE'
            """, Integer.class, actor, anchor, category);
    }

    private int budget(UUID actor, String action) {
        return Optional.ofNullable(jdbc().queryForObject("""
            SELECT coalesce(max(used_count), 0) FROM signal_actor_budget
            WHERE actor_id = ? AND action = ?
            """, Integer.class, actor, action)).orElse(0);
    }

    private JdbcSignalStorageExpiryMaintenance maintenance(Instant now) {
        return new JdbcSignalStorageExpiryMaintenance(
                jdbc(), manager(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void holdGrant(SignalCommandGrant grant, CountDownLatch locked, CountDownLatch release) {
        new TransactionTemplate(manager()).executeWithoutResult(status -> {
            jdbc().queryForObject("""
                SELECT command_id FROM signal_command_grant
                WHERE actor_id = ? AND command_id = ? FOR UPDATE
                """, UUID.class, grant.admission().actorId(), grant.commandId());
            locked.countDown();
            await(release);
        });
    }

    private boolean waitingFor(String pattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc().queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pg_stat_activity activity
                WHERE activity.datname = current_database()
                  AND cardinality(pg_blocking_pids(activity.pid)) > 0
                  AND activity.query LIKE ?)
                """, Boolean.class, pattern);
            if (Boolean.TRUE.equals(waiting)) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private UUID account() {
        UUID actor = UUID.randomUUID();
        jdbc().update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                actor, actor.toString());
        return actor;
    }

    private static UUID start(JourneyService journeys, UUID actor) {
        UUID journey = UUID.randomUUID();
        journeys.start(actor, journey, Journey.Kind.TRIP);
        return journey;
    }

    private String session(UUID actor) {
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc().update("""
            INSERT INTO auth_session(token_hash, account_id, created_at, expires_at, authenticated_at)
            VALUES (?, ?, ?, ?, ?)
            """, hash, actor, Timestamp.from(START), Timestamp.from(START.plusSeconds(900)),
                Timestamp.from(START));
        return hash;
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }

    private DataSourceTransactionManager manager() {
        return new DataSourceTransactionManager(dataSource);
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SignalStorageDenied.class)
                .hasMessage("Signal command denied").hasNoCause();
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(SignalStorageConflict.class)
                .hasMessage("Signal command changed").hasNoCause();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted");
        }
    }

    private record Services(JourneyService journeys, PresenceConsentService consents,
            LiveRouteContextService contexts, SignalStorageService signals) {}

    private record Fixture(UUID actor, UUID journey, List<UUID> anchors,
            StoredLiveRouteContext context, MutableClock clock, JourneyService journeys,
            PresenceConsentService consents, LiveRouteContextService contexts,
            SignalStorageService signals) {}

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
