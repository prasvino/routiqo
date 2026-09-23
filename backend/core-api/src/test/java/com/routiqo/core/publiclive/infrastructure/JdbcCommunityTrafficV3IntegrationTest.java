package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routing.domain.RouteRequest;
import java.sql.Timestamp;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("persistence")
class JdbcCommunityTrafficV3IntegrationTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    private static final Instant WINDOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final UUID ANCHOR = UUID.randomUUID();
    private static final UUID CATALOG_VERSION = UUID.randomUUID();
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    private final List<Owner> owners = new ArrayList<>();

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM community_traffic_report_v3");
        jdbc.update("DELETE FROM community_traffic_suppression_audit_v3");
        jdbc.update("DELETE FROM community_traffic_projection_v3");
        jdbc.update("DELETE FROM community_traffic_decision_v3");
        jdbc.update("DELETE FROM community_traffic_candidate_v3");
        owners.clear();
    }

    @Test void uniqueConsensusPublishesOnceAndReaderRequiresOwnedRelevantJourney() {
        for (int i = 0; i < 12; i++) seed(i < 10 ? "TRAFFIC_SLOW" : "TRAFFIC_MOVING");
        assertThat(publisher(WINDOW.plusSeconds(301)).publish(20)).isEqualTo(2);
        assertThat(publisher(WINDOW.plusSeconds(302)).publish(20)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_projection_v3",
                Integer.class)).isEqualTo(1);
        var store = store(WINDOW.plusSeconds(303));
        var owner = owners.getFirst();
        var feed = store.read(owner.actor(), owner.journey());
        assertThat(feed.moments()).hasSize(1);
        assertThat(feed.moments().getFirst().trafficValue()).isEqualTo("traffic_slow");
        assertThat(feed.moments().getFirst().source()).isEqualTo("community");
        assertThatThrownBy(() -> store.read(owner.actor(), owners.get(1).journey()))
                .isInstanceOf(JdbcCommunityTrafficV3.Missing.class);
        UUID ref = feed.moments().getFirst().ref();
        store.report(owner.actor(), owner.journey(), ref, UUID.randomUUID(), "INACCURATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_report_v3",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.report(owner.actor(), owner.journey(), UUID.randomUUID(),
                UUID.randomUUID(), "SPAM")).isInstanceOf(JdbcCommunityTrafficV3.Missing.class);
        assertThat(store.read(owner.actor(), owner.journey()).moments()).hasSize(1);
    }

    @Test void stoppedContributorBeforeSnapshotCausesTerminalNoOutput() {
        for (int i = 0; i < 12; i++) seed(i < 10 ? "TRAFFIC_SLOW" : "TRAFFIC_MOVING");
        jdbc.update("UPDATE community_traffic_candidate_v3 SET state = 'STOPPED', stopped_at = ? WHERE actor_id = ?",
                Timestamp.from(WINDOW.plusSeconds(299)), owners.getFirst().actor());
        assertThat(publisher(WINDOW.plusSeconds(301)).publish(20)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT outcome FROM community_traffic_decision_v3 WHERE window_start = ?",
                String.class, Timestamp.from(WINDOW))).isEqualTo("NO_OUTPUT");
        jdbc.update("UPDATE community_traffic_candidate_v3 SET state = 'ACTIVE', stopped_at = NULL WHERE actor_id = ?",
                owners.getFirst().actor());
        assertThat(publisher(WINDOW.plusSeconds(302)).publish(20)).isZero();
    }

    @Test void ghostAndExpiredOutputFailClosedEvenBeforeCleanup() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        var owner = owners.getFirst();
        jdbc.update("UPDATE presence_consent SET sharing = FALSE WHERE actor_id = ?", owner.actor());
        assertThat(publisher(WINDOW.plusSeconds(301)).publish(20)).isEqualTo(2);
        assertThatThrownBy(() -> store(WINDOW.plusSeconds(302)).read(owner.actor(), owner.journey()))
                .isInstanceOf(JdbcCommunityTrafficV3.Missing.class);
        assertThatThrownBy(() -> store(WINDOW.plusSeconds(901)).read(owners.get(1).actor(),
                owners.get(1).journey())).isInstanceOf(JdbcCommunityTrafficV3.Missing.class);
    }

    @Test void emptyTerminalWindowCannotBeReopenedByLateCandidate() {
        assertThat(publisher(WINDOW.plusSeconds(301)).publish(20)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM community_traffic_decision_v3
                WHERE catalog_version = ? AND anchor_id = ? AND window_start = ?
                """, String.class, CATALOG_VERSION, ANCHOR, Timestamp.from(WINDOW)))
                .isEqualTo("NO_OUTPUT");
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        assertThat(publisher(WINDOW.plusSeconds(302)).publish(20)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_projection_v3",
                Integer.class)).isZero();
    }

    @Test void twelveAccountsAloneDoNotOverrideTenAndEightyPercentAgreement() {
        for (int i = 0; i < 13; i++) seed(i < 10 ? "TRAFFIC_SLOW" : "TRAFFIC_MOVING");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        assertThat(jdbc.queryForObject("SELECT outcome FROM community_traffic_decision_v3 WHERE window_start = ?",
                String.class, Timestamp.from(WINDOW))).isEqualTo("NO_OUTPUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_projection_v3",
                Integer.class)).isZero();
    }

    @Test void feedServerTimeFiltersMomentThatExpiresDuringRead() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var owner = owners.getFirst();
        jdbc.update("UPDATE live_route_context SET expires_at = ? WHERE actor_id = ?",
                Timestamp.from(WINDOW.plusSeconds(1200)), owner.actor());
        Clock stepping = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(stepping.instant()).thenReturn(
                WINDOW.plusSeconds(899), WINDOW.plusSeconds(900));
        var feed = new JdbcCommunityTrafficV3(jdbc, manager, catalog(), stepping)
                .read(owner.actor(), owner.journey());
        assertThat(feed.serverTime()).isEqualTo(WINDOW.plusSeconds(900));
        assertThat(feed.moments()).isEmpty();
    }

    @Test void auditedSuppressionStopsServingWithoutChangingTerminalDecision() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        UUID ref = store(WINDOW.plusSeconds(302)).read(owners.getFirst().actor(),
                owners.getFirst().journey()).moments().getFirst().ref();
        UUID operator = UUID.randomUUID(), request = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                operator, "traffic-operator-" + operator);
        var store = store(WINDOW.plusSeconds(303));
        assertThatThrownBy(() -> store.suppress(operator, request, ref, "INACCURATE"))
                .isInstanceOf(JdbcCommunityTrafficV3.Missing.class);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_suppress', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)),
                Timestamp.from(WINDOW.plusSeconds(600)));
        store.suppress(operator, request, ref, "INACCURATE");
        store.suppress(operator, request, ref, "INACCURATE");
        assertThat(store.read(owners.getFirst().actor(), owners.getFirst().journey()).moments())
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT outcome FROM community_traffic_decision_v3 WHERE window_start = ?",
                String.class, Timestamp.from(WINDOW))).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_suppression_audit_v3",
                Integer.class)).isEqualTo(1);
    }

    @Test void catalogChangeDoesNotServeOldVersionProjection() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        UUID nextVersion = UUID.randomUUID();
        jdbc.update("UPDATE live_route_context SET catalog_version = ? WHERE actor_id = ?",
                nextVersion, owners.getFirst().actor());
        var nextCatalog = new RouteAnchorCatalog(nextVersion, catalog().anchors());
        var reader = new JdbcCommunityTrafficV3(jdbc, manager, nextCatalog,
                Clock.fixed(WINDOW.plusSeconds(302), ZoneOffset.UTC));
        assertThat(reader.read(owners.getFirst().actor(), owners.getFirst().journey()).moments())
                .isEmpty();
    }

    @Test void uncommittedShareDoesNotDelayOrEnterSnapshot() throws Exception {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        var withheld = owners.getFirst();
        // Candidate row is removed, then inserted on another connection without commit.
        var candidate = jdbc.queryForMap("SELECT * FROM community_traffic_candidate_v3 WHERE actor_id = ?",
                withheld.actor());
        jdbc.update("DELETE FROM community_traffic_candidate_v3 WHERE actor_id = ?", withheld.actor());
        try (Connection pending = dataSource.getConnection()) {
            pending.setAutoCommit(false);
            try (var insert = pending.prepareStatement("""
                    INSERT INTO community_traffic_candidate_v3(candidate_id, actor_id, command_id,
                        request_id, journey_id, anchor_id, traffic_value, window_start,
                        catalog_version, consent_generation, state, received_at, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 7, 'ACTIVE', ?, ?, ?)
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, withheld.actor());
                insert.setObject(3, candidate.get("command_id"));
                insert.setObject(4, UUID.randomUUID());
                insert.setObject(5, withheld.journey());
                insert.setObject(6, ANCHOR);
                insert.setString(7, "TRAFFIC_SLOW");
                insert.setTimestamp(8, Timestamp.from(WINDOW));
                insert.setObject(9, CATALOG_VERSION);
                insert.setTimestamp(10, Timestamp.from(WINDOW.plusSeconds(30)));
                insert.setTimestamp(11, Timestamp.from(WINDOW.plusSeconds(40)));
                insert.setTimestamp(12, Timestamp.from(WINDOW.plusSeconds(24 * 3600 + 300)));
                insert.executeUpdate();
            }
            assertThat(publisher(WINDOW.plusSeconds(301)).publish(20)).isEqualTo(2);
            pending.commit();
        }
        assertThat(jdbc.queryForObject("SELECT outcome FROM community_traffic_decision_v3 WHERE window_start = ?",
                String.class, Timestamp.from(WINDOW))).isEqualTo("NO_OUTPUT");
        assertThat(publisher(WINDOW.plusSeconds(302)).publish(20)).isZero();
    }

    @Test void twoPublishersCannotCreateCompetingTerminals() throws Exception {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> publisher(WINDOW.plusSeconds(301)).publish(20));
            var second = pool.submit(() -> publisher(WINDOW.plusSeconds(301)).publish(20));
            assertThat(first.get() + second.get()).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_decision_v3",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_projection_v3",
                Integer.class)).isEqualTo(1);
    }

    @Test void boundedCleanupRemovesSourceAndExpiredContentButKeepsTerminalTombstone() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        assertThat(new JdbcCommunityTrafficV3Cleanup(jdbc, manager,
                Clock.fixed(WINDOW.plusSeconds(25 * 3600), ZoneOffset.UTC)).cleanup(100))
                .isGreaterThanOrEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_candidate_v3",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_projection_v3",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_decision_v3",
                Integer.class)).isEqualTo(2);
    }

    @Test void failedProjectionRollsBackTerminalAndFreshOwnerCanRetry() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        jdbc.execute("""
                CREATE FUNCTION test_traffic_fail_projection() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected projection failure'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_traffic_fail_projection BEFORE INSERT
                ON community_traffic_projection_v3 FOR EACH ROW
                EXECUTE FUNCTION test_traffic_fail_projection()
                """);
        try {
            assertThatThrownBy(() -> publisher(WINDOW.plusSeconds(301)).publish(20))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_decision_v3",
                    Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_traffic_fail_projection ON community_traffic_projection_v3");
            jdbc.execute("DROP FUNCTION test_traffic_fail_projection()");
        }
        assertThat(publisher(WINDOW.plusSeconds(302)).publish(20)).isEqualTo(2);
    }

    @Test void productionDbTimeExcludesAuthorityExpiredBeforeFirstSnapshotStatement()
            throws Exception {
        Instant now = Instant.now();
        Instant floor = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 300) * 300);
        Instant window = floor.minusSeconds(300);
        for (int i = 0; i < 12; i++) seedAt("TRAFFIC_SLOW", window);
        Instant expiry = Instant.now().plusMillis(900);
        jdbc.update("UPDATE quick_signal_receipt SET evidence_expires_at = ? WHERE anchor_id = ? AND received_at >= ?",
                Timestamp.from(expiry), ANCHOR, Timestamp.from(window));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var realTimePublisher = new JdbcCommunityTrafficPublisherV3(dataSource, catalog(), null,
                () -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> realTimePublisher.publish(20));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(1100);
            release.countDown();
            assertThat(result.get()).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM community_traffic_decision_v3
                WHERE catalog_version = ? AND anchor_id = ? AND window_start = ?
                """, String.class, CATALOG_VERSION, ANCHOR, Timestamp.from(window)))
                .isEqualTo("NO_OUTPUT");
    }

    @Test void committedStopGhostCompletionRestrictionAndDeletionBeforeSnapshotExcludeCandidate()
            throws Exception {
        for (String action : List.of("STOP", "GHOST", "COMPLETE", "RESTRICT", "DELETE")) {
            clear();
            for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var publisher = new JdbcCommunityTrafficPublisherV3(dataSource, catalog(),
                    Clock.fixed(WINDOW.plusSeconds(301), ZoneOffset.UTC), () -> {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    });
            try (var pool = Executors.newSingleThreadExecutor()) {
                var result = pool.submit(() -> publisher.publish(20));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var owner = owners.getFirst();
                switch (action) {
                    case "STOP" -> jdbc.update("""
                            UPDATE community_traffic_candidate_v3
                            SET state = 'STOPPED', stopped_at = ? WHERE actor_id = ?
                            """, Timestamp.from(WINDOW.plusSeconds(300)), owner.actor());
                    case "GHOST" -> jdbc.update("UPDATE presence_consent SET sharing = FALSE WHERE actor_id = ?",
                            owner.actor());
                    case "COMPLETE" -> jdbc.update("""
                            UPDATE journey SET status = 'COMPLETED', completed_at = ? WHERE id = ?
                            """, Timestamp.from(WINDOW.plusSeconds(300)), owner.journey());
                    case "RESTRICT" -> jdbc.update("""
                            INSERT INTO live_contribution_restriction(actor_id, revision, restricted)
                            VALUES (?, 1, TRUE)
                            """, owner.actor());
                    case "DELETE" -> jdbc.update("DELETE FROM routiqo_account WHERE id = ?",
                            owner.actor());
                    default -> throw new IllegalStateException();
                }
                release.countDown();
                assertThat(result.get()).isEqualTo(2);
            }
            assertThat(jdbc.queryForObject("SELECT outcome FROM community_traffic_decision_v3 WHERE window_start = ?",
                    String.class, Timestamp.from(WINDOW))).isEqualTo("NO_OUTPUT");
        }
    }

    @Test void stopCommittedAfterSnapshotCannotRedrawAnInProgressSummary() throws Exception {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var publisher = new JdbcCommunityTrafficPublisherV3(dataSource, catalog(),
                Clock.fixed(WINDOW.plusSeconds(301), ZoneOffset.UTC), () -> {}, () -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> publisher.publish(20));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("""
                    UPDATE community_traffic_candidate_v3
                    SET state = 'STOPPED', stopped_at = ? WHERE actor_id = ?
                    """, Timestamp.from(WINDOW.plusSeconds(301)), owners.getFirst().actor());
            release.countDown();
            assertThat(result.get()).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT outcome FROM community_traffic_decision_v3 WHERE window_start = ?",
                String.class, Timestamp.from(WINDOW))).isEqualTo("PUBLISHED");
    }

    @Test void maximumCatalogCanBeDecidedInBoundedBatchesWithinServingWindow() {
        var anchors = new ArrayList<RouteAnchor>();
        for (int i = 0; i < 512; i++) anchors.add(new RouteAnchor(UUID.randomUUID(),
                new RouteRequest.Coordinate(12.9, 80.2), Set.of(QuickSignalValue.Category.TRAFFIC),
                Optional.of("Reviewed road area")));
        var largeCatalog = new RouteAnchorCatalog(UUID.randomUUID(), anchors);
        var publisher = new JdbcCommunityTrafficPublisherV3(dataSource, largeCatalog,
                Clock.fixed(WINDOW.plusSeconds(301), ZoneOffset.UTC));
        int decided = 0;
        for (int i = 0; i < 11; i++) decided += publisher.publish(100);
        assertThat(decided).isEqualTo(1024);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM community_traffic_decision_v3 WHERE catalog_version = ?
                """, Integer.class, largeCatalog.version())).isEqualTo(1024);
        assertThat(publisher.publish(100)).isZero();
    }

    private JdbcCommunityTrafficPublisherV3 publisher(Instant now) {
        return new JdbcCommunityTrafficPublisherV3(dataSource, catalog(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private JdbcCommunityTrafficV3 store(Instant now) {
        return new JdbcCommunityTrafficV3(jdbc, manager, catalog(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private RouteAnchorCatalog catalog() {
        return new RouteAnchorCatalog(CATALOG_VERSION, List.of(new RouteAnchor(ANCHOR,
                new RouteRequest.Coordinate(12.9, 80.2), Set.of(QuickSignalValue.Category.TRAFFIC),
                Optional.of("Reviewed road area"))));
    }

    private void seed(String value) {
        seedAt(value, WINDOW);
    }

    private void seedAt(String value, Instant window) {
        UUID actor = UUID.randomUUID(), journey = UUID.randomUUID(), command = UUID.randomUUID();
        UUID context = UUID.randomUUID(), person = UUID.randomUUID(), caseId = UUID.randomUUID();
        UUID reviewerA = UUID.randomUUID(), reviewerB = UUID.randomUUID();
        for (UUID id : List.of(actor, reviewerA, reviewerB))
            jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", id,
                    "community-traffic-" + id);
        jdbc.update("INSERT INTO journey(id, owner_id, kind, status, started_at) VALUES (?, ?, 'TRIP', 'ACTIVE', ?)",
                journey, actor, Timestamp.from(window.minusSeconds(300)));
        jdbc.update("INSERT INTO presence_consent(actor_id, journey_id, generation, sharing, journey_active) VALUES (?, ?, 7, TRUE, TRUE)",
                actor, journey);
        jdbc.update("""
                INSERT INTO live_route_context(actor_id, journey_id, context_id, revision,
                    anchor_ids, issued_at, expires_at, catalog_version)
                VALUES (?, ?, ?, 3, ARRAY[?]::UUID[], ?, ?, ?)
                """, actor, journey, context, ANCHOR, Timestamp.from(window.minusSeconds(30)),
                Timestamp.from(window.plusSeconds(600)), CATALOG_VERSION);
        jdbc.update("""
                INSERT INTO signal_command_grant(actor_id, command_id, journey_id, context_id,
                    route_revision, anchor_id, consent_generation, permitted_categories,
                    issued_at, expires_at, state, restriction_revision)
                VALUES (?, ?, ?, ?, 3, ?, 7, ARRAY['TRAFFIC']::TEXT[], ?, ?, 'CONSUMED', 0)
                """, actor, command, journey, context, ANCHOR, Timestamp.from(window),
                Timestamp.from(window.plusSeconds(60)));
        jdbc.update("""
                INSERT INTO quick_signal_receipt(actor_id, command_id, journey_id, anchor_id,
                    signal_value, category, consent_generation, context_id, route_revision,
                    received_at, evidence_expires_at, retain_until, state)
                VALUES (?, ?, ?, ?, ?, 'TRAFFIC', 7, ?, 3, ?, ?, ?, 'ACTIVE')
                """, actor, command, journey, ANCHOR, value, context,
                Timestamp.from(window.plusSeconds(30)), Timestamp.from(window.plusSeconds(600)),
                Timestamp.from(window.plusSeconds(3600)));
        jdbc.update("INSERT INTO live_verification_case(id, account_id, person_ref, expires_at) VALUES (?, ?, ?, ?)",
                caseId, actor, person, Timestamp.from(window.plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO live_verified_contributor(account_id, case_id, person_ref, revision,
                    state, first_reviewer_id, second_reviewer_id, expires_at)
                VALUES (?, ?, ?, 2, 'active', ?, ?, ?)
                """, actor, caseId, person, reviewerA, reviewerB,
                Timestamp.from(window.plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO community_traffic_candidate_v3(candidate_id, actor_id, command_id,
                    request_id, journey_id, anchor_id, traffic_value, window_start, catalog_version,
                    consent_generation, state, received_at, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 7, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(), actor, command, UUID.randomUUID(), journey, ANCHOR,
                value, Timestamp.from(window), CATALOG_VERSION,
                Timestamp.from(window.plusSeconds(30)), Timestamp.from(window.plusSeconds(40)),
                Timestamp.from(window.plusSeconds(24 * 3600 + 300)));
        owners.add(new Owner(actor, journey));
    }

    private record Owner(UUID actor, UUID journey) {}
}
