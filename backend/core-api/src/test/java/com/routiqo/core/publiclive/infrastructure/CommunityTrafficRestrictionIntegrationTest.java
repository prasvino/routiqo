package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.identity.infrastructure.JdbcAccountWriteAuthority;
import com.routiqo.core.journey.infrastructure.JdbcJourneyStore;
import com.routiqo.core.moderation.infrastructure.JdbcContributionRestrictionParticipant;
import com.routiqo.core.privacy.application.PresenceConsentParticipant;
import com.routiqo.core.privacy.domain.PresenceConsent;
import com.routiqo.core.publiclive.application.CommunityTrafficShareService;
import com.routiqo.core.routeupdate.application.LiveRouteContextParticipant;
import com.routiqo.core.routeupdate.application.SignalStorageStore;
import com.routiqo.core.routeupdate.domain.*;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.verification.infrastructure.JdbcVerifiedContributorAuthority;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import static org.mockito.Mockito.*;

/** Uses the production JDBC account, journey, verification and restriction authorities. */
@SpringBootTest
@ActiveProfiles("persistence")
class CommunityTrafficRestrictionIntegrationTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    static { DATABASE.start(); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test void unrestrictedVerifiedAccountCanShareAndSuspensionDeniesAnotherCandidate() {
        Instant now = Instant.now();
        UUID actor = account(), reviewer = account(), otherReviewer = account();
        UUID journey = UUID.randomUUID(), person = UUID.randomUUID();
        UUID verificationCase = UUID.randomUUID(), anchor = UUID.randomUUID();
        UUID catalogVersion = UUID.randomUUID(), contextId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO journey(id, owner_id, kind, status, started_at)
            VALUES (?, ?, 'TRIP', 'ACTIVE', ?)
            """, journey, actor, Timestamp.from(now.minusSeconds(600)));
        jdbc.update("""
            INSERT INTO live_verification_case(id, account_id, person_ref, expires_at)
            VALUES (?, ?, ?, ?)
            """, verificationCase, actor, person, Timestamp.from(now.plusSeconds(3600)));
        jdbc.update("""
            INSERT INTO live_verified_contributor(account_id, case_id, person_ref,
                revision, state, first_reviewer_id, second_reviewer_id, expires_at)
            VALUES (?, ?, ?, 2, 'active', ?, ?, ?)
            """, actor, verificationCase, person, reviewer, otherReviewer,
                Timestamp.from(now.plusSeconds(3600)));

        PresenceConsentParticipant consents = mock(PresenceConsentParticipant.class);
        LiveRouteContextParticipant contexts = mock(LiveRouteContextParticipant.class);
        SignalStorageStore receipts = mock(SignalStorageStore.class);
        when(consents.read(any())).thenReturn(new PresenceConsent(actor, journey, 7, true, true));
        when(contexts.read(any())).thenReturn(Optional.of(new StoredLiveRouteContext(
                new LiveRouteContext(contextId, actor, journey, 3, Set.of(anchor)),
                now.minusSeconds(60), now.plusSeconds(600), Optional.of(catalogVersion))));
        var catalog = new RouteAnchorCatalog(catalogVersion, List.of(new RouteAnchor(anchor,
                new RouteRequest.Coordinate(80, 13), Set.of(QuickSignalValue.Category.TRAFFIC))));
        var accounts = new JdbcAccountWriteAuthority(jdbc, manager);
        var restrictions = new JdbcContributionRestrictionParticipant(jdbc);
        var service = new CommunityTrafficShareService(new JdbcJourneyStore(jdbc, accounts),
                accounts, consents, contexts, restrictions,
                new JdbcVerifiedContributorAuthority(jdbc), catalog, receipts,
                new JdbcCommunityTrafficCandidateStore(jdbc), Clock.fixed(now, ZoneOffset.UTC));

        UUID firstCommand = UUID.randomUUID();
        receipt(receipts, actor, journey, contextId, anchor, firstCommand, now, 0);
        var accepted = service.share(actor, journey, firstCommand, UUID.randomUUID());
        assertThat(accepted.trafficValue()).isEqualTo("TRAFFIC_SLOW");
        accounts.withEnabledAccount(actor, () -> {
            var prior = restrictions.read(actor);
            assertThat(prior.revision()).isZero();
            restrictions.replace(prior, prior.suspend(0));
            return null;
        });
        UUID secondCommand = UUID.randomUUID();
        receipt(receipts, actor, journey, contextId, anchor, secondCommand, now, 1);
        assertThatThrownBy(() -> service.share(actor, journey, secondCommand, UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM community_traffic_candidate_v3 WHERE actor_id = ?
            """, Integer.class, actor)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT used FROM community_traffic_daily_debit_v3 WHERE actor_id = ?
            """, Integer.class, actor)).isEqualTo(1);
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO routiqo_account(id, google_subject) VALUES (?, ?)",
                id, "community-restriction-" + id);
        return id;
    }

    private static void receipt(SignalStorageStore receipts, UUID actor, UUID journey,
            UUID contextId, UUID anchor, UUID command, Instant now,
            long restrictionRevision) {
        var admission = new SignalAdmission(actor, journey, contextId, anchor, 3, 7,
                Set.of(QuickSignalValue.Category.TRAFFIC), now.minusSeconds(30),
                now.plusSeconds(30));
        when(receipts.findGrant(actor, command)).thenReturn(Optional.of(new SignalCommandGrant(
                command, admission, restrictionRevision, SignalCommandGrant.State.CONSUMED)));
        var signal = new QuickSignal(command, actor, journey, anchor,
                QuickSignalValue.TRAFFIC_SLOW, 7, now, now.plusSeconds(600));
        when(receipts.findReceipt(actor, command)).thenReturn(Optional.of(
                new QuickSignalReceipt(signal, contextId, 3, now.plusSeconds(700),
                        QuickSignalReceipt.State.ACTIVE)));
    }
}
