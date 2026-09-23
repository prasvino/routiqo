package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.routeupdate.domain.QuickSignalValue;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routing.domain.RouteRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class JdbcPrivateLiveWindowPublisherTest {
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
    @Autowired PlatformTransactionManager manager;

    @BeforeEach void clearOwnWindow() {
        jdbc.update("DELETE FROM private_live_window_decision WHERE anchor_id = ?", ANCHOR);
        jdbc.update("DELETE FROM public_signal_intent WHERE anchor_id = ?", ANCHOR);
    }

    @Test void closedSparseWindowIsDecidedOnceAndNeverRecomputed() {
        seedIntent();
        var beforeClose = publisher(WINDOW.plusSeconds(299));
        assertThat(beforeClose.evaluate(10)).isZero();
        var afterClose = publisher(WINDOW.plusSeconds(301));
        assertThat(afterClose.evaluate(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT decision FROM private_live_window_decision
            WHERE anchor_id = ? AND category = 'TRAFFIC' AND window_start = ?
            """, String.class, ANCHOR, Timestamp.from(WINDOW))).isEqualTo("NO_RELEASE");
        assertThat(jdbc.queryForObject("""
            SELECT signal_value FROM private_live_window_decision
            WHERE anchor_id = ? AND category = 'TRAFFIC' AND window_start = ?
            """, String.class, ANCHOR, Timestamp.from(WINDOW))).isNull();
        assertThat(afterClose.evaluate(10)).isZero();
        assertThatThrownBy(() -> afterClose.evaluate(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingCuratedAnchorFailsClosed() {
        seedIntent();
        var wrongCatalog = new RouteAnchorCatalog(CATALOG_VERSION,
                List.of(new RouteAnchor(UUID.randomUUID(),
                    new RouteRequest.Coordinate(12.9, 80.2), Set.of(QuickSignalValue.Category.TRAFFIC))));
        var evaluator = new JdbcPrivateLiveWindowPublisher(jdbc, manager, wrongCatalog,
                Clock.fixed(WINDOW.plusSeconds(301), ZoneOffset.UTC));
        assertThat(evaluator.evaluate(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT decision FROM private_live_window_decision
            WHERE anchor_id = ? AND category = 'TRAFFIC' AND window_start = ?
            """, String.class, ANCHOR, Timestamp.from(WINDOW))).isEqualTo("NO_RELEASE");
    }

    @Test void candidateRequiresTwelveCurrentPeopleAndTenMatchingValues() {
        for (int index = 0; index < 12; index++)
            seedEligibleIntent(index < 10 ? "TRAFFIC_SLOW" : "TRAFFIC_MOVING");
        assertThat(publisher(WINDOW.plusSeconds(301)).evaluate(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT signal_value FROM private_live_window_decision
            WHERE anchor_id = ? AND category = 'TRAFFIC' AND window_start = ?
            """, String.class, ANCHOR, Timestamp.from(WINDOW))).isEqualTo("TRAFFIC_SLOW");
    }

    @Test void revokedPersonIsExcludedBeforeWindowDecision() {
        for (int index = 0; index < 12; index++)
            seedEligibleIntent(index < 10 ? "TRAFFIC_SLOW" : "TRAFFIC_MOVING");
        jdbc.update("""
            UPDATE live_verified_contributor SET state = 'revoked', revision = revision + 1
            WHERE account_id = (SELECT actor_id FROM public_signal_intent
                WHERE anchor_id = ? AND category = 'TRAFFIC' AND window_start = ?
                ORDER BY actor_id LIMIT 1)
            """, ANCHOR, Timestamp.from(WINDOW));
        assertThat(publisher(WINDOW.plusSeconds(301)).evaluate(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT decision FROM private_live_window_decision
            WHERE anchor_id = ? AND category = 'TRAFFIC' AND window_start = ?
            """, String.class, ANCHOR, Timestamp.from(WINDOW))).isEqualTo("NO_RELEASE");
    }

    @Test void expiredPrivateDecisionIsPhysicallyRemovedInBoundedCleanup() {
        seedIntent();
        assertThat(publisher(WINDOW.plusSeconds(301)).evaluate(10)).isEqualTo(1);
        var cleanup = new JdbcPublicSignalIntentCleanup(jdbc, manager,
                Clock.fixed(WINDOW.plusSeconds(901), ZoneOffset.UTC));
        assertThat(cleanup.deleteExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM private_live_window_decision
            WHERE anchor_id = ? AND category = 'TRAFFIC' AND window_start = ?
            """, Integer.class, ANCHOR, Timestamp.from(WINDOW))).isZero();
    }

    private JdbcPrivateLiveWindowPublisher publisher(Instant now) {
        return new JdbcPrivateLiveWindowPublisher(jdbc, manager,
                new RouteAnchorCatalog(CATALOG_VERSION, List.of(new RouteAnchor(ANCHOR,
                    new RouteRequest.Coordinate(12.9, 80.2),
                    Set.of(QuickSignalValue.Category.TRAFFIC)))), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void seedIntent() {
        UUID actor = UUID.randomUUID(), journey = UUID.randomUUID(), command = UUID.randomUUID();
        UUID person = UUID.randomUUID(), context = UUID.randomUUID(), request = UUID.randomUUID();
        Instant received = WINDOW.plusSeconds(30);
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)", actor, "publisher-" + actor);
        jdbc.update("""
            INSERT INTO journey(id, owner_id, kind, status, started_at)
            VALUES (?, ?, 'TRIP', 'ACTIVE', ?)
            """, journey, actor, Timestamp.from(WINDOW.minusSeconds(300)));
        jdbc.update("""
            INSERT INTO quick_signal_receipt(actor_id, command_id, journey_id, anchor_id,
                signal_value, category, consent_generation, context_id, route_revision,
                received_at, evidence_expires_at, retain_until, state)
            VALUES (?, ?, ?, ?, 'TRAFFIC_SLOW', 'TRAFFIC', 7, ?, 3, ?, ?, ?, 'ACTIVE')
            """, actor, command, journey, ANCHOR, context, Timestamp.from(received),
                Timestamp.from(received.plusSeconds(600)), Timestamp.from(received.plusSeconds(3600)));
        jdbc.update("""
            INSERT INTO public_signal_intent(actor_id, command_id, journey_id, person_ref,
                verification_revision, restriction_revision, anchor_id, category, signal_value,
                window_start, received_at, evidence_expires_at, shared_at, share_request_id, state)
            VALUES (?, ?, ?, ?, 2, 0, ?, 'TRAFFIC', 'TRAFFIC_SLOW', ?, ?, ?, ?, ?, 'ACTIVE')
            """, actor, command, journey, person, ANCHOR, Timestamp.from(WINDOW),
                Timestamp.from(received), Timestamp.from(received.plusSeconds(600)),
                Timestamp.from(received.plusSeconds(20)), request);
    }

    private void seedEligibleIntent(String value) {
        UUID actor = UUID.randomUUID(), journey = UUID.randomUUID(), command = UUID.randomUUID();
        UUID person = UUID.randomUUID(), context = UUID.randomUUID(), request = UUID.randomUUID();
        UUID reviewerA = UUID.randomUUID(), reviewerB = UUID.randomUUID(), caseId = UUID.randomUUID();
        Instant received = WINDOW.plusSeconds(30);
        for (UUID account : List.of(actor, reviewerA, reviewerB))
            jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                    account, "publisher-" + account);
        jdbc.update("""
            INSERT INTO journey(id, owner_id, kind, status, started_at)
            VALUES (?, ?, 'TRIP', 'ACTIVE', ?)
            """, journey, actor, Timestamp.from(WINDOW.minusSeconds(300)));
        jdbc.update("""
            INSERT INTO presence_consent(actor_id, journey_id, generation, sharing, journey_active)
            VALUES (?, ?, 7, TRUE, TRUE)
            """, actor, journey);
        jdbc.update("""
            INSERT INTO live_route_context(actor_id, journey_id, context_id, revision,
                anchor_ids, issued_at, expires_at, catalog_version)
            VALUES (?, ?, ?, 3, ARRAY[?]::UUID[], ?, ?, ?)
            """, actor, journey, context, ANCHOR, Timestamp.from(WINDOW.minusSeconds(30)),
                Timestamp.from(WINDOW.plusSeconds(600)), CATALOG_VERSION);
        jdbc.update("""
            INSERT INTO signal_command_grant(actor_id, command_id, journey_id, context_id,
                route_revision, anchor_id, consent_generation, permitted_categories,
                issued_at, expires_at, state, restriction_revision)
            VALUES (?, ?, ?, ?, 3, ?, 7, ARRAY['TRAFFIC']::TEXT[], ?, ?, 'CONSUMED', 0)
            """, actor, command, journey, context, ANCHOR,
                Timestamp.from(WINDOW), Timestamp.from(WINDOW.plusSeconds(60)));
        jdbc.update("""
            INSERT INTO quick_signal_receipt(actor_id, command_id, journey_id, anchor_id,
                signal_value, category, consent_generation, context_id, route_revision,
                received_at, evidence_expires_at, retain_until, state)
            VALUES (?, ?, ?, ?, ?, 'TRAFFIC', 7, ?, 3, ?, ?, ?, 'ACTIVE')
            """, actor, command, journey, ANCHOR, value, context, Timestamp.from(received),
                Timestamp.from(received.plusSeconds(600)), Timestamp.from(received.plusSeconds(3600)));
        jdbc.update("""
            INSERT INTO live_verification_case(id, account_id, person_ref, expires_at)
            VALUES (?, ?, ?, ?)
            """, caseId, actor, person, Timestamp.from(WINDOW.plusSeconds(3600)));
        jdbc.update("""
            INSERT INTO live_verified_contributor(account_id, case_id, person_ref, revision,
                state, first_reviewer_id, second_reviewer_id, expires_at)
            VALUES (?, ?, ?, 2, 'active', ?, ?, ?)
            """, actor, caseId, person, reviewerA, reviewerB,
                Timestamp.from(WINDOW.plusSeconds(3600)));
        jdbc.update("""
            INSERT INTO public_signal_intent(actor_id, command_id, journey_id, person_ref,
                verification_revision, restriction_revision, anchor_id, category, signal_value,
                window_start, received_at, evidence_expires_at, shared_at, share_request_id, state)
            VALUES (?, ?, ?, ?, 2, 0, ?, 'TRAFFIC', ?, ?, ?, ?, ?, ?, 'ACTIVE')
            """, actor, command, journey, person, ANCHOR, value, Timestamp.from(WINDOW),
                Timestamp.from(received), Timestamp.from(received.plusSeconds(600)),
                Timestamp.from(received.plusSeconds(20)), request);
    }
}
