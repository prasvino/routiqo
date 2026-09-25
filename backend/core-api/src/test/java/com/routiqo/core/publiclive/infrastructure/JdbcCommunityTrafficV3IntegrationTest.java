package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.moderation.infrastructure.JdbcTrafficReview;
import com.routiqo.core.moderation.infrastructure.JdbcTrafficGrantAdmin;
import com.routiqo.core.moderation.infrastructure.AdminSessionService;
import com.routiqo.core.identity.application.GoogleIdentityVerifier;
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
    private static final Instant WINDOW = com.routiqo.core.publiclive.RecentTrafficWindow.now();
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
        jdbc.update("DELETE FROM community_traffic_review_action_audit_v3");
        jdbc.update("DELETE FROM community_traffic_review_disposition_v3");
        jdbc.update("DELETE FROM community_traffic_moderator_read_audit_v3");
        jdbc.update("DELETE FROM community_traffic_report_group_v3");
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
        store.report(owners.getFirst().actor(), owners.getFirst().journey(), ref, UUID.randomUUID(), "INACCURATE");
        var reviewer = new JdbcTrafficReview(jdbc, manager, Clock.fixed(WINDOW.plusSeconds(303), ZoneOffset.UTC));
        assertThatThrownBy(() -> reviewer.decide(operator, request, ref, "SUPPRESS", "INACCURATE", () -> {}))
                .isInstanceOf(JdbcTrafficReview.Missing.class);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_suppress', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)),
                Timestamp.from(Instant.now().plusSeconds(600)));
        reviewer.decide(operator, request, ref, "SUPPRESS", "INACCURATE", () -> {});
        reviewer.decide(operator, request, ref, "SUPPRESS", "INACCURATE", () -> {});
        assertThat(store.read(owners.getFirst().actor(), owners.getFirst().journey()).moments())
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT outcome FROM community_traffic_decision_v3 WHERE window_start = ?",
                String.class, Timestamp.from(WINDOW))).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_suppression_audit_v3",
                Integer.class)).isEqualTo(1);
    }

    private record GrantRace(UUID ref, UUID moderator, UUID administrator,
            JdbcTrafficReview reviewer, JdbcTrafficGrantAdmin grants) {}

    private GrantRace grantRace() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var owner = owners.getFirst();
        UUID ref = store(WINDOW.plusSeconds(302)).read(owner.actor(), owner.journey()).moments().getFirst().ref();
        store(WINDOW.plusSeconds(302)).report(owner.actor(), owner.journey(), ref, UUID.randomUUID(), "SPAM");
        UUID moderator = UUID.randomUUID(), administrator = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", moderator, "grant-race-mod-" + moderator);
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", administrator, "grant-race-admin-" + administrator);
        Instant databaseNow = Instant.now();
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_review', ?, ?), (?, 'traffic_grant_admin', ?, ?)
                """, moderator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(databaseNow.plusSeconds(600)),
                administrator, Timestamp.from(databaseNow.minusSeconds(10)), Timestamp.from(databaseNow.plusSeconds(600)));
        Clock now = Clock.fixed(WINDOW.plusSeconds(303), ZoneOffset.UTC);
        return new GrantRace(ref, moderator, administrator,
                new JdbcTrafficReview(jdbc, manager, now), new JdbcTrafficGrantAdmin(jdbc, manager));
    }

    @Test void revokeFirstPreventsModeratorDecisionCommit() throws Exception {
        GrantRace race = grantRace();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoke = pool.submit(() -> race.grants().change(race.administrator(), race.moderator(), UUID.randomUUID(),
                    "traffic_review", "REVOKE", "SECURITY_RESPONSE", null, () -> {
                        if (checks.incrementAndGet() == 2) { locked.countDown(); awaitGrantRace(release); }
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var decision = pool.submit(() -> {
                try {
                    race.reviewer().decide(race.moderator(), UUID.randomUUID(), race.ref(), "DISMISS", "SPAM",
                            () -> jdbc.query("SELECT id FROM routiqo_account WHERE id = ? FOR SHARE",
                                    (rs, n) -> rs.getObject(1, UUID.class), race.moderator()));
                    return "committed";
                } catch (JdbcTrafficReview.Missing missing) { return "denied"; }
            });
            assertThat(decision.isDone()).isFalse();
            release.countDown();
            revoke.get(10, TimeUnit.SECONDS);
            assertThat(decision.get(10, TimeUnit.SECONDS)).isEqualTo("denied");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_review_action_audit_v3 WHERE ref = ?",
                Integer.class, race.ref())).isZero();
    }

    @Test void moderatorDecisionFirstCompletesBeforeRevocation() throws Exception {
        GrantRace race = grantRace();
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var decision = pool.submit(() -> race.reviewer().decide(race.moderator(), UUID.randomUUID(),
                    race.ref(), "DISMISS", "SPAM", () -> {
                        jdbc.query("SELECT id FROM routiqo_account WHERE id = ? FOR SHARE",
                                (rs, n) -> rs.getObject(1, UUID.class), race.moderator());
                        if (first.compareAndSet(true, false)) { checked.countDown(); awaitGrantRace(release); }
                    }));
            assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = pool.submit(() -> race.grants().change(race.administrator(), race.moderator(), UUID.randomUUID(),
                    "traffic_review", "REVOKE", "SECURITY_RESPONSE", null, () -> {}));
            assertThat(revoke.isDone()).isFalse();
            release.countDown();
            decision.get(10, TimeUnit.SECONDS);
            assertThat(revoke.get(10, TimeUnit.SECONDS).expiresAt()).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_review_action_audit_v3 WHERE ref = ?",
                Integer.class, race.ref())).isEqualTo(1);
    }

    @Test void moderatorQueueAndGrantRevocationUseAccountBeforeGrantOrder() throws Exception {
        GrantRace race = grantRace();
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var queue = pool.submit(() -> race.reviewer().queue(race.moderator(), null, 20, () -> {
                jdbc.query("SELECT id FROM routiqo_account WHERE id = ? FOR SHARE",
                        (rs, n) -> rs.getObject(1, UUID.class), race.moderator());
                if (first.compareAndSet(true, false)) { checked.countDown(); awaitGrantRace(release); }
            }));
            assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = pool.submit(() -> race.grants().change(race.administrator(), race.moderator(), UUID.randomUUID(),
                    "traffic_review", "REVOKE", "SECURITY_RESPONSE", null, () -> {}));
            assertThat(revoke.isDone()).isFalse();
            release.countDown();
            assertThat(queue.get(10, TimeUnit.SECONDS).items()).hasSize(1);
            assertThat(revoke.get(10, TimeUnit.SECONDS).expiresAt()).isNull();
        }
    }

    @Test void moderatorGrantExpiryUsesDatabaseTimeDespiteSkewedProjectionClock() {
        GrantRace race = grantRace(); // Reviewer clock is fixed at the old projection window.
        assertThat(race.reviewer().queue(race.moderator(), null, 20, () -> {}).items()).hasSize(1);
        jdbc.update("""
                UPDATE moderation_operator_grant SET issued_at = ?, expires_at = ?
                WHERE operator_id = ? AND permission = 'traffic_review'
                """, Timestamp.from(Instant.now().minusSeconds(120)),
                Timestamp.from(Instant.now().minusSeconds(1)), race.moderator());
        assertThatThrownBy(() -> race.reviewer().queue(race.moderator(), null, 20, () -> {}))
                .isInstanceOf(JdbcTrafficReview.Missing.class);
    }

    @Test void queueAfterAcquiredRevocationIsDeniedWithoutDeadlock() throws Exception {
        GrantRace race = grantRace();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoke = pool.submit(() -> race.grants().change(race.administrator(), race.moderator(), UUID.randomUUID(),
                    "traffic_review", "REVOKE", "SECURITY_RESPONSE", null, () -> {
                        if (checks.incrementAndGet() == 2) { locked.countDown(); awaitGrantRace(release); }
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var queue = pool.submit(() -> {
                try {
                    race.reviewer().queue(race.moderator(), null, 20,
                            () -> jdbc.query("SELECT id FROM routiqo_account WHERE id = ? FOR SHARE",
                                    (rs, n) -> rs.getObject(1, UUID.class), race.moderator()));
                    return "served";
                } catch (JdbcTrafficReview.Missing missing) { return "denied"; }
            });
            assertThat(queue.isDone()).isFalse();
            release.countDown();
            revoke.get(10, TimeUnit.SECONDS);
            assertThat(queue.get(10, TimeUnit.SECONDS)).isEqualTo("denied");
        }
    }

    private static void awaitGrantRace(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    @Test void dismissalReopensOnNewReportAndConflictingRetryFails() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var first = owners.getFirst();
        UUID ref = store(WINDOW.plusSeconds(302)).read(first.actor(), first.journey()).moments().getFirst().ref();
        UUID operator = UUID.randomUUID(), request = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, "traffic-review-" + operator);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_review', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        store(WINDOW.plusSeconds(302)).report(first.actor(), first.journey(), ref, UUID.randomUUID(), "SPAM");
        var reviewer = new JdbcTrafficReview(jdbc, manager, Clock.fixed(WINDOW.plusSeconds(303), ZoneOffset.UTC));
        assertThat(reviewer.queue(operator, null, 20, () -> {}).items()).hasSize(1);
        reviewer.decide(operator, request, ref, "DISMISS", "SPAM", () -> {});
        assertThat(reviewer.queue(operator, null, 20, () -> {}).items()).isEmpty();
        reviewer.decide(operator, request, ref, "DISMISS", "SPAM", () -> {});
        assertThatThrownBy(() -> reviewer.decide(operator, request, ref, "DISMISS", "POLICY", () -> {}))
                .isInstanceOf(JdbcTrafficReview.Conflict.class);
        var second = owners.get(1);
        store(WINDOW.plusSeconds(304)).report(second.actor(), second.journey(), ref, UUID.randomUUID(), "INACCURATE");
        var reopened = reviewer.queue(operator, null, 20, () -> {}).items();
        assertThat(reopened).hasSize(1);
        assertThat(reopened.getFirst().reasonCounts()).containsEntry("SPAM", 1).containsEntry("INACCURATE", 1);
        jdbc.update("DELETE FROM community_traffic_report_v3 WHERE actor_id = ?", first.actor());
        assertThat(reviewer.queue(operator, null, 20, () -> {}).items().getFirst().reasonCounts())
                .containsEntry("SPAM", 0).containsEntry("INACCURATE", 1);
        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", second.actor());
        assertThat(reviewer.queue(operator, null, 20, () -> {}).items()).isEmpty();
        assertThatThrownBy(() -> reviewer.decide(operator, UUID.randomUUID(), ref, "DISMISS", "POLICY", () -> {}))
                .isInstanceOf(JdbcTrafficReview.Missing.class);
        jdbc.update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", operator);
        assertThatThrownBy(() -> reviewer.queue(operator, null, 20, () -> {})).isInstanceOf(JdbcTrafficReview.Missing.class);
    }

    @Test void expiredProjectionIsUnavailableAndCannotBeReviewed() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var first = owners.getFirst();
        UUID ref = store(WINDOW.plusSeconds(302)).read(first.actor(), first.journey()).moments().getFirst().ref();
        store(WINDOW.plusSeconds(302)).report(first.actor(), first.journey(), ref, UUID.randomUUID(), "UNSAFE");
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, "traffic-expiry-" + operator);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_suppress', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        var reviewer = new JdbcTrafficReview(jdbc, manager, Clock.fixed(WINDOW.plusSeconds(901), ZoneOffset.UTC));
        assertThat(reviewer.queue(operator, null, 20, () -> {}).items().getFirst().evidenceStatus())
                .isEqualTo("EVIDENCE_UNAVAILABLE");
        assertThat(reviewer.queue(operator, null, 20, () -> {}).items().getFirst().areaLabel()).isNull();
        assertThatThrownBy(() -> reviewer.decide(operator, UUID.randomUUID(), ref, "SUPPRESS", "UNSAFE", () -> {}))
                .isInstanceOf(JdbcTrafficReview.Missing.class);
        assertThat(jdbc.queryForObject("SELECT suppressed_at FROM community_traffic_projection_v3 WHERE ref = ?",
                Timestamp.class, ref)).isNull();
    }

    @Test void adminExchangeRequiresExistingEnabledAccountAndCurrentFiniteGrant() {
        UUID operator = UUID.randomUUID();
        String subject = "admin-subject-" + operator;
        var admin = new AdminSessionService(jdbc, manager,
                (token, nonce) -> new GoogleIdentityVerifier.Identity("google", subject));
        var challenge = admin.begin();
        assertThatThrownBy(() -> admin.exchange(challenge.id(), challenge.binding(), "verified-token"))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routiqo_account WHERE google_subject = ?",
                Integer.class, subject)).isZero();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, subject);
        assertThatThrownBy(() -> admin.exchange(challenge.id(), challenge.binding(), "verified-token"))
                .isInstanceOf(SecurityException.class);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_review', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        var session = admin.exchange(challenge.id(), challenge.binding(), "verified-token");
        assertThat(session.accountId()).isEqualTo(operator);
        assertThat(admin.authenticate(session.credential()).accountId()).isEqualTo(operator);
        assertThatThrownBy(() -> admin.exchange(challenge.id(), challenge.binding(), "verified-token"))
                .isInstanceOf(SecurityException.class);
        jdbc.update("UPDATE routiqo_account SET enabled = FALSE WHERE id = ?", operator);
        assertThatThrownBy(() -> admin.authenticate(session.credential())).isInstanceOf(SecurityException.class);
    }

    @Test void moderatorQueueHasBoundedCursorPages() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var owner = owners.getFirst();
        UUID firstRef = store(WINDOW.plusSeconds(302)).read(owner.actor(), owner.journey()).moments().getFirst().ref();
        store(WINDOW.plusSeconds(302)).report(owner.actor(), owner.journey(), firstRef, UUID.randomUUID(), "SPAM");
        UUID secondRef = UUID.randomUUID(), secondAnchor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO community_traffic_decision_v3
                (catalog_version, anchor_id, window_start, outcome, traffic_value, decided_at, expires_at)
                VALUES (?, ?, ?, 'PUBLISHED', 'TRAFFIC_SLOW', ?, ?)
                """, CATALOG_VERSION, secondAnchor, Timestamp.from(WINDOW),
                Timestamp.from(WINDOW.plusSeconds(301)), Timestamp.from(WINDOW.plusSeconds(900)));
        jdbc.update("""
                INSERT INTO community_traffic_projection_v3
                (ref, catalog_version, anchor_id, window_start, area_label, traffic_value, expires_at)
                VALUES (?, ?, ?, ?, 'Test area', 'TRAFFIC_SLOW', ?)
                """, secondRef, CATALOG_VERSION, secondAnchor, Timestamp.from(WINDOW),
                Timestamp.from(WINDOW.plusSeconds(900)));
        UUID reportRequest = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO community_traffic_report_v3
                (actor_id, request_id, ref, reason, created_at, expires_at)
                VALUES (?, ?, ?, 'UNSAFE', ?, ?)
                """, owner.actor(), reportRequest, secondRef, Timestamp.from(WINDOW.plusSeconds(303)),
                Timestamp.from(WINDOW.plusSeconds(303 + 720L * 3600)));
        Long sequence = jdbc.queryForObject("SELECT review_sequence FROM community_traffic_report_v3 WHERE request_id = ?",
                Long.class, reportRequest);
        jdbc.update("""
                INSERT INTO community_traffic_report_group_v3
                (ref, latest, latest_sequence, inaccurate, unsafe, spam, expires_at)
                VALUES (?, ?, ?, 0, 1, 0, ?)
                """, secondRef, Timestamp.from(WINDOW.plusSeconds(303)), sequence,
                Timestamp.from(WINDOW.plusSeconds(303 + 720L * 3600)));
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, "traffic-page-" + operator);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_review', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        var reviewer = new JdbcTrafficReview(jdbc, manager, Clock.fixed(WINDOW.plusSeconds(304), ZoneOffset.UTC));
        assertThatThrownBy(() -> reviewer.queue(operator, null, 51, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        var page1 = reviewer.queue(operator, null, 1, () -> {});
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.nextCursor()).isNotNull();
        var page2 = reviewer.queue(operator, page1.nextCursor(), 1, () -> {});
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.nextCursor()).isNull();
        assertThat(Set.of(page1.items().getFirst().ref(), page2.items().getFirst().ref()))
                .containsExactlyInAnyOrder(firstRef, secondRef);
        reviewer.decide(operator, UUID.randomUUID(), secondRef, "DISMISS", "UNSAFE", () -> {});
        var skipped = reviewer.queue(operator, null, 1, () -> {});
        assertThat(skipped.items()).isEmpty();
        assertThat(skipped.nextCursor()).isNotNull();
        assertThat(reviewer.queue(operator, skipped.nextCursor(), 1, () -> {}).items().getFirst().ref())
                .isEqualTo(firstRef);
    }

    @Test void suppressionAuditFailureRollsBackProjection() {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var owner = owners.getFirst();
        UUID ref = store(WINDOW.plusSeconds(302)).read(owner.actor(), owner.journey()).moments().getFirst().ref();
        store(WINDOW.plusSeconds(302)).report(owner.actor(), owner.journey(), ref, UUID.randomUUID(), "UNSAFE");
        UUID operator = UUID.randomUUID(), priorOperator = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, "traffic-rollback-" + operator);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_suppress', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        // Existing unique audit deliberately forces the insert after UPDATE to fail.
        jdbc.update("""
                INSERT INTO community_traffic_suppression_audit_v3
                (operator_id, request_id, ref, reason, occurred_at, expires_at)
                VALUES (?, ?, ?, 'UNSAFE', ?, ?)
                """, priorOperator, UUID.randomUUID(), ref, Timestamp.from(WINDOW.plusSeconds(302)),
                Timestamp.from(WINDOW.plusSeconds(302 + 720L * 3600)));
        var reviewer = new JdbcTrafficReview(jdbc, manager, Clock.fixed(WINDOW.plusSeconds(303), ZoneOffset.UTC));
        assertThatThrownBy(() -> reviewer.decide(operator, UUID.randomUUID(), ref, "SUPPRESS", "UNSAFE", () -> {}))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT suppressed_at FROM community_traffic_projection_v3 WHERE ref = ?",
                Timestamp.class, ref)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_review_action_audit_v3 WHERE ref = ?",
                Integer.class, ref)).isZero();
    }

    @Test void concurrentSuppressionHasOneWinner() throws Exception {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var owner = owners.getFirst();
        UUID ref = store(WINDOW.plusSeconds(302)).read(owner.actor(), owner.journey()).moments().getFirst().ref();
        store(WINDOW.plusSeconds(302)).report(owner.actor(), owner.journey(), ref, UUID.randomUUID(), "UNSAFE");
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, "traffic-race-" + operator);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_suppress', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        var reviewer = new JdbcTrafficReview(jdbc, manager, Clock.fixed(WINDOW.plusSeconds(303), ZoneOffset.UTC));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var work = (java.util.concurrent.Callable<String>) () -> {
                ready.countDown(); start.await();
                try { reviewer.decide(operator, UUID.randomUUID(), ref, "SUPPRESS", "UNSAFE", () -> {}); return "won"; }
                catch (JdbcTrafficReview.Missing unavailable) { return "lost"; }
            };
            var first = pool.submit(work);
            var second = pool.submit(work);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("won", "lost");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_suppression_audit_v3 WHERE ref = ?",
                Integer.class, ref)).isEqualTo(1);
    }

    @Test void accountDeletionOfLastReportWinsRaceAgainstSuppression() throws Exception {
        for (int i = 0; i < 12; i++) seed("TRAFFIC_SLOW");
        publisher(WINDOW.plusSeconds(301)).publish(20);
        var owner = owners.getFirst();
        UUID ref = store(WINDOW.plusSeconds(302)).read(owner.actor(), owner.journey()).moments().getFirst().ref();
        store(WINDOW.plusSeconds(302)).report(owner.actor(), owner.journey(), ref, UUID.randomUUID(), "UNSAFE");
        UUID operator = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, "traffic-delete-race-" + operator);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_suppress', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        var reviewer = new JdbcTrafficReview(jdbc, manager, Clock.fixed(WINDOW.plusSeconds(303), ZoneOffset.UTC));
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var decisionStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager)
                    .executeWithoutResult(status -> {
                        jdbc.update("DELETE FROM routiqo_account WHERE id = ?", owner.actor());
                        deleted.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    }));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            var decision = pool.submit(() -> {
                decisionStarted.countDown();
                try { reviewer.decide(operator, UUID.randomUUID(), ref, "SUPPRESS", "UNSAFE", () -> {}); return "suppressed"; }
                catch (JdbcTrafficReview.Missing missing) { return "missing"; }
            });
            assertThat(decisionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(decision.isDone()).isFalse();
            release.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(decision.get(10, TimeUnit.SECONDS)).isEqualTo("missing");
        }
        assertThat(jdbc.queryForObject("SELECT suppressed_at FROM community_traffic_projection_v3 WHERE ref = ?",
                Timestamp.class, ref)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_traffic_review_action_audit_v3 WHERE ref = ?",
                Integer.class, ref)).isZero();
    }

    @Test void adminSessionExpiryIsCheckedAfterRowLockWait() throws Exception {
        UUID operator = UUID.randomUUID();
        String subject = "admin-lock-wait-" + operator;
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", operator, subject);
        jdbc.update("""
                INSERT INTO moderation_operator_grant(operator_id, permission, issued_at, expires_at)
                VALUES (?, 'traffic_review', ?, ?)
                """, operator, Timestamp.from(WINDOW.plusSeconds(280)), Timestamp.from(Instant.now().plusSeconds(600)));
        var admin = new AdminSessionService(jdbc, manager,
                (token, nonce) -> new GoogleIdentityVerifier.Identity("google", subject));
        var challenge = admin.begin();
        var session = admin.exchange(challenge.id(), challenge.binding(), "verified-token");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager)
                    .executeWithoutResult(status -> {
                        jdbc.query("SELECT token_hash FROM admin_auth_session WHERE account_id = ? FOR UPDATE",
                                (rs, n) -> rs.getString(1), operator);
                        jdbc.update("UPDATE admin_auth_session SET expires_at = ? WHERE account_id = ?",
                                Timestamp.from(Instant.now().minusSeconds(1)), operator);
                        locked.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var check = pool.submit(() -> {
                try { admin.assertCurrent(operator, session.credential()); return "current"; }
                catch (SecurityException expired) { return "expired"; }
            });
            Thread.sleep(100);
            assertThat(check.isDone()).isFalse();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(check.get(10, TimeUnit.SECONDS)).isEqualTo("expired");
        }
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
